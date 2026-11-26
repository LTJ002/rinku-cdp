package com.wdcdp.cdpscreen.render;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.wdcdp.cdpscreen.CdpLog;
import com.wdcdp.cdpscreen.CdpRenderQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * The Minecraft texture a page is drawn through.
 *
 * <h2>Shape follows the working implementation</h2>
 * <p>This mirrors how Rinku drives its own page texture, because that is the only
 * arrangement proven to render on 26.1.2. Three details are load-bearing and each
 * one was wrong in an earlier version of this class:</p>
 *
 * <ol>
 *   <li><b>Registered at construction, not after the first frame.</b> WebDisplays
 *       resolves the identifier through the {@code TextureManager} when it builds
 *       the page's {@code RenderType}. Registering only after a frame has been
 *       uploaded means the first {@code RenderType} is built for an identifier
 *       that does not exist yet, and the render type is then cached - so the wall
 *       keeps binding the missing-texture sprite forever.</li>
 *   <li><b>The GPU texture is owned directly</b> ({@code GpuTexture} +
 *       {@code GpuTextureView} + {@code GpuSampler} on {@link AbstractTexture}'s
 *       protected fields) instead of going through {@code DynamicTexture}. The
 *       {@code DynamicTexture} route produced a texture that decoded hundreds of
 *       frames, reported no failures, and never appeared on screen.</li>
 *   <li><b>{@link #isReady()} is the same predicate the working implementation
 *       uses</b>: a live GPU texture, a registered identifier, and a live view.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>Decoding happens on the pool; {@link #sync()} runs on the render thread and
 * is the only place GPU objects are touched. The single-slot
 * {@link AtomicReference} carries frames across: only the newest matters, so an
 * unclaimed older frame is closed rather than queued - queueing is what turns a
 * slow upload into seconds of latency behind a video.</p>
 */
public final class PageTexture extends AbstractTexture implements AutoCloseable {

    private static final AtomicInteger NAMES = new AtomicInteger();

    /**
     * Usage flags for the GPU texture.
     *
     * <p>5 is copy-destination plus sampled, which is what the working
     * implementation passes for a texture written from the CPU and read by the
     * pipeline. The wrong value is silent: the texture is created, the write is
     * rejected or ignored, and the page stays black.</p>
     */
    private static final int TEXTURE_USAGE = 5;

    /** A decoded frame waiting for the render thread. */
    private record Pending(NativeImage image, long sequence, boolean fullscreen, int layoutWidth, int layoutHeight) {}

    /** Geometry of the frame actually uploaded to the GPU. */
    public record Presentation(int width, int height, boolean fullscreen, int layoutWidth, int layoutHeight) {}
    private volatile Presentation presentation;
    private volatile int glowColor;

    public int glowColor() {
        return glowColor;
    }

    static int averageGlow(NativeImage image) {
        int red = 0, green = 0, blue = 0;
        // Sparse, stratified samples from the same image being presented.
        for (int row = 0; row < 8; row++) {
            int y = (2 * row + 1) * image.getHeight() / 16;
            for (int col = 0; col < 12; col++) {
                int x = (2 * col + 1) * image.getWidth() / 24;
                int pixel = image.getPixel(x, y);
                red += (pixel >>> 16) & 255;
                green += (pixel >>> 8) & 255;
                blue += pixel & 255;
            }
        }
        return ((red / 96) << 16) | ((green / 96) << 8) | (blue / 96);
    }

    public Presentation presentation() {
        return presentation;
    }

    private final AtomicReference<Pending> incoming = new AtomicReference<>();
    private final Identifier name =
            Identifier.fromNamespaceAndPath("wdcdp", "page/" + NAMES.incrementAndGet());

    private final AtomicLong uploadFailures = new AtomicLong();
    private volatile String lastUploadError = "";

    /** Highest sequence number already on the GPU; older frames are dropped. */
    private volatile long shown;
    private volatile boolean everUploaded;
    private volatile boolean disposed;

    private final AtomicLong textureRebuilds = new AtomicLong();

    /** How many times the GPU texture was reallocated; a high number is a stutter. */
    public long rebuildCount() {
        return textureRebuilds.get();
    }

    /** True once {@code TextureManager.register} has run for {@link #name}. */
    private volatile boolean registered;

    private int width;
    private int height;

    /**
     * Register immediately, so the identifier is resolvable before the first
     * frame. Render thread only.
     *
     * <p>The guard is not paranoia: {@code createBrowser} is called from both the
     * render thread and the client tick, and registering from the tick would touch
     * the GPU off-thread. Failing loudly here is better than a driver crash with no
     * stack pointing at the cause.</p>
     */
    public void register() {
        if (registered || disposed) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            // Queue it rather than risk it; the pump will register on the next frame.
            CdpRenderQueue.register(this);
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().register(name, this);
            registered = true;
        } catch (RuntimeException e) {
            CdpLog.warn("Could not register the page texture", e);
        }
    }

    /**
     * Offer a decoded frame.
     *
     * <p>Called from a decoder thread. Takes ownership of {@code frame} in every
     * case: uploaded, superseded, or dropped.</p>
     */
    public void offer(NativeImage frame, long sequence, boolean fullscreen, int layoutWidth, int layoutHeight) {
        if (disposed || sequence <= shown) {
            frame.close();
            return;
        }
        Pending next = new Pending(frame, sequence, fullscreen, layoutWidth, layoutHeight);
        while (true) {
            Pending current = incoming.get();
            if (current != null && current.sequence() >= sequence) {
                frame.close();
                return;
            }
            if (incoming.compareAndSet(current, next)) {
                if (current != null) {
                    current.image().close();
                }
                break;
            }
        }
        if (disposed) {
            dropPending();
        }
    }

    /**
     * Upload the newest frame, if any. Render thread only.
     *
     * @return true once the texture is usable for drawing
     */
    public boolean sync() {
        // Re-entrancy guard.
        //
        // Minecraft's RenderType factories memoise through
        // ConcurrentHashMap.computeIfAbsent, and entityCutout(id) calls
        // entityCutout(id, true) - which re-enters the SAME cache node. That is a
        // deadlock in Minecraft itself, and it fires whenever something creates a
        // RenderType while that node is already being computed. A texture upload
        // that indirectly touches the render-type cache (a shader pack binding a
        // page texture during entity rendering does exactly this) can land in that
        // window.
        //
        // Skipping the frame is always safe: the next one is 16 ms away and the
        // pending slot holds the newest image. Skipping a whole deadlocked frame is
        // infinitely better than freezing the game.
        if (!uploading.compareAndSet(false, true)) {
            reentrantSkips.incrementAndGet();
            return isReady();
        }
        try {
            Pending frame = incoming.getAndSet(null);
            if (frame != null) {
                if (frame.sequence() <= shown) {
                    frame.image().close();
                } else {
                    shown = frame.sequence();
                    NativeImage image = frame.image();
                    try {
                        int sampledColor = averageGlow(image);
                        upload(image);
                        glowColor = sampledColor;
                        Presentation previous = presentation;
                        if (previous == null || previous.width() != width || previous.height() != height
                                || previous.fullscreen() != frame.fullscreen()
                                || previous.layoutWidth() != frame.layoutWidth()
                                || previous.layoutHeight() != frame.layoutHeight()) {
                            presentation = new Presentation(width, height, frame.fullscreen(),
                                    frame.layoutWidth(), frame.layoutHeight());
                        }
                        everUploaded = true;
                    } catch (RuntimeException e) {
                        uploadFailures.incrementAndGet();
                        lastUploadError = String.valueOf(e);
                        CdpLog.warn("Could not upload a page frame", e);
                    } finally {
                        // The pixels are in the GPU texture now; this NativeImage is
                        // ours and nothing else references it.
                        image.close();
                    }
                }
            }
            return isReady();
        } finally {
            uploading.set(false);
        }
    }

    /** Guards against a re-entrant sync, which would deadlock in RenderTypes. */
    private final AtomicBoolean uploading = new AtomicBoolean();

    private final AtomicLong reentrantSkips = new AtomicLong();

    /** Frames skipped because an upload was already in progress. */
    public long reentrantSkipCount() {
        return reentrantSkips.get();
    }

    private void upload(NativeImage frame) {
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();

        // A size change needs a new GPU texture: the view cannot be resized and a
        // mismatched write is rejected. This is also why an oscillating frame size
        // is catastrophic - every frame would allocate and free a GPU texture.
        if (texture != null && (frameWidth != width || frameHeight != height)) {
            releaseGpuTexture();
        }

        if (texture == null) {
            // GpuDevice takes depthOrLayers before mipLevels. The wall uses a
            // single 2D layer and uploads only the base mip level.
            GpuTexture created = RenderSystem.getDevice().createTexture(
                    () -> "WD CDP page",
                    TEXTURE_USAGE,
                    TextureFormat.RGBA8,
                    frameWidth,
                    frameHeight,
                    1,
                    1);
            this.texture = created;
            this.textureView = RenderSystem.getDevice().createTextureView(created);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            this.width = frameWidth;
            this.height = frameHeight;
            textureRebuilds.incrementAndGet();
        }

        // Direct GL upload, the same route the working implementation takes.
        //
        // The CommandEncoder path (writeToTexture) was measurably slower here: it
        // goes through the frame-graph machinery and allocates per call, which at
        // 30 fps per screen shows up as stutter. A plain glTexSubImage2D into the
        // texture's own id is the direct route and needs no encoder at all.
        //
        // The binding is restored afterwards because Minecraft caches the currently
        // bound texture in GlStateManager: leaving ours bound makes the next draw
        // that assumes the cache is correct sample the wrong texture. The same
        // applies to the unpack state, which the next upload elsewhere expects to be
        // the default.
        if (texture instanceof com.mojang.blaze3d.opengl.GlTexture glTexture) {
            int previous = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            int glId = glTexture.glId();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
            try {
                // Tightly packed rows: the image is exactly width*4 bytes per row.
                org.lwjgl.opengl.GL11.glPixelStorei(
                        org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 4);
                org.lwjgl.opengl.GL11.glPixelStorei(
                        org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
                org.lwjgl.opengl.GL11.glPixelStorei(
                        org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
                org.lwjgl.opengl.GL11.glPixelStorei(
                        org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
                org.lwjgl.opengl.GL12.glTexSubImage2D(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                        0, 0, 0,
                        frameWidth, frameHeight,
                        org.lwjgl.opengl.GL11.GL_RGBA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                        frame.getPointer());
            } finally {
                org.lwjgl.opengl.GL11.glBindTexture(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previous);
            }
            return;
        }

        // Fallback for a non-GL backend (there is none on the client today, but a
        // silent no-op would be worse than a slow path).
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, frame);
    }

    private void releaseGpuTexture() {
        GpuTextureView view = textureView;
        textureView = null;
        if (view != null) {
            view.close();
        }
        GpuTexture owned = texture;
        texture = null;
        if (owned != null) {
            owned.close();
        }
        width = 0;
        height = 0;
        presentation = null;
    }

    public void discardPending() {
        dropPending();
    }

    private void dropPending() {
        Pending stranded = incoming.getAndSet(null);
        if (stranded != null) {
            stranded.image().close();
        }
    }

    /** The identifier WebDisplays draws with; stable for this page's lifetime. */
    public Identifier name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * True when the page can be drawn.
     *
     * <p>Same predicate the working implementation uses: a live GPU texture, a
     * registered identifier, and a live view. It deliberately does <b>not</b>
     * require {@code everUploaded}: a texture with no frames yet still binds, and
     * reporting "not ready" for it makes WebDisplays skip the whole page.</p>
     */
    public boolean isReady() {
        GpuTexture owned = texture;
        GpuTextureView view = textureView;
        return owned != null && !owned.isClosed()
                && registered
                && view != null && !view.isClosed();
    }

    public long uploadFailureCount() {
        return uploadFailures.get();
    }

    public String lastUploadError() {
        return lastUploadError;
    }

    /** True when a decoded frame is waiting for the render thread. */
    public boolean hasPending() {
        return incoming.get() != null;
    }

    public boolean hasEverUploaded() {
        return everUploaded;
    }

    /**
     * Release the GPU texture and the identifier. Render thread only.
     *
     * <p>Callers must go through {@code CdpRenderQueue} when they are not on the
     * render thread; breaking a screen runs on the server thread and would
     * otherwise free a GPU texture from the wrong thread.</p>
     */
    @Override
    public void close() {
        if (disposed) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            CdpRenderQueue.release(this);
            return;
        }
        disposed = true;
        dropPending();
        releaseGpuTexture();
        if (registered) {
            registered = false;
            try {
                Minecraft.getInstance().getTextureManager().release(name);
            } catch (RuntimeException e) {
                // Already gone; nothing to release.
            }
        }
    }
}
