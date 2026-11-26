package com.wdcdp.cdpscreen.cdp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.wdcdp.cdpscreen.CdpLog;
import com.wdcdp.cdpscreen.render.FrameDecoder;
import com.wdcdp.cdpscreen.render.PageTexture;

/**
 * One page: a CDP target, its flattened session, and the screencast driving it.
 *
 * <p>Everything a screen needs at runtime hangs off this object, and it is what
 * {@code Rinku.createBrowser} returns. It is engine-only - it never touches a
 * {@code DynamicTexture} directly, only {@link PageTexture#offer}, so the render
 * thread boundary stays in one place.</p>
 *
 * <h2>Backpressure</h2>
 * <p>Chrome's screencast is change-driven. Acknowledge every frame with a valid
 * token immediately, even if we discard its payload, so Chromium cannot run out
 * of capture credits. Only admitted frames reach the bounded decoder pool; local
 * admission does not reduce Chrome's encode work.</p>
 */
public final class CdpPage implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("wdcdp.page");

    /**
     * Default maximum decode admissions per page per second.
     *
     * <p>30 is the honest default: most pages, and most video sources they play,
     * update at that rate. Asking for 60 by default makes Chrome encode and ship
     * duplicate frames, which costs the machine without changing what anyone sees.
     * {@link #adaptRate} raises the ceiling when a page is measurably delivering
     * more than this, so genuinely 60 fps content still gets it.</p>
     */
    private static final int TARGET_FPS = 30;

    /**
     * Ceiling the adaptive controller may raise a page to.
     *
     * <p>60 Hz content is common on video sites, and a wall showing a 60 fps clip
     * at 30 is plainly less smooth than one showing it at 60. The controller only
     * goes there when the page demonstrably delivers that many frames.</p>
     */
    private static final int MAX_ADAPTIVE_FPS = 60;

    /**
     * Encode every Nth composited frame.
     *
     * <p><b>Retained for reference only.</b> Skipping at the encoder is a ceiling
     * Chrome enforces before anything reaches this mod, and the adaptive controller
     * measures what arrived - so a skip factor of 2 made 60 fps content look like
     * 30 fps forever, because the rate could never measure high enough to raise the
     * cap. Decode admission is the correct place to spend that budget: it can give
     * the allowance back when the machine keeps up.</p>
     */
    private static final int EVERY_NTH_FRAME = 1;

    /**
     * Skip factor for a frame of this many pixels.
     *
     * <p>Fullscreen video repaints the whole surface on every frame, so the encode
     * cost is proportional to area while the wall only ever shows one size. A large
     * page therefore needs a coarser skip than a small one, or the encoder becomes
     * the bottleneck and playback looks uneven exactly when a video is being
     * watched at its largest.</p>
     */
    private static int skipForPixels(int pixels) {
        if (pixels > 1_900_000) return 3;
        if (pixels > 1_500_000) return 2;
        return EVERY_NTH_FRAME;
    }

    /**
     * Cap the decoded frame size.
     *
     * <p>Measured on this machine: a 640x640 JPEG costs Chrome ~100 ms to encode,
     * and encoding is the dominant per-frame cost - far more than the ~2 ms STB
     * decode. A wall configured at 640x640 for a surface a few hundred pixels
     * across therefore pays for pixels nobody can see.</p>
     *
     * <p>1280 is the ceiling: above it the encode cost grows faster than the
     * visible benefit, and the design document's own measurements put 1080p at
     * 19 fps. Below that, the wall's configured size wins - it is usually already
     * modest.</p>
     */
    public static final int MAX_STREAM_DIMENSION = 1920;

    /** Keep-alive budget while a page is not streaming. */
    private static final long LOADING_PATIENCE_NANOS = 20_000_000_000L;

    private final CdpClient cdp;
    private final String targetId;
    private final String sessionId;
    private final PageTexture texture;
    private final Executor decoder;
    private final PageMedia media;

    private final Object admissionLock = new Object();
    private long nextAdmissionNanos;
    private boolean admissionScheduled;

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong ackCount = new AtomicLong();
    private final AtomicLong decodeFailures = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicReference<String> lastDecodeError = new AtomicReference<>("");

    private final AtomicReference<Runnable> onFrame = new AtomicReference<>();
    private record EncodedFrame(Cast cast, byte[] payload, String base64, long sequence) {}
    private final AtomicReference<EncodedFrame> latestEncoded = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean decodeScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile String url = "";
    private volatile String title = "";
    private volatile boolean loading;
    private volatile long loadingSinceNanos;
    private volatile boolean streaming;
    private volatile boolean closed;

    private volatile int frameWidth;
    private volatile int frameHeight;
    /** When the last screencast frame arrived; used to detect a stalled stream. */
    private volatile long lastFrameNanos;

    public record Viewport(int width, int height, int captureWidth, int captureHeight,
                           boolean fullscreen) {}

    public Viewport currentViewport() {
        return appliedViewport;
    }

    public boolean acceptsPointer(Viewport viewport) {
        Viewport requested = requestedViewport;
        if (fullscreenGeometryPending || (viewport != null && requested != null
                && (viewport.fullscreen() || requested.fullscreen())
                && !sameLayout(viewport, requested))) return false;
        var shown = texture.presentation();
        return viewport == null || shown == null || (shown.fullscreen() == viewport.fullscreen()
                && (!viewport.fullscreen() || (shown.layoutWidth() == viewport.width()
                && shown.layoutHeight() == viewport.height())));
    }

    private static boolean sameLayout(Viewport first, Viewport second) {
        return first.fullscreen() == second.fullscreen()
                && first.width() == second.width() && first.height() == second.height();
    }
    private record Cast(Viewport viewport, long settleUntil, int frameStep) {}
    private Viewport normalViewport;
    /** Long edge requested by automatic wall sizing; zero preserves the manual pixel budget. */
    private int fullscreenCaptureEdge;
    private volatile Viewport requestedViewport;
    private volatile Viewport appliedViewport;
    private volatile Cast capture;
    private Cast mostRecentFrameCast;
    private long captureSettleUntil;
    private static final int PAGE_JPEG_QUALITY = 74;
    private static final int FULLSCREEN_JPEG_QUALITY = 92;
    /** Zero uses the default for the current presentation mode. */
    private volatile int qualityOverride;
    private volatile int fpsOverride;
    private volatile int maxFps = MAX_ADAPTIVE_FPS;
    private static final int LARGE_CAPTURE_PIXELS = 1280 * 720;
    private final AtomicLong decodedFrames = new AtomicLong();
    private boolean limitFastVideo;
    private int slowerVideoSamples;
    private long lastVideoSampleNanos;
    private long lastVideoDecodeCount;

    private int captureRate(Viewport viewport) {
        int explicit = fpsOverride;
        if (explicit > 0) return explicit;
        return viewport.fullscreen() ? MAX_ADAPTIVE_FPS : maxFps;
    }

    private volatile String mainFrameId;
    private volatile int cursorType;

    private CdpPage(CdpClient cdp, String targetId, String sessionId, PageTexture texture,
                    Executor decoder) {
        this.cdp = cdp;
        this.targetId = targetId;
        this.sessionId = sessionId;
        this.texture = texture;
        this.decoder = decoder;
        this.media = new PageMedia(cdp, sessionId, this::onFullscreenChanged, this::onVideoCadence);
    }

    /**
     * Create a page and attach a flattened session to it.
     *
     * <p>Creation is deliberately split from navigation: WebDisplays always asks
     * for {@code about:blank} first and then calls {@code loadURL}, and a page
     * that navigates twice on creation would reload the real URL once for
     * nothing.</p>
     *
     * <p><b>Every page gets its own window.</b> This is not cosmetic - it is what
     * makes more than one screen work. Chrome composites, and therefore screencasts,
     * only the <i>active tab of a window</i>: a background tab reports
     * {@code document.visibilityState === "hidden"}, its compositor stops
     * producing frames, and {@code Page.startScreencast} then emits nothing at all
     * while the page's audio keeps playing normally. With plain
     * {@code Target.createTarget} every screen became another tab of one window, so
     * exactly one wall showed a picture and the rest stayed black with sound - the
     * reported bug.</p>
     *
     * <p>Measured on this machine, three tabs loading the same playing video:</p>
     * <ul>
     *   <li>{@code newWindow:false} - frames per tab: 112, <b>0</b>, 96; only the
     *       active tab was {@code visible}.</li>
     *   <li>{@code newWindow:true} - frames per tab: 97, 95, 92, all three
     *       {@code visible}.</li>
     * </ul>
     *
     * <p>A hidden tab can even <i>start</i> streaming - Chrome emits a frame or two
     * while the tab is still being made visible - which is why a second screen used
     * to show a frozen first frame rather than nothing.</p>
     */
    public static CompletableFuture<CdpPage> create(CdpClient cdp, PageTexture texture,
                                                    Executor decoder, String initialUrl) {
        JsonObject params = CdpJson.obj("url",
                initialUrl == null || initialUrl.isBlank() ? "about:blank" : initialUrl);
        // One window per page: the page must be the active tab to be composited.
        params.addProperty("newWindow", true);
        return cdp.call(null, "Target.createTarget", params)
                .thenCompose(result -> {
                    String targetId = CdpJson.string(result, "targetId", "");
                    if (targetId.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new CdpException("Target.createTarget returned no targetId"));
                    }
                    // Registered before attaching: the browser-level creation event
                    // races this future, and an unregistered target looks stray.
                    com.wdcdp.cdpscreen.CdpBackend.notifyCreating(targetId);
                    return cdp.call(null, "Target.attachToTarget",
                                    CdpJson.obj("targetId", targetId, "flatten", true))
                            .thenApply(attach -> {
                                String sessionId = CdpJson.string(attach, "sessionId", "");
                                if (sessionId.isEmpty()) {
                                    throw new CdpException(
                                            "Target.attachToTarget returned no sessionId");
                                }
                                CdpPage page = new CdpPage(cdp, targetId, sessionId, texture, decoder);
                                page.enableDomains();
                                return page;
                            })
                            .exceptionallyCompose(error -> {
                                // The target was created but never adopted: without
                                // this it stays open in Chrome forever, holding a
                                // renderer process and a page of memory. A failed
                                // attach is rare, but "rare" plus "leaks a process
                                // per attempt" is how a retry loop exhausts a machine.
                                com.wdcdp.cdpscreen.CdpBackend.forgetCreating(targetId);
                                cdp.fire(null, "Target.closeTarget",
                                        CdpJson.obj("targetId", targetId));
                                return CompletableFuture.failedFuture(error);
                            });
                });
    }

    private void enableDomains() {
        cdp.fire(sessionId, "Page.enable", new JsonObject());
        cdp.fire(sessionId, "Runtime.enable", new JsonObject());
        // Auto-accept alert/confirm/prompt. A modal dialog in a headless browser
        // blocks the renderer and the screen simply freezes with no clue why.
        cdp.fire(sessionId, "Page.setInterceptFileChooserDialog",
                CdpJson.obj("enabled", false));
    }

    // --------------------------------------------------------------- identity

    public String targetId() {
        return targetId;
    }

    public String sessionId() {
        return sessionId;
    }

    public PageTexture texture() {
        return texture;
    }

    public String url() {
        String current = url;
        return current == null ? "" : current;
    }

    public String title() {
        String current = title;
        return current == null || current.isBlank() ? url() : current;
    }

    public boolean isLoading() {
        return loading && System.nanoTime() - loadingSinceNanos < LOADING_PATIENCE_NANOS;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The page's current CEF cursor id. */
    public int cursorType() {
        return cursorType;
    }

    /** Invoked (on a decoder thread) after every frame reaches the texture queue. */
    public void setFrameListener(Runnable listener) {
        onFrame.set(listener);
    }

    public long framesReceived() {
        return frameCount.get();
    }

    /** Frames acked back to Chrome; a frame that is never acked stops the stream. */
    public long framesAcked() {
        return ackCount.get();
    }

    public long decodeFailureCount() {
        return decodeFailures.get();
    }

    public String lastDecodeError() {
        return lastDecodeError.get();
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    // ----------------------------------------------------------------- events

    /** Route one CDP event for this page. Called from the WebSocket thread. */
    public void handleEvent(String method, JsonObject params) {
        handleEvent(method, params, null);
    }

    /** Route an event, optionally with already decoded compressed frame bytes. */
    public void handleEvent(String method, JsonObject params, byte[] payload) {
        if (closed) return;
        switch (method) {
            case CdpClient.FRAME_EVENT, "Page.screencastFrameBytes" -> onScreencastFrame(params, payload);
            case "Page.frameStartedLoading" -> {
                if (isMainFrame(params)) {
                    if (!loading && !audioNavigating) beginAudioNavigation();
                    setLoading(true);
                }
            }
            case "Page.frameStoppedLoading" -> {
                if (isMainFrame(params)) {
                    endAudioNavigation();
                    setLoading(false);
                }
            }
            case "Page.loadEventFired" -> {
                setLoading(false);
                // A new document starts without the fill styles, so re-apply.
                fitVideo();
                // The screencast survives most navigations, but a load that swaps
                // the renderer (or a cross-process navigation) can stop producing
                // frames while the capture still believes it is running. Nothing
                // restarted it, so a screen that had just navigated sat on its last
                // frame until something else re-armed the stream - which is what
                // "jumping pages freezes" looked like.
                restartScreencastAfterNavigation();
            }
            case "Page.frameNavigated" -> {
                JsonObject frame = CdpJson.child(params, "frame");
                if (!frame.has("parentId")) {
                    resetVideoSamples();
                    mainFrameId = CdpJson.string(frame, "id", mainFrameId);
                    endAudioNavigation();
                    String next = CdpJson.string(frame, "url", null);
                    if (next != null && !next.isBlank()) {
                        url = next;
                    }
                }
            }
            case "Page.navigatedWithinDocument" -> {
                if (!isMainFrame(params)) break;
                endAudioNavigation();
                String next = CdpJson.string(params, "url", null);
                if (next != null && !next.isBlank()) {
                    url = next;
                }
                setLoading(false);
                // Single-page sites (Bilibili, YouTube) change route without ever
                // firing loadEventFired, so a navigation that only swaps the view
                // would never re-apply the player styling or re-check the stream.
                // Re-running the same steps here is what makes an in-page jump feel
                // the same as a full load.
                fitVideo();
                restartScreencastAfterNavigation();
            }
            case "Page.windowOpen" -> com.wdcdp.cdpscreen.CdpBackend.get().pollForPopup(this);
            case "Page.javascriptDialogOpening" -> {
                // Accepting is better than freezing; a page that opened a dialog is
                // otherwise stuck forever because nobody can click it.
                cdp.fire(sessionId, "Page.handleJavaScriptDialog", CdpJson.obj("accept", true));
            }
            case "Runtime.bindingCalled" -> onBindingCalled(params);
            case "Runtime.executionContextDestroyed" ->
                    media.contextDestroyed(CdpJson.integer(params, "executionContextId", -1));
            case "Runtime.executionContextsCleared" -> {
                media.contextsCleared();
                invalidateAudio();
                resetFullscreenForNavigation();
            }
            default -> {
                // Other events are not needed by a display surface.
            }
        }
    }

    private boolean isMainFrame(JsonObject params) {
        String frameId = CdpJson.string(params, "frameId", "");
        String main = mainFrameId;
        return main == null || main.isEmpty() || main.equals(frameId);
    }

    private void setLoading(boolean value) {
        if (value && !loading) {
            loadingSinceNanos = System.nanoTime();
        }
        loading = value;
    }

    // ----------------------------------------------------------------- frames

    private void onScreencastFrame(JsonObject params, byte[] payload) {
        if (closed) {
            return;
        }

        frameCount.incrementAndGet();
        // Recorded before any admission or decode decision: this answers "is the
        // capture still producing frames", which is exactly what the post-navigation
        // check needs, and a frame dropped for pacing is still proof of life.
        lastFrameNanos = System.nanoTime();
        // Return the capture credit even for late frames or malformed image data.
        fireAck(CdpJson.integer(params, "sessionId", -1));
        Cast cast = capture;
        if (cast == null || !admitFrame(cast.viewport())) return;

        String base64 = payload == null ? CdpJson.string(params, "data", "") : null;
        if (base64 != null && base64.isEmpty()) return;
        var frame = new EncodedFrame(cast, payload, base64, sequence.incrementAndGet());
        if (latestEncoded.getAndSet(frame) != null) droppedFrames.incrementAndGet();
        scheduleDecode();
    }

    private void scheduleDecode() {
        if (closed || !decodeScheduled.compareAndSet(false, true)) return;
        try {
            decoder.execute(this::decodeLatest);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            latestEncoded.set(null);
            decodeScheduled.set(false);
        }
    }

    private void decodeLatest() {
        try {
            EncodedFrame frame = latestEncoded.getAndSet(null);
            if (closed || frame == null || capture != frame.cast()) return;
            decodeFrame(frame);
        } finally {
            decodeScheduled.set(false);
            if (latestEncoded.get() != null) scheduleDecode();
        }
    }

    private void decodeFrame(EncodedFrame frame) {
        Cast cast = frame.cast();
        try {
            NativeImage image = frame.payload() != null ? FrameDecoder.decode(frame.payload())
                    : FrameDecoder.decodeBase64(frame.base64());
            synchronized (this) {
                if (closed || capture != cast) {
                    image.close();
                    return;
                }
                Viewport view = cast.viewport();
                int w = image.getWidth(), h = image.getHeight();
                // Chrome can finish encoding an old surface after the new
                // stream starts. Filter it only during the bounded handoff.
                if (cast.settleUntil() != 0 && cast.settleUntil() - System.nanoTime() > 0
                        && (Math.abs(w - view.captureWidth()) > 3 || Math.abs(h - view.captureHeight()) > 3)) {
                    image.close();
                    return;
                }
                if (mostRecentFrameCast != cast && cast.settleUntil() != 0) {
                    CdpLog.info("Fullscreen transition frame " + w + "x" + h + ", expected "
                            + view.captureWidth() + "x" + view.captureHeight());
                }
                mostRecentFrameCast = cast;
                frameWidth = w;
                frameHeight = h;
                texture.offer(image, frame.sequence(), view.fullscreen(), view.width(), view.height());
            }
            decodedFrames.incrementAndGet();
            Runnable listener = onFrame.get();
            if (listener != null) listener.run();
        } catch (Exception e) {
            // Keep a bad frame visible in the counters without spamming the log.
            decodeFailures.incrementAndGet();
            lastDecodeError.set(String.valueOf(e));
            LOG.log(Level.FINE, "Could not decode a page frame", e);
        }
    }

    /** Admit at the local decode rate, tolerating small arrival-time jitter. */
    private boolean admitFrame(Viewport viewport) {
        int rate = captureRate(viewport);
        // When the ceiling is high, admit everything and let the bounded decoder
        // queue be the only throttle. Pacing arrival at the same rate the encoder
        // already produces guarantees a lossy boundary: two independently paced
        // 60 Hz streams never line up, so roughly every other frame fell just outside
        // the window and was counted as a drop even though decode had caught up.
        if (rate >= TARGET_FPS * 2 || (viewport.fullscreen() && (rate == TARGET_FPS || rate == MAX_ADAPTIVE_FPS))) {
            return true;
        }
        long interval = 1_000_000_000L / rate;
        synchronized (admissionLock) {
            long now = System.nanoTime();
            long lateBy = now - nextAdmissionNanos;
            long jitter = Math.min(2_000_000L, interval / 10);
            if (admissionScheduled && lateBy < -jitter) {
                droppedFrames.incrementAndGet();
                return false;
            }
            // Pace from the prior deadline so tardy frames do not accumulate drift.
            // Start a new schedule after an idle period instead of allowing catch-up.
            nextAdmissionNanos = !admissionScheduled || lateBy >= interval
                    ? now + interval : nextAdmissionNanos + interval;
            admissionScheduled = true;
            return true;
        }
    }

    /** Frames acked but not decoded, because they arrived too fast. */
    public long framesDropped() {
        return droppedFrames.get();
    }

    private void fireAck(int ackToken) {
        if (ackToken >= 0 && !closed) {
            ackCount.incrementAndGet();
            cdp.fire(sessionId, "Page.screencastFrameAck",
                    CdpJson.obj("sessionId", ackToken));
        }
    }

    private static final int FULLSCREEN_BOUNDS_ATTEMPTS = 7;
    private record WindowBounds(String state, int width, int height) {}
    private long fullscreenRevision;
    private boolean nativeFullscreen;
    /** Last verified native fullscreen bounds, available only while fullscreen is active. */
    private WindowBounds fullscreenBounds;
    private volatile boolean fullscreenGeometryPending;

    private synchronized void resetFullscreenForNavigation() {
        if (nativeFullscreen) onFullscreenChanged(false);
    }

    private synchronized void onFullscreenChanged(boolean active) {
        if (closed || active == nativeFullscreen || normalViewport == null) return;
        nativeFullscreen = active;
        fullscreenBounds = null;
        fullscreenGeometryPending = true;
        long revision = ++fullscreenRevision;
        updateScreencast();
        // fullscreenchange can arrive before the native window has resized. Read
        // the actual bounds (not screen.width, which CDP overrides), then align
        // the layout to that surface rather than trying to enlarge it in place.
        checkFullscreenBounds(revision, active, FULLSCREEN_BOUNDS_ATTEMPTS, null);
    }

    private synchronized void checkFullscreenBounds(long revision, boolean active, int remaining,
                                                     WindowBounds previous) {
        if (closed || revision != fullscreenRevision) return;
        cdp.call(null, "Browser.getWindowForTarget", CdpJson.obj("targetId", targetId))
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> {
                    synchronized (this) {
                        if (closed || revision != fullscreenRevision) return;
                        JsonObject bounds = error == null ? CdpJson.child(result, "bounds") : new JsonObject();
                        String state = CdpJson.string(bounds, "windowState", "");
                        int w = CdpJson.integer(bounds, "width", 0);
                        int h = CdpJson.integer(bounds, "height", 0);
                        boolean valid = !state.isEmpty() && active == "fullscreen".equals(state)
                                && (!active || (w > 0 && h > 0 && w <= MAX_LAYOUT_DIMENSION && h <= MAX_LAYOUT_DIMENSION));
                        WindowBounds sample = valid ? new WindowBounds(state, w, h) : null;
                        if ((!valid || !sample.equals(previous)) && remaining > 1) {
                            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS)
                                    .execute(() -> checkFullscreenBounds(revision, active, remaining - 1, sample));
                            return;
                        }
                        fullscreenGeometryPending = false;
                        if (active && !valid) {
                            LOG.warning("Could not identify the native fullscreen bounds; keeping the current viewport");
                            updateScreencast();
                            return;
                        }
                        fullscreenBounds = active ? sample : null;
                        Viewport next = active ? fullscreenViewport(fullscreenBounds, normalViewport) : normalViewport;
                        CdpLog.info("Native fullscreen " + (active ? "entered" : "exited")
                                + "; window " + w + "x" + h + ", layout " + next.width() + "x" + next.height()
                                + ", capture " + next.captureWidth() + "x" + next.captureHeight());
                        // Even equal dimensions need reassertion after Chrome's
                        // native fullscreen window restores its old visible area.
                        requestViewport(next, true);
                    }
                });
    }

    private Viewport fullscreenViewport(WindowBounds bounds, Viewport normal) {
        // The wall draws the entire fullscreen texture, stretching it to the wall
        // shape. Rasterize at the virtual monitor's aspect to keep all 1080 lines
        // of a desktop video even when the wall is wider than the monitor.
        int width = bounds.width();
        int height = bounds.height();
        int longEdge = Math.max(width, height);
        double scale = Math.min(1.0, MAX_STREAM_DIMENSION / (double) longEdge);
        if (fullscreenCaptureEdge > 0) {
            scale = Math.min(scale, fullscreenCaptureEdge / (double) longEdge);
        } else {
            long budget = (long) normal.captureWidth() * normal.captureHeight();
            scale = Math.min(scale, Math.sqrt(budget / (double) ((long) width * height)));
        }
        return new Viewport(width, height,
                Math.max(1, (int) Math.floor(width * scale)),
                Math.max(1, (int) Math.floor(height * scale)), true);
    }

    // -------------------------------------------------------------- streaming

    /** Start or stop capture when the wall has content. */
    public synchronized void setStreaming(boolean value) {
        if (closed || streaming == value) return;
        streaming = value;
        if (!value) resetFullscreenForNavigation();
        audioEpoch.incrementAndGet();
        updateScreencast();
    }

    public CompletableFuture<Void> installMediaControl() {
        return media.install();
    }

    /**
     * Leave any fullscreen the page is showing.
     *
     * <p>Asked of the page rather than cached. A cache fed by a binding has three
     * failure modes that all end in the wrong answer: a subframe reports its own
     * state and overwrites the main frame's, a navigation leaves a value from the
     * previous document behind, and the player can enter fullscreen between the cache
     * being written and the key being handled.</p>
     *
     * <p>Two exits are attempted because the two cases differ. The injected
     * {@code __wdcdpExitFullscreen} runs whatever the media script set up; the native
     * {@code document.exitFullscreen} covers a player that entered real fullscreen
     * before that script was installed. Whichever ran, the answer is the page's own
     * report of whether fullscreen was actually active - so the caller's key is
     * consumed only when there was something to leave.</p>
     *
     * <p>No optimistic state is kept: a failed native exit must not make the next
     * Escape believe the wall is already out.</p>
     *
     * @return a future resolving to true when fullscreen was active and an exit was
     *         requested, meaning the key was consumed; never null
     */
    public CompletableFuture<Boolean> exitFullscreen() {
        if (closed) return CompletableFuture.completedFuture(Boolean.FALSE);
        String code = "(() => {"
                + " var active = !!document.fullscreenElement;"
                + " if (!active) return false;"
                + " try {"
                + "   if (window.__wdcdpExitFullscreen && window.__wdcdpExitFullscreen() === true) {"
                + "     return true; }"
                + " } catch (e) {}"
                + " try {"
                + "   if (document.exitFullscreen) {"
                + "     var p = document.exitFullscreen();"
                + "     if (p && typeof p.catch === 'function') p.catch(() => {});"
                + "     return true; }"
                + " } catch (e) {}"
                + " return false;"
                + "})()";
        return evaluateForValue(code)
                .thenApply(answer -> "true".equalsIgnoreCase(answer.trim()))
                .exceptionally(error -> false);
    }

    /** Run when the page's own playback pauses or seeks, so buffered PCM is dropped. */
    public void onPlaybackStopped(Runnable handler) {
        audioFlush = handler;
    }

    private volatile boolean mediaPaused;
    private volatile boolean audioNavigating;
    private volatile Runnable audioFlush;
    private final AtomicLong audioEpoch = new AtomicLong();
    private final AtomicLong navigationRevision = new AtomicLong();

    public void invalidateAudio() {
        // The audio worker checks the epoch before consuming a queued block.
        audioEpoch.incrementAndGet();
        Runnable flush = audioFlush;
        if (flush != null) flush.run();
    }

    private long beginAudioNavigation() {
        long revision = navigationRevision.incrementAndGet();
        audioNavigating = true;
        invalidateAudio();
        return revision;
    }

    private void endAudioNavigation() {
        audioNavigating = false;
    }

    private void recoverAudioNavigation(long revision) {
        if (navigationRevision.get() == revision) endAudioNavigation();
    }
    private long viewportRevision;
    private long appliedViewportRevision;

    public synchronized void setMediaPaused(boolean value) {
        if (closed || mediaPaused == value) return;
        mediaPaused = value;
        audioEpoch.incrementAndGet();
        media.setPaused(value);
        updateScreencast();
    }

    public long audioEpoch() {
        return audioEpoch.get();
    }

    public boolean acceptsAudio() {
        return !closed && streaming && !mediaPaused && !audioNavigating;
    }

    public boolean isStreaming() {
        return !closed && capture != null;
    }

    private void updateScreencast() {
        boolean wanted = !closed && streaming && !mediaPaused && !fullscreenGeometryPending
                && appliedViewportRevision == viewportRevision && appliedViewport != null;
        if (wanted == (capture != null)) return;
        if (wanted) {
            long guard = captureSettleUntil;
            if (guard != 0 && guard - System.nanoTime() <= 0) guard = 0;
            Cast cast = new Cast(appliedViewport, guard, captureFrameStep(appliedViewport));
            capture = cast;
            startScreencast(cast, true);
            if (guard != 0) {
                CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS)
                        .execute(() -> refreshTransitionCapture(cast));
            }
        } else {
            stopScreencast();
        }
    }

    private void stopScreencast() {
        if (capture != null) {
            capture = null;
            latestEncoded.set(null);
            texture.discardPending();
            cdp.fire(sessionId, "Page.stopScreencast", new JsonObject());
        }
    }

    /**
     * Resize the page viewport.
     *
     * <p>The screencast has to be restarted because {@code maxWidth}/{@code
     * maxHeight} are fixed at start time; without the restart Chrome keeps
     * encoding at the old size and the frame no longer matches the wall.</p>
     *
     * <p><b>The frame size must equal the viewport size.</b> That sounds obvious
     * and was the source of two visible bugs. {@code Page.startScreencast} treats
     * {@code maxWidth}/{@code maxHeight} as a <i>bounding box</i> and preserves
     * the page's aspect ratio inside it, so asking for a 640x640 box on a page
     * whose layout is 640x316 yields a 640x316 frame. WebDisplays then stretches
     * that frame across the wall using the ratio from the <i>configured</i>
     * resolution, and the picture is distorted. It also re-uploads - and so
     * rebuilds the GPU texture - every time the reported size disagrees with what
     * arrived, which is the stutter.</p>
     *
     * <p>The fix is to make the two agree: the device metrics override defines the
     * layout size, and the screencast box is set to exactly that size, so Chrome
     * has nothing to letterbox and the frame comes back at the requested
     * dimensions.</p>
     */
    /**
     * Resize the page.
     *
     * <p><b>Layout size and capture size are different things.</b> The page is laid
     * out at a desktop size so sites render their normal layout - at a 540 px wide
     * viewport a video site switches to its narrow mode and a login dialog covers
     * most of the screen. The capture is then bounded to a small box, so the frame
     * that reaches the GPU and the transfer stay cheap.</p>
     *
     * <p>{@code Emulation.setDeviceMetricsOverride} sets the layout;
     * {@code Page.startScreencast}'s {@code maxWidth}/{@code maxHeight} bound the
     * captured image, and Chrome scales the layout down into it. That separation is
     * what lets the page look like a browser while costing what a small frame
     * costs.</p>
     *
     * @param width  layout width in CSS pixels
     * @param height layout height in CSS pixels
     * @param captureWidth  width to capture at, or 0 to use the layout width
     * @param captureHeight height to capture at, or 0 to use the layout height
     */
    public void setViewport(int width, int height, int captureWidth, int captureHeight) {
        setViewport(width, height, captureWidth, captureHeight, 0);
    }

    public synchronized void setViewport(int width, int height, int captureWidth, int captureHeight,
                                         int automaticFullscreenEdge) {
        int w = Math.clamp(width, 1, MAX_LAYOUT_DIMENSION);
        int h = Math.clamp(height, 1, MAX_LAYOUT_DIMENSION);
        int cw = Math.clamp(captureWidth <= 0 ? w : captureWidth, 1, MAX_STREAM_DIMENSION);
        int ch = Math.clamp(captureHeight <= 0 ? h : captureHeight, 1, MAX_STREAM_DIMENSION);
        if (closed) return;
        fullscreenCaptureEdge = Math.clamp(automaticFullscreenEdge, 0, MAX_STREAM_DIMENSION);
        normalViewport = new Viewport(w, h, cw, ch, false);
        if (fullscreenGeometryPending) return;
        if (!nativeFullscreen) {
            requestViewport(normalViewport, false);
        } else if (fullscreenBounds != null) {
            Viewport next = fullscreenViewport(fullscreenBounds, normalViewport);
            if (!next.equals(requestedViewport)) {
                CdpLog.info("Native fullscreen resized; layout " + next.width() + "x" + next.height()
                        + ", capture " + next.captureWidth() + "x" + next.captureHeight());
                requestViewport(next, false);
            }
        }
    }

    /** Called under the page lock. */
    private void requestViewport(Viewport next, boolean force) {
        if (next == null || (!force && next.equals(requestedViewport))) return;
        requestedViewport = next;
        long revision = ++viewportRevision;
        updateScreencast();
        applyViewport(next, revision, force);
    }

    private synchronized void refreshTransitionCapture(Cast cast) {
        if (closed || capture != cast || mostRecentFrameCast == cast) return;
        captureSettleUntil = 0;
        stopScreencast();
        updateScreencast();
    }

    /**
     * Resize the page to a single size, used for both layout and capture.
     *
     * <p>Kept for callers that have no reason to separate them - the probes, and any
     * page that is not shown on a wall.</p>
     */
    public void setViewport(int width, int height) {
        setViewport(width, height, 0, 0);
    }

    /** Largest layout dimension; a desktop page, not a phone page. */
    private static final int MAX_LAYOUT_DIMENSION = 3840;

    /**
     * Apply the device metrics and restart the stream at that size.
     *
     * <p><b>This must wait for the metrics command to complete.</b> Firing
     * {@code Emulation.setDeviceMetricsOverride} and then immediately firing
     * {@code Page.startScreencast} races: Chrome accepts the screencast request
     * while the layout is still the old size, so the stream is configured for the
     * old aspect ratio and every frame comes back at the wrong dimensions. That
     * race is why the same build produced 640x640 on one launch and 640x316 on the
     * next.</p>
     */
    private void applyViewport(Viewport next, long revision, boolean retry) {
        cdp.call(sessionId, "Emulation.setDeviceMetricsOverride", CdpJson.obj(
                        "width", next.width(),
                        "height", next.height(),
                        "deviceScaleFactor", 1,
                        "screenWidth", next.width(),
                        "screenHeight", next.height(),
                        "mobile", false))
                .whenComplete((result, error) -> {
                    synchronized (this) {
                        if (closed || revision != viewportRevision) return;
                        if (error == null) {
                            Viewport previous = appliedViewport;
                            if (previous != null && (previous.fullscreen() || next.fullscreen())
                                    && !previous.equals(next)) {
                                captureSettleUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650);
                            }
                            appliedViewport = next;
                        } else if (retry) {
                            applyViewport(next, revision, false);
                            return;
                        } else {
                            // If Chrome rejects the change, keep using its last
                            // working geometry rather than leave capture stopped.
                            requestedViewport = appliedViewport;
                            LOG.log(Level.WARNING, "Could not apply the viewport", error);
                        }
                        appliedViewportRevision = revision;
                        updateScreencast();
                    }
                });
    }

    private int captureFrameStep(Viewport viewport) {
        boolean large = viewport.captureWidth() * viewport.captureHeight() >= LARGE_CAPTURE_PIXELS;
        return limitFastVideo && large && fpsOverride == 0 ? 2 : 1;
    }

    private void resetVideoSamples() {
        lastVideoSampleNanos = 0;
        slowerVideoSamples = 0;
    }

    private synchronized void onVideoCadence(int cadence) {
        if (closed || mediaPaused || fpsOverride > 0 || capture == null) return;
        long now = System.nanoTime();
        long count = decodedFrames.get();
        long elapsed = now - lastVideoSampleNanos;
        long frames = count - lastVideoDecodeCount;
        lastVideoSampleNanos = now;
        lastVideoDecodeCount = count;

        if (cadence <= 0) {
            slowerVideoSamples = 0;
            if (cadence == 0) {
                resetVideoSamples();
                limitFastVideoCapture(false);
            }
            return;
        }
        if (elapsed < 700_000_000L || elapsed > 2_500_000_000L) return;
        if (limitFastVideo) {
            // Keep the limit across brief changes in source cadence. Restore
            // full sampling for sustained 24/30fps video or a static page.
            slowerVideoSamples = cadence <= 38 ? slowerVideoSamples + 1 : 0;
            if (slowerVideoSamples >= 3) limitFastVideoCapture(false);
            return;
        }
        double delivered = frames * 1_000_000_000.0 / elapsed;
        if (cadence >= 45 && delivered < cadence * 0.85) limitFastVideoCapture(true);
    }

    private void limitFastVideoCapture(boolean active) {
        limitFastVideo = active;
        Cast current = capture;
        if (current == null || captureFrameStep(current.viewport()) == current.frameStep()) return;
        resetVideoSamples();
        CdpLog.info("Video capture cadence: every " + captureFrameStep(current.viewport())
                + " compositor frame at " + current.viewport().captureWidth()
                + "x" + current.viewport().captureHeight());
        stopScreencast();
        updateScreencast();
    }

    private void startScreencast(Cast cast, boolean retry) {
        int w = cast.viewport().captureWidth();
        int h = cast.viewport().captureHeight();
        int jpegQuality = qualityOverride;
        if (jpegQuality == 0) {
            jpegQuality = cast.viewport().fullscreen() ? FULLSCREEN_JPEG_QUALITY : PAGE_JPEG_QUALITY;
        }
        cdp.call(sessionId, "Page.startScreencast", CdpJson.obj(
                "format", "jpeg",
                "quality", jpegQuality,
                "maxWidth", w,
                "maxHeight", h,
                // Skip before JPEG encoding to leave time for the video and audio pipelines.
                "everyNthFrame", cast.frameStep()))
                .exceptionally(error -> {
                    synchronized (this) {
                        if (capture == cast && !closed) {
                            if (retry) {
                                cdp.fire(sessionId, "Page.stopScreencast", new JsonObject());
                                startScreencast(cast, false);
                            } else {
                                capture = null;
                                LOG.log(Level.WARNING, "Could not start the page screencast", error);
                            }
                        }
                    }
                    return null;
                });
    }

    /**
     * Make the page's video fit the viewport.
     *
     * <p>Measured: the player element on a video page is a fixed <b>668x376</b>
     * regardless of viewport width - 640, 1280 and 1920 all gave the same size.
     * So at a 640x360 viewport the video is wider than the page and gets cropped at
     * both edges, which reads as "the picture does not scale with the screen".</p>
     *
     * <p>The fix is to size the video to the viewport explicitly rather than hoping
     * the site's own responsive rules do it, and to hide the chrome around it
     * (header, comments, player controls) so what is left is the picture. Applied on
     * load and re-applied while the player initialises, because the element is
     * replaced during startup.</p>
     */
    public void fitVideo() {
        if (closed) {
            return;
        }
        cdp.fire(sessionId, "Runtime.evaluate", CdpJson.obj(
                "expression", FIT_VIDEO_JS,
                "returnByValue", false,
                "userGesture", false));
    }

    /**
     * Make the page usable on a wall: dismiss modal overlays and fill with video.
     *
     * <p>Both halves are needed and they do different jobs. Dismissing is what stops
     * a site's login dialog covering the picture - a wall has no way to click "later"
     * and the dialog is drawn over the video. Filling is what makes the picture use
     * the whole wall instead of a window-sized player.</p>
     *
     * <p>An earlier revision replaced the dismissal with the filling, and the login
     * dialog came straight back. They are one script now for that reason.</p>
     */
    private static final String FIT_VIDEO_JS = """
            (function(){
              try {
                if (!(location.hostname === 'bilibili.com' || location.hostname.endsWith('.bilibili.com'))) return;
                var removed = 0;
                ['.bili-mini-mask', '.bili-mini-login', '.bili-mini-container',
                 '.bpx-player-login-mask'].forEach(function (selector) {
                  document.querySelectorAll(selector).forEach(function (element) {
                    if (element.style.display !== 'none') {
                      element.style.display = 'none';
                      removed++;
                    }
                  });
                });

                window.__wdcdpFitted = (window.__wdcdpFitted || 0) + 1;
                window.__wdcdpDismissed = (window.__wdcdpDismissed || 0) + removed;
              } catch (e) {}
            })();
            """;

    /** Set the JPEG quality (1-100) and the ack rate cap. */
    public void setQuality(int jpegQuality, int fpsCap) {
        this.qualityOverride = Math.clamp(jpegQuality, 1, 100);
        this.fpsOverride = Math.clamp(fpsCap, 1, MAX_ADAPTIVE_FPS);
    }

    /**
     * Move the admission rate toward what the page and the machine can sustain.
     *
     * <p>Adjusts both ways, with hysteresis. Downward because a shortfall is caused
     * by real work (Chrome's encode, our decode and upload) and trying harder does
     * not make that work cheaper. Upward, but only to 60 and only when the page has
     * measurably been delivering more than it is allowed right now - that is the
     * signal that the content genuinely updates faster than 30 Hz, which is the
     * minority case but exactly the one that looks wrong when it is capped.</p>
     *
     * @param suggestedFps what the measurement says is achievable
     */
    public void adaptRate(int suggestedFps) {
        Viewport view = appliedViewport;
        if (suggestedFps <= 0 || fpsOverride > 0 || (view != null && view.fullscreen())) return;
        // Hysteresis in both directions: ignore small differences, or the rate
        // would drift on every sample because the measurement is inherently noisy.
        if (suggestedFps > maxFps) {
            // Room to go up. The old threshold demanded a measurement 6 fps above the
            // current cap, which interacted badly with the encoder skip: the measured
            // rate could never exceed the cap, so a 60 fps clip stayed at 30. Requiring
            // only a clear margin above the *admitted* rate lets real headroom through.
            if (maxFps >= MAX_ADAPTIVE_FPS || suggestedFps < maxFps + 3) return;
            // Step by whole frames rather than jumping to whatever was measured:
            // a single noisy sample should not set the ceiling.
            int next = Math.min(MAX_ADAPTIVE_FPS, Math.max(maxFps + 3, suggestedFps));
            LOG.fine("Page rate " + maxFps + " -> " + next + " fps (content is faster)");
            maxFps = next;
            return;
        }
        int target = Math.clamp(suggestedFps, MIN_FPS, MAX_ADAPTIVE_FPS);
        if (target >= maxFps - 3) {
            return;
        }
        int next = Math.max(MIN_FPS, target);
        if (next != maxFps) {
            LOG.fine("Page rate " + maxFps + " -> " + next + " fps (measured "
                    + suggestedFps + ")");
            maxFps = next;
        }
    }

    /** Below this, video stops reading as video. */
    private static final int MIN_FPS = 10;

    /**
     * Script that reports the page's dominant media aspect ratio.
     *
     * <p>Used to lay the page out at the shape of what it is actually showing. A
     * video page wants 16:9; a text page wants whatever the wall is. Reading it
     * from the page is the only way to know, because the configured screen
     * resolution is free text and frequently not the shape of the content.</p>
     */
    private static final String ASPECT_PROBE =
            "(()=>{try{var l=[].slice.call(document.querySelectorAll('video'));"
                    + "if(!l.length)return 0;"
                    + "var v=l.map(function(e){var r=e.getBoundingClientRect();"
                    + "return {a:(e.videoWidth||0)/(e.videoHeight||0),"
                    + "s:r.width*r.height}}).filter(function(o){return o.a>0.1&&o.a<10});"
                    + "if(!v.length)return 0;"
                    + "v.sort(function(x,y){return y.s-x.s});"
                    + "return Math.round(v[0].a*1000)/1000}catch(e){return 0}})()";

    /** Last aspect reported to the owner, so a change can be detected. */
    private volatile double lastReportedAspect;

    /** Receives the page's dominant media aspect ratio when it changes. */
    public void probeContentAspect(java.util.function.DoubleConsumer sink) {
        if (closed) {
            return;
        }
        evaluateForValue(ASPECT_PROBE).thenAccept(value -> {
            double aspect = parseDouble(value);
            if (aspect > 0.1 && aspect < 10.0
                    && Math.abs(aspect - lastReportedAspect) > 0.02) {
                lastReportedAspect = aspect;
                sink.accept(aspect);
            }
        }).exceptionally(error -> null);
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- navigation

    public void navigate(String target) {
        if (closed || target == null || target.isBlank()) {
            return;
        }
        url = target;
        setLoading(true);
        // A navigation is the moment frames can stop for good, so arm a check now
        // as well as on the load event: a page that never finishes loading would
        // otherwise never be inspected at all.
        restartScreencastAfterNavigation();
        // The stream is NOT restarted here. A navigation has not painted yet, and
        // restarting immediately cancels the capture that is about to be needed -
        // which produced a wall that never received a single frame after the first
        // navigation. Chrome's load event is the point where the new document is
        // actually there to capture, so the restart hangs off that instead.
        long revision = beginAudioNavigation();
        cdp.call(sessionId, "Page.navigate", CdpJson.obj("url", target))
                .thenAccept(result -> {
                    if (result.has("errorText") || !result.has("loaderId")) recoverAudioNavigation(revision);
                    else NAVIGATION_CHECK.schedule(() -> recoverAudioNavigation(revision), 3, TimeUnit.SECONDS);
                })
                .exceptionally(error -> {
                    recoverAudioNavigation(revision);
                    CdpLog.warn("Page.navigate to " + target + " failed: " + error.getMessage());
                    setLoading(false);
                    return null;
                });
    }

    /**
     * Re-arm the stream after a navigation, but only when it actually stalled.
     *
     * <p>Restarting on every load is wrong: {@code loadEventFired} also fires for
     * the initial page and for pages that reload themselves, and each restart
     * races the renderer. The counter is sampled and compared, and a restart only
     * happens when no frame arrived in the window.</p>
     *
     * <p>Checked more than once because a slow site can take several seconds to
     * paint anything after a load event - a single 1.5 s check saw "no frames yet",
     * concluded nothing was wrong, and the stall then persisted for the life of the
     * page. Each attempt is cheap (a frame counter) and repeats are bounded.</p>
     */
    private void restartScreencastAfterNavigation() {
        if (closed) return;
        scheduleStreamCheck(1, 1200);
    }

    /** How many post-navigation checks run before giving up. */
    private static final int STREAM_CHECKS = 4;

    private void scheduleStreamCheck(int attempt, long delayMillis) {
        if (closed || attempt > STREAM_CHECKS) return;
        long seen = frameCount.get();
        NAVIGATION_CHECK.schedule(() -> {
            if (closed) return;
            synchronized (this) {
                if (!streaming || mediaPaused) return;
                if (frameCount.get() != seen) {
                    // Frames are flowing, so the capture survived this navigation.
                    return;
                }
                long silent = System.nanoTime() - lastFrameNanos;
                if (silent < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(900)) {
                    scheduleStreamCheck(attempt + 1, delayMillis);
                    return;
                }
                // Prefer a repaint request: a page that simply is not drawing needs
                // one frame, while restarting the capture costs a round trip and
                // blanks the wall for the length of a start/ack cycle.
                nudgeStaticPage();
                if (attempt >= STREAM_CHECKS) {
                    // Repeated nudges did not produce a frame, so the capture itself
                    // is suspect rather than the page being idle.
                    stopScreencast();
                    updateScreencast();
                }
                scheduleStreamCheck(attempt + 1, delayMillis);
            }
        }, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Called from the frame pump: keeps a still page alive between navigations.
     *
     * <p>Separate from the post-navigation checks because this has to run for the
     * whole life of the page - a wall showing a document that never changes would
     * otherwise have no frame after its first one.</p>
     */
    public void keepFramesFlowing() {
        if (closed || !streaming || mediaPaused) return;
        if (capture == null) return;
        nudgeStaticPage();
    }

    /**
     * Keep a quiet page producing frames.
     *
     * <p>{@code Page.startScreencast} only emits a frame when the compositor paints,
     * so a page that has finished loading and is not animating delivers
     * <b>nothing</b>: measured on this build, a static page gave 0 fps while the same
     * page with a moving canvas gave 30 fps. The capture is healthy; there is simply
     * no new frame to send.</p>
     *
     * <p>That is invisible while the page is static - the wall keeps showing the
     * last frame - but it turns into a freeze whenever the texture is being rebuilt:
     * a navigation, a viewport change, or a screen re-created from the world save
     * starts the stream on a page that never paints, so the wall stays black with
     * nothing in the log. Nudging the page to repaint once produces exactly one
     * frame, which is all a still page needs.</p>
     */
    private void nudgeStaticPage() {
        if (closed) return;
        long silent = System.nanoTime() - lastFrameNanos;
        if (silent < STATIC_NUDGE_NANOS) return;
        if (System.nanoTime() - lastNudgeNanos < STATIC_NUDGE_NANOS) return;
        lastNudgeNanos = System.nanoTime();
        // Painting a 1px canvas is what actually commits a compositor frame. Three
        // other approaches were measured on this build and produced no frame at all:
        // toggling document opacity, a subpixel transform, and scrolling 1px and
        // back. Only a real paint in a layer the compositor tracks is seen.
        cdp.fire(sessionId, "Runtime.evaluate", CdpJson.obj(
                "expression", NUDGE_JS,
                "returnByValue", true,
                "userGesture", false));
    }

    /** Draws one 1px canvas frame, which is enough to produce one capture frame. */
    private static final String NUDGE_JS = "(()=>{try{"
            + "var c=document.getElementById('__wdcdpNudge');"
            + "if(!c){c=document.createElement('canvas');c.id='__wdcdpNudge';"
            + "c.width=c.height=1;"
            + "c.style.cssText='position:fixed;left:0;top:0;width:1px;height:1px;"
            + "opacity:0.01;pointer-events:none;z-index:-1';"
            + "(document.body||document.documentElement).appendChild(c);}"
            + "var g=c.getContext('2d');"
            + "g.fillStyle=g.fillStyle==='#fff'?'#000':'#fff';"
            + "g.fillRect(0,0,1,1);"
            + "return 1}catch(e){return 0}})()";

    /** Silence long enough that a static page is the likely explanation. */
    private static final long STATIC_NUDGE_NANOS = 900_000_000L;

    private volatile long lastNudgeNanos;

    /** Waits after a page load before checking whether capture survived it. */
    private static final java.util.concurrent.ScheduledExecutorService NAVIGATION_CHECK =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "wdcdp-navigation");
                thread.setDaemon(true);
                return thread;
            });

    public void reload(boolean ignoreCache) {
        if (closed) {
            return;
        }
        setLoading(true);
        cdp.fire(sessionId, "Page.reload", CdpJson.obj("ignoreCache", ignoreCache));
    }

    public void stopLoading() {
        if (closed) {
            return;
        }
        setLoading(false);
        cdp.fire(sessionId, "Page.stopLoading", new JsonObject());
    }

    public void back() {
        history(-1);
    }

    public void forward() {
        history(1);
    }

    private void history(int delta) {
        if (closed) {
            return;
        }
        cdp.call(sessionId, "Page.getNavigationHistory", new JsonObject())
                .thenAccept(result -> {
                    int index = CdpJson.integer(result, "currentIndex", 0) + delta;
                    JsonArray entries = CdpJson.array(result, "entries");
                    if (index < 0 || index >= entries.size()) {
                        return;
                    }
                    JsonObject entry = entries.get(index).getAsJsonObject();
                    cdp.fire(sessionId, "Page.navigateToHistoryEntry",
                            CdpJson.obj("entryId", CdpJson.integer(entry, "id", 0)));
                })
                .exceptionally(error -> null);
    }

    /**
     * Expose a JavaScript function that calls back into Java.
     *
     * <p>{@code Runtime.addBinding} installs {@code window.<name>}; every call
     * arrives as a {@code Runtime.bindingCalled} event carrying the string the page
     * passed. That is the only push channel CDP offers from page to host, and audio
     * needs it: polling would resample and drift.</p>
     */
    public CompletableFuture<Void> addBinding(String name, java.util.function.Consumer<String> onCall) {
        if (closed || name == null || name.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot install binding: " + name));
        }
        bindings.put(name, onCall);
        return cdp.call(sessionId, "Runtime.addBinding", CdpJson.obj("name", name)).thenAccept(result -> {});
    }

    private final java.util.Map<String, java.util.function.Consumer<String>> bindings =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Run a script in every new document, before the page's own scripts.
     *
     * <p>Audio capture has to be in place before a player starts, or the page plays
     * into an untapped context and stays silent for its whole life.</p>
     */
    public CompletableFuture<Void> addInitScript(String source) {
        if (closed || source == null || source.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot install init script"));
        }
        return cdp.call(sessionId, "Page.addScriptToEvaluateOnNewDocument",
                        CdpJson.obj("source", source))
                .thenAccept(result -> {
                    String identifier = CdpJson.string(result, "identifier", "");
                    if (!identifier.isEmpty()) {
                        initScripts.add(identifier);
                    }
                });
    }

    private final java.util.List<String> initScripts =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Dispatch a {@code Runtime.bindingCalled} event to its handler. */
    private void onBindingCalled(JsonObject params) {
        String name = CdpJson.string(params, "name", "");
        if (name.isEmpty() || media.onBinding(name,
                CdpJson.integer(params, "executionContextId", -1),
                CdpJson.string(params, "payload", ""))) {
            return;
        }
        java.util.function.Consumer<String> handler = bindings.get(name);
        if (handler == null) {
            return;
        }
        try {
            handler.accept(CdpJson.string(params, "payload", ""));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Binding handler " + name + " failed", e);
        }
    }

    /** Run JavaScript and ignore the result. */
    public void execute(String code) {
        if (closed || code == null || code.isEmpty()) {
            return;
        }
        cdp.fire(sessionId, "Runtime.evaluate", CdpJson.obj(
                "expression", code, "returnByValue", false));
    }

    /**
     * Run JavaScript and return its value as text.
     *
     * <p>Used for the cursor probe, which is the one place a result is actually
     * needed. The value comes back as a JSON primitive, so it is unwrapped with
     * {@code getAsString} rather than parsed as a tree.</p>
     */
    public CompletableFuture<String> evaluateForValue(String code) {
        if (closed || code == null || code.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        return cdp.call(sessionId, "Runtime.evaluate", CdpJson.obj(
                        "expression", code, "returnByValue", true))
                .thenApply(result -> {
                    JsonObject remote = CdpJson.child(result, "result");
                    if (!remote.has("value")) {
                        return "";
                    }
                    try {
                        return remote.get("value").getAsString();
                    } catch (RuntimeException e) {
                        return "";
                    }
                });
    }

    // ------------------------------------------------------------------ input

    private int mouseButtons;

    public synchronized void mouseMoved(int x, int y, int modifiers) {
        String activeButton = (mouseButtons & 1) != 0 ? "left" : (mouseButtons & 2) != 0 ? "right"
                : (mouseButtons & 4) != 0 ? "middle" : "none";
        mouse("mouseMoved", x, y, activeButton, mouseButtons, 0, modifiers);
    }

    public synchronized void mousePressed(int x, int y, int cefButton, int clickCount, int modifiers) {
        mouseButtons |= CdpKeys.buttonMask(cefButton);
        mouse("mousePressed", x, y, CdpKeys.mouseButton(cefButton), mouseButtons, clickCount, modifiers);
    }

    public synchronized void mouseReleased(int x, int y, int cefButton, int clickCount, int modifiers) {
        mouseButtons &= ~CdpKeys.buttonMask(cefButton);
        mouse("mouseReleased", x, y, CdpKeys.mouseButton(cefButton), mouseButtons, clickCount, modifiers);
    }

    private void mouse(String type, int x, int y, String button, int buttons,
                       int clicks, int modifiers) {
        if (closed) {
            return;
        }
        JsonObject params = CdpJson.obj(
                "type", type,
                "x", x,
                "y", y,
                "button", button,
                "buttons", buttons,
                "modifiers", modifiers);
        if (clicks > 0) {
            params.addProperty("clickCount", clicks);
        }
        cdp.fire(sessionId, "Input.dispatchMouseEvent", params);
    }

    /**
     * Dispatch a wheel event.
     *
     * @param deltaY pixels of scroll; positive scrolls the page down
     */
    public void wheel(int x, int y, double deltaX, double deltaY, int modifiers) {
        if (closed) {
            return;
        }
        cdp.fire(sessionId, "Input.dispatchMouseEvent", CdpJson.obj(
                "type", "mouseWheel",
                "x", x,
                "y", y,
                "deltaX", deltaX,
                "deltaY", deltaY,
                "modifiers", modifiers,
                "button", "none",
                "buttons", 0));
    }

    public void focusForInput() {
        if (!closed) cdp.fire(sessionId, "Page.bringToFront", new JsonObject());
    }

    public void keyDown(CdpKeys.Key key, int modifiers) {
        if (closed || key == null) {
            return;
        }
        // Printable characters arrive separately via keyTyped(). GLFW has no
        // character callback for Enter, but Chromium needs CR for its keypress
        // and default action (for example, submitting a single-line input).
        boolean enter = key.windowsVirtualKeyCode() == 0x0D
                && (modifiers & (CdpKeys.CTRL | CdpKeys.ALT | CdpKeys.META)) == 0;
        JsonObject params = keyEvent(enter ? "keyDown" : "rawKeyDown", key, modifiers);
        if (enter) {
            params.addProperty("text", "\r");
            params.addProperty("unmodifiedText", "\r");
        }
        cdp.fire(sessionId, "Input.dispatchKeyEvent", params);
    }

    public void keyUp(CdpKeys.Key key, int modifiers) {
        if (!closed && key != null) {
            cdp.fire(sessionId, "Input.dispatchKeyEvent", keyEvent("keyUp", key, modifiers));
        }
    }

    private static JsonObject keyEvent(String type, CdpKeys.Key key, int modifiers) {
        return CdpJson.obj(
                "type", type,
                "windowsVirtualKeyCode", key.windowsVirtualKeyCode(),
                "key", key.key(),
                "code", key.code(),
                "location", key.location(),
                "isKeypad", key.location() == 3,
                "modifiers", modifiers);
    }

    /** Insert a typed character. */
    public void keyTyped(char c) {
        if (closed) {
            return;
        }
        String text = String.valueOf(c);
        cdp.fire(sessionId, "Input.dispatchKeyEvent", CdpJson.obj(
                "type", "char",
                "text", text,
                "unmodifiedText", text,
                "key", text));
    }

    /** Mirror the native IME's transient text into the currently focused editor. */
    public void setComposition(String text, int cursor) {
        if (closed || text == null) return;
        int offset = Math.clamp(cursor, 0, text.length());
        cdp.fire(sessionId, "Input.imeSetComposition", CdpJson.obj(
                "text", text, "selectionStart", offset, "selectionEnd", offset));
    }

    /** Insert committed text, including characters outside the BMP. */
    public void insertText(String text) {
        if (!closed && text != null && !text.isEmpty()) {
            cdp.fire(sessionId, "Input.insertText", CdpJson.obj("text", text));
        }
    }

    /** Browser-level target listing, for the stray-tab regression. */
    public CompletableFuture<JsonObject> browserTargetsForTest() {
        return cdp.call(null, "Target.getTargets", new JsonObject());
    }

    /**
     * Capture a one-off PNG of the page.
     *
     * <p>Used by the stage 1 probe to exercise the decoder against known-good
     * input. Not used by the running mod: the screencast already supplies frames,
     * and a second capture per frame would double the encode cost.</p>
     */
    public CompletableFuture<String> screenshotForTest() {
        return cdp.call(sessionId, "Page.captureScreenshot",
                        CdpJson.obj("format", "png", "captureBeyondViewport", false))
                .thenApply(result -> CdpJson.string(result, "data", ""));
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Stop the stream and close the target.
     *
     * <p>Order matters: stop the screencast first, so no frame arrives for a
     * target that is going away, then close the target, then release the texture
     * on the render thread (the caller does that part - see {@code CdpBrowser}).</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        streaming = false;
        latestEncoded.set(null);
        cdp.fire(sessionId, "Page.stopScreencast", new JsonObject());
        cdp.fire(null, "Target.closeTarget", CdpJson.obj("targetId", targetId));
    }

    /** The browser closed this target from its side. */
    void markRemotelyClosed() {
        closed = true;
        streaming = false;
    }
}
