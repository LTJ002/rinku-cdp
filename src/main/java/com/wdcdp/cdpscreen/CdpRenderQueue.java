package com.wdcdp.cdpscreen;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.wdcdp.cdpscreen.render.PageTexture;

/**
 * Work that must run on Minecraft's render thread.
 *
 * <p>GPU objects - textures in particular - can only be created, uploaded and
 * released on the thread that owns the GL context. Everything else in this mod
 * runs on decoder threads, the WebSocket thread, or Chrome's process, so
 * teardown requests arrive from the wrong thread and have to be handed over.</p>
 *
 * <p>Drained once per client tick. A frame's worth of latency on a texture
 * release is irrelevant, and deferring it is much safer than trying to make the
 * release thread-safe.</p>
 */
public final class CdpRenderQueue {

    private static final Queue<PageTexture> RELEASES = new ConcurrentLinkedQueue<>();
    private static final Queue<PageTexture> REGISTRATIONS = new ConcurrentLinkedQueue<>();

    private CdpRenderQueue() {
    }

    /** Ask for a texture to be released on the render thread. */
    public static void release(PageTexture texture) {
        if (texture != null) {
            RELEASES.add(texture);
        }
    }

    /** Ask for a texture to be registered on the render thread. */
    public static void register(PageTexture texture) {
        if (texture != null) {
            REGISTRATIONS.add(texture);
        }
    }

    /** Render thread: release and register everything queued. */
    public static void drain() {
        PageTexture toRegister;
        while ((toRegister = REGISTRATIONS.poll()) != null) {
            try {
                toRegister.register();
            } catch (RuntimeException e) {
                CdpLog.warn("Could not register a page texture", e);
            }
        }
        PageTexture texture;
        while ((texture = RELEASES.poll()) != null) {
            try {
                texture.close();
            } catch (RuntimeException e) {
                CdpLog.warn("Could not release a page texture", e);
            }
        }
    }

    /** Number of pending operations; used by the leak check. */
    public static int pending() {
        return RELEASES.size() + REGISTRATIONS.size();
    }
}
