package com.wdcdp.cdpscreen.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * JPEG/PNG bytes to a {@link NativeImage}, without AWT.
 *
 * <p>Three things make this the hot path and shape the implementation:</p>
 * <ul>
 *   <li>A 1920x1080 frame arrives at ~20 Hz per screen, so a decode that
 *       allocates a {@code BufferedImage} and then copies pixel by pixel into the
 *       native image costs more than the network transfer.</li>
 *   <li>{@code NativeImage} already bundles STB, so the bytes can be decoded
 *       straight into an image we own. STB writes <b>RGBA in memory order</b>,
 *       which is exactly what {@code NativeImage.Format.RGBA} describes, so no
 *       channel swap is needed.</li>
 *   <li>Minecraft runs on Java 25 with AWT available but uninitialised in a
 *       headless game process; touching ImageIO would drag in the whole
 *       font/colour-management stack for nothing.</li>
 * </ul>
 *
 * <p>Must not be called on the render thread - see {@code CdpBrowser}'s decoder
 * pool. The result is handed to the render thread through an
 * {@code AtomicReference} and only ever touched there.</p>
 */
public final class FrameDecoder {

    /** STB's "keep the source's channel count" request value. */
    private static final int DESIRED_CHANNELS_RGBA = 4;

    private FrameDecoder() {
    }

    /**
     * Decode base64 JPEG/PNG into a new {@code NativeImage}.
     *
     * <p>The caller owns the returned image and must {@code close()} it after
     * uploading, or hand it to {@code DynamicTexture.setPixels}, which takes
     * ownership.</p>
     *
     * @param base64 the {@code data} field of a {@code Page.screencastFrame}
     */
    public static NativeImage decodeBase64(String base64) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IOException("frame payload was not valid base64", e);
        }
        return decode(bytes);
    }

    /**
     * Per-thread scratch, so a steady 30 fps does not allocate per frame.
     *
     * <p>Each frame used to allocate a direct {@code ByteBuffer} (a native
     * allocation, freed by a Cleaner) plus three int arrays. At 30 fps per screen
     * that is steady native and heap churn for buffers whose size barely changes,
     * which shows up as GC pauses in the game. The buffers are grown, never
     * shrunk, so after the first few frames there is no allocation at all.</p>
     */
    private static final class Scratch {
        ByteBuffer source;
        final int[] width = new int[1];
        final int[] height = new int[1];
        final int[] channels = new int[1];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Decode JPEG/PNG bytes into a new {@code NativeImage}. */
    public static NativeImage decode(byte[] data) throws IOException {
        if (data.length == 0) {
            throw new IOException("empty frame");
        }

        Scratch scratch = SCRATCH.get();
        // STB needs a direct buffer. Grow it to fit, then reuse it: a direct
        // allocation is a native malloc and has to be reclaimed by a Cleaner, so
        // doing it per frame is exactly the kind of churn that causes hitches.
        if (scratch.source == null || scratch.source.capacity() < data.length) {
            scratch.source = ByteBuffer.allocateDirect(
                    Math.max(data.length, 64 * 1024));
        }
        ByteBuffer source = scratch.source;
        source.clear();
        source.put(data);
        source.flip();

        int[] width = scratch.width;
        int[] height = scratch.height;
        int[] channels = scratch.channels;

        ByteBuffer pixels;
        try {
            pixels = org.lwjgl.stb.STBImage.stbi_load_from_memory(
                    source, width, height, channels, DESIRED_CHANNELS_RGBA);
        } catch (RuntimeException e) {
            throw new IOException("STB could not read the frame", e);
        }
        if (pixels == null) {
            throw new IOException("STB rejected the frame: "
                    + org.lwjgl.stb.STBImage.stbi_failure_reason());
        }

        try {
            int w = width[0];
            int h = height[0];
            if (w <= 0 || h <= 0) {
                throw new IOException("frame reported a size of " + w + "x" + h);
            }

            NativeImage image = new NativeImage(w, h, false);
            try {
                // STB with a forced 4-channel request writes tightly packed RGBA,
                // which matches NativeImage.Format.RGBA byte for byte, so this is
                // a straight block copy into the image's own native memory.
                long sourceAddress = org.lwjgl.system.MemoryUtil.memAddress(pixels);
                long targetAddress = image.getPointer();
                org.lwjgl.system.MemoryUtil.memCopy(
                        sourceAddress, targetAddress, (long) w * h * 4L);
                return image;
            } catch (RuntimeException e) {
                image.close();
                throw new IOException("could not fill the frame image", e);
            }
        } finally {
            org.lwjgl.stb.STBImage.stbi_image_free(pixels);
        }
    }
}
