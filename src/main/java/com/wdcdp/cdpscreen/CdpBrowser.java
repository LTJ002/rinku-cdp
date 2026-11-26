package com.wdcdp.cdpscreen;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.wdcdp.cdpscreen.cdp.CdpKeys;
import com.wdcdp.cdpscreen.cdp.CdpPage;
import com.wdcdp.cdpscreen.render.PageTexture;
import de.keksuccino.rinku.listeners.RinkuCursorChangeListener;
import net.minecraft.resources.Identifier;

/**
 * The browser object WebDisplays drives, standing in for {@code RinkuBrowser}.
 *
 * <p>WebDisplays never imports this class. {@code BrowserHelper} reaches it by
 * reflection through {@code browser.getClass()} and looks for exactly fourteen
 * instance methods by name and signature; miss one and the corresponding feature
 * silently does nothing. The full list is therefore part of the contract, and
 * {@code ReflectionValidator} checks it at startup:</p>
 *
 * <pre>
 *   getTextureIdentifier  () -&gt; Identifier
 *   isTextureReady        () -&gt; boolean
 *   loadURL               (String)
 *   getURL                () -&gt; String
 *   resize                (int, int)
 *   sendMousePress        (int, int, int)
 *   sendMouseRelease      (int, int, int)
 *   sendMouseMove         (int, int)
 *   sendMouseWheel        (int, int, double, int)
 *   sendKeyPress          (int, long, int)
 *   sendKeyRelease        (int, long, int)
 *   sendKeyTyped          (char, int)
 *   executeJavaScript     (String, String, int)
 *   setCursorChangeListener (RinkuCursorChangeListener)
 * </pre>
 *
 * <h2>Why creation is asynchronous</h2>
 * <p>{@code createBrowser} must return an object immediately - WebDisplays stores
 * it and moves on - but a CDP page needs a target and a flattened session, which
 * are round trips. So the object exists at once with its texture identifier
 * already valid, and the page attaches when the handshake completes. Calls made
 * in between are recorded and replayed, which is what makes a screen configured
 * during startup show its page instead of staying blank.</p>
 */
public final class CdpBrowser {

    /** How long a fresh page waits before the first frame is considered late. */
    private static final long FIRST_FRAME_WARN_NANOS = 10_000_000_000L;

    /** Cursor probing is a round trip, so it is rate-limited. */
    private static final long CURSOR_PROBE_INTERVAL_NANOS = 120_000_000L;

    /** Live browsers, so the render thread can pump their textures once a frame. */
    private static final Set<CdpBrowser> LIVE = ConcurrentHashMap.newKeySet();

    private final PageTexture texture = new PageTexture();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong framesSeen = new AtomicLong();

    private volatile CdpPage page;
    private boolean scriptsReady;
    private volatile RinkuCursorChangeListener cursorListener;

    /** Requests that arrived before the page existed. */
    private volatile String pendingUrl = "about:blank";
    private volatile int viewportWidth = 640;
    private volatile int viewportHeight = 400;

    private volatile long createdAtNanos = System.nanoTime();
    private volatile long firstFrameNanos;
    private volatile long lastCursorProbeNanos;
    private volatile int lastCursorType;

    private volatile boolean transparent;
    private volatile String lastError = "";
    private final AtomicLong viewportChanges = new AtomicLong();

    CdpBrowser(boolean transparent, int width, int height) {
        this.transparent = transparent;
        this.viewportWidth = Math.max(1, width);
        this.viewportHeight = Math.max(1, height);
        // Seed the budget from the size WebDisplays passed to createBrowser, so a
        // wall-shape report that arrives before the first resize() re-derives from
        // the right starting point instead of from a guess.
        this.budgetW = Math.max(1, width);
        this.budgetH = Math.max(1, height);
        LIVE.add(this);
    }

    /**
     * Create a browser and start attaching its page.
     *
     * <p>Lives in this package rather than in {@code de.keksuccino.rinku} so the
     * facade stays a thin reflection surface: the constructor and
     * {@link #attach} are package-private, which keeps the object's creation
     * sequence in one place instead of spread across two packages.</p>
     */
    public static CdpBrowser create(CdpBackend backend, boolean transparent,
                                    int width, int height) {
        CdpBrowser browser = new CdpBrowser(transparent, width, height);
        // The texture is registered on the render thread by the frame pump, not
        // here. createBrowser() is called from both the render thread and the
        // client tick (WebDisplays retries from both), and TextureManager.register
        // touches GPU state - doing it on the caller's thread would be a crash
        // waiting for the wrong caller.
        browser.attach(backend.createPage(browser.texture, width, height));
        return browser;
    }

    /**
     * Attach the CDP page once it exists.
     *
     * <p>Applies everything that was requested while the page was being created,
     * in the order that matters: viewport first (so the first frame is already the
     * right size), then the pending navigation.</p>
     */
    void attach(CompletableFuture<CdpPage> creation) {
        creation.whenComplete((attached, error) -> {
            if (error != null) {
                lastError = String.valueOf(error.getMessage());
                CdpLog.warn("Could not create a CDP page: " + lastError, error);
                return;
            }
            synchronized (this) {
                if (closed.get()) {
                    attached.close();
                    CdpBackend.get().forget(attached);
                    return;
                }
                attached.setFrameListener(this::onFrame);
                applyViewport(attached);
                attached.setMediaPaused(gamePaused);
                var mediaReady = attached.installMediaControl().exceptionally(installError -> {
                    CdpLog.warn("Could not install media pause", installError);
                    return null;
                });
                // A pause or seek inside the page must not leave the queued tail
                // playing after the picture has stopped.
                attached.onPlaybackStopped(this::flushAudio);
                page = attached;
                var audioReady = attachAudioToSink().exceptionally(installError -> {
                    CdpLog.warn("Could not install audio capture", installError);
                    return null;
                });
                CompletableFuture.allOf(mediaReady, audioReady).thenRun(() -> {
                    synchronized (this) {
                        if (closed.get() || attached.isClosed()) return;
                        scriptsReady = true;
                        if (hasContent(pendingUrl)) navigateNow(pendingUrl);
                    }
                });
            }
        });
    }

    /**
     * Provide the audio sink before pages are created.
     *
     * <p>The sink receives the browser id alongside the samples, so it can open one
     * mixer line per page. Passing the id through the sink rather than having the
     * sink guess is what keeps two screens playing two videos from being mixed into
     * one stream.</p>
     */
    public static void setAudioSink(java.util.function.ObjIntConsumer<float[][]> sink) {
        PENDING_AUDIO_SINK = sink;
    }

    /** Receives samples together with the rate the page's context runs at. */
    @FunctionalInterface
    public interface RateAwareSink {
        void accept(float[][] planar, int browserId, int sampleRate);
    }

    private static volatile java.util.function.ObjIntConsumer<float[][]> PENDING_AUDIO_SINK;
    private static volatile RateAwareSink PENDING_RATE_SINK;

    /** Register a sink that also needs the true sample rate. */
    public static void setRateAwareAudioSink(RateAwareSink sink) {
        PENDING_RATE_SINK = sink;
    }

    private CompletableFuture<Void> attachAudioToSink() {
        // No pre-warm line is opened.
        //
        // It used to open one at a fixed 48 kHz, and the first real block then
        // reported a different rate from the page's own AudioContext. Reopening the
        // device at that point is audible as a click right as playback starts, and
        // the pre-warm existed only to save a few milliseconds. The line is now
        // opened once, at the rate the page actually reports.
        return com.wdcdp.cdpscreen.audio.PageAudio.install(page, () -> { }, (planar, frames, rate) -> {
            var rateSink = PENDING_RATE_SINK;
            if (!closed.get() && rateSink != null) {
                try {
                    rateSink.accept(planar, pageId, rate);
                    return;
                } catch (RuntimeException e) {
                    CdpLog.warn("Audio sink failed", e);
                }
            }
            var sink = PENDING_AUDIO_SINK;
            if (closed.get() || sink == null) return;
            try {
                sink.accept(planar, pageId);
            } catch (RuntimeException e) {
                CdpLog.warn("Audio sink failed", e);
            }
        });
    }

    private void onFrame() {
        if (firstFrameNanos == 0L) {
            firstFrameNanos = System.nanoTime();
        }
        framesSeen.incrementAndGet();
    }

    /**
     * Re-apply the overlay dismissal and video sizing. Render thread only.
     *
     * <p>Called from the frame pump rather than from {@link #onFrame}, because
     * onFrame runs on a decoder thread: firing a CDP command there would put JSON
     * serialisation for a UI concern on the thread that decodes frames. The pump is
     * already on the render thread and already runs every frame.</p>
     */
    private void maintainPagePresentation() {
        CdpPage current = page;
        // A page that has stopped painting sends no frames at all, so a screen whose
        // texture has to be rebuilt would stay black. This is cheap (a timestamp
        // comparison) and only asks the page to repaint when it has been silent.
        // Skipped while the game is paused: a frozen game must not keep paying for
        // captures nobody is looking at.
        if (current != null && !gamePaused) {
            current.keepFramesFlowing();
        }
        long now = System.nanoTime();
        if (now - lastFitNanos <= FIT_RETRY_INTERVAL_NANOS
                || now - createdAtNanos >= FIT_WINDOW_NANOS) {
            return;
        }
        lastFitNanos = now;
        if (current != null) {
            current.fitVideo();
        }
    }

    /** How often the overlay/video script is re-applied. */
    private static final long FIT_RETRY_INTERVAL_NANOS = 1_500_000_000L;

    /**
     * How long after creation the script keeps being re-applied.
     *
     * <p>Two minutes, not thirty seconds. Sites re-open their login dialog on a
     * timer, and one that appears after the retries stop is never dismissed - the
     * wall then shows a login form over the video until the page is reloaded. The
     * cost is one Runtime.evaluate every 1.5 s, which is nothing next to a frame
     * upload.</p>
     */
    private static final long FIT_WINDOW_NANOS = 120_000_000_000L;

    private volatile long lastFitNanos;

    // ------------------------------------------------------- texture contract

    /**
     * The texture identifier to draw with.
     *
     * <p>Valid from construction, before any frame has arrived: WebDisplays calls
     * this every frame for every screen, and a null here means "draw nothing".
     * Whether the texture actually has pixels is a separate question, answered by
     * {@link #isTextureReady()}.</p>
     *
     * <p>Must not block or allocate - it is called from the render loop.</p>
     */
    public Identifier getTextureIdentifier() {
        return texture.name();
    }

    /** True once at least one frame has reached the GPU. */
    public boolean isTextureReady() {
        return texture.isReady();
    }

    /**
     * Upload the newest decoded frame.
     *
     * <p>Render thread only; called once per frame by the mod's frame hook. Kept
     * public so the hook can reach it without a friend-package trick.</p>
     */
    public void syncTexture() {
        if (closed.get()) {
            return;
        }
        // Register on the render thread before anything else. This is the first
        // safe moment: the call site is the frame pump, which is guaranteed to be
        // the render thread, and it runs before WebDisplays resolves the identifier
        // for this frame's RenderType.
        texture.register();

        // Cap how often the GPU texture is rewritten.
        //
        // A full upload of a 540x360 frame is 778 KB, and glTexSubImage2D is a
        // synchronous call: at 60 Hz that is 46 MB/s of PCIe traffic plus a driver
        // round trip on the render thread every frame, which starves the game's own
        // rendering. The screen is a few blocks across on a monitor, so 30 uploads a
        // second is already more than it can show - the extra ones were pure cost.
        //
        // The check is a plain early return: the texture keeps only the newest
        // frame, so skipping this tick costs nothing - the pending frame is replaced
        // by a fresher one rather than queued behind it.
        // Page upkeep belongs on this thread, so it runs on both paths.
        maintainPagePresentation();

        long now = System.nanoTime();
        if (gamePaused || !texture.hasPending() || now - lastUploadNanos < MIN_UPLOAD_INTERVAL_NANOS) {
            return;
        }

        boolean uploadedBefore = texture.hasEverUploaded();
        texture.sync();
        lastUploadNanos = now;

        if (!uploadedBefore && texture.hasEverUploaded()) {
            // The first upload is the moment a screen stops being black. Worth one
            // line per page, because "blank screen" is otherwise indistinguishable
            // from "page never created".
            CdpLog.info("First page frame on the GPU: " + texture.width() + "x"
                    + texture.height() + " (" + framesSeen.get() + " decoded, url="
                    + getURL() + ")");
        }

        long sinceFirst = System.nanoTime() - createdAtNanos;
        if (firstFrameNanos == 0L && sinceFirst > FIRST_FRAME_WARN_NANOS
                && lastError.isEmpty() && page == null) {
            lastError = "no page after " + (sinceFirst / 1_000_000_000L) + "s";
        }
    }

    /**
     * Minimum gap between texture uploads.
     *
     * <p>One frame at 60 Hz. This has to match the stream rate: uploading on a 33 ms
     * cadence while frames arrive every 16 ms silently discarded every other frame,
     * which made a 60 fps stream look like 30. The upload itself is a single
     * glTexSubImage2D of the wall's capture size, which the decoder and upload
     * measurements show is affordable at this rate.</p>
     */
    private static final long MIN_UPLOAD_INTERVAL_NANOS = 16_000_000L;

    private volatile long lastUploadNanos;

    /**
     * A stable integer id for this page, for the audio mixer.
     *
     * <p>WebDisplays keys one output line per page through
     * {@code BrowserHelper.browserIdOf}, which on the CEF backend reads
     * {@code CefBrowser.getIdentifier()}. The mixer only needs an id that is unique
     * per live page and stable for its lifetime, so a counter serves the same
     * purpose without a protocol round trip.</p>
     */
    public int getIdentifier() {
        return pageId;
    }

    private static final java.util.concurrent.atomic.AtomicInteger PAGE_IDS =
            new java.util.concurrent.atomic.AtomicInteger();

    private final int pageId = PAGE_IDS.incrementAndGet();

    /** Texture dimensions currently on the GPU; 0 until the first upload. */
    public int textureWidth() {
        return texture.width();
    }

    /**
     * The page texture's actual pixel size.
     *
     * <p>WebDisplays uses this for its letterbox maths instead of the screen's
     * configured resolution, which is free text and need not match what the page is
     * rendered at. Returns null until the first frame has reached the GPU, so the
     * caller keeps using the configured value until there is something better.</p>
     */
    public int[] getTextureSize() {
        int w = texture.width();
        int h = texture.height();
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[]{w, h};
    }

    public int textureHeight() {
        return texture.height();
    }

    /** Width, height and fullscreen (0/1), atomically sampled from the shown frame. */
    public int[] getTexturePresentation() {
        var shown = texture.presentation();
        return shown == null ? null : new int[]{shown.width(), shown.height(), shown.fullscreen() ? 1 : 0};
    }

    public int getScreenGlowColor() {
        return !closed.get() && hasContent(pendingUrl) ? texture.glowColor() : 0;
    }

    // ------------------------------------------------------------- navigation

    /** Run the pending navigation once its burst has gone quiet. */
    private static final java.util.concurrent.ScheduledExecutorService NAVIGATION_TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "wdcdp-navigation-timer");
                thread.setDaemon(true);
                return thread;
            });

    private final NavigationDebounce navigation = new NavigationDebounce();
    private boolean navigationScheduled;

    /**
     * Navigate.
     *
     * <p>Deliberately never blocks: WebDisplays calls this from the render thread
     * when a URL is configured, and a synchronous round trip there would show up
     * as a frame hitch.</p>
     *
     * <p>Requests are filtered by {@link NavigationDebounce} first. A screen's URL
     * is reported every time its configuration syncs, so without that filter the
     * same page was re-navigated over and over - which blanks the picture, restarts
     * the video, rebuilds the audio graph and leaves a renderer behind for every
     * abandoned load.</p>
     */
    public synchronized void loadURL(String url) {
        if (closed.get()) return;
        String target = hasContent(url) ? url : "about:blank";
        CdpPage current = page;
        String live = current == null ? "" : current.url();
        boolean loading = current != null && current.isLoading();
        long now = System.nanoTime();
        var decision = navigation.request(target, live, loading, now);
        pendingUrl = target;
        if (decision == NavigationDebounce.Decision.IGNORE) return;
        if (decision == NavigationDebounce.Decision.LATER) {
            scheduleNavigation();
            return;
        }
        navigateNow(target);
    }

    private synchronized void scheduleNavigation() {
        if (navigationScheduled) return;
        navigationScheduled = true;
        NAVIGATION_TIMER.schedule(this::runDeferredNavigation, 60, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private synchronized void runDeferredNavigation() {
        navigationScheduled = false;
        if (closed.get()) return;
        String due = navigation.take(System.nanoTime());
        if (due == null) {
            if (navigation.hasPending()) scheduleNavigation();
            return;
        }
        if (due.equals(pendingUrl) && (page == null || !due.equals(page.url()))) navigateNow(due);
    }

    /** Load the page and make sure it is streaming. Caller holds the lock. */
    private void navigateNow(String target) {
        CdpPage current = page;
        if (current == null || !scriptsReady) return;
        if (hasContent(target)) {
            current.setStreaming(true);
            current.navigate(target);
        } else {
            current.setStreaming(false);
            current.navigate(target);
            flushAudio();
        }
    }

    private static boolean hasContent(String url) {
        return url != null && !url.isBlank() && !"about:blank".equals(url);
    }

    public String getURL() {
        CdpPage current = page;
        if (current != null) {
            String live = current.url();
            if (live != null && !live.isBlank()) {
                return live;
            }
        }
        String pending = pendingUrl;
        return pending == null ? "" : pending;
    }

    /** The page title, or the URL when the page has not set one. */
    public String getTitle() {
        CdpPage current = page;
        return current == null ? "" : current.title();
    }

    /**
     * Resize the page viewport.
     *
     * <p><b>The page behaves like a computer monitor.</b> It is laid out at a fixed
     * screen shape - 16:9 - regardless of how large the wall is, and WebDisplays
     * scales it to fit with equal borders. That is the behaviour a player expects
     * from a screen: the whole page is always visible, nothing is cropped, and a
     * bigger wall shows the same picture larger rather than showing more of it.</p>
     *
     * <p>This works because WebDisplays letterboxes using {@code resX/resY}
     * ({@code ScreenRenderer.fillPageUvs}), and {@code BrowserHelper.getTextureSize}
     * now reports the page's real frame size into that value. So the page is 16:9,
     * {@code resX/resY} says 16:9, and the wall's own shape drives the borders.
     * Reporting the wall's shape here instead - which an earlier version did - made
     * the page match the wall and defeated the letterbox entirely.</p>
     *
     * <p>The configured resolution is honoured as a pixel budget, so a wall
     * configured at 640x640 streams at 640x360: same cost, correct shape.</p>
     */
    public synchronized void resize(int width, int height) {
        if (closed.get()) return;
        budgetW = Math.max(1, width);
        budgetH = Math.max(1, height);
        applyViewport(page);
    }

    /** Update the physical aspect and configured capture budget in one operation. */
    public synchronized void resizeForWall(int width, int height, int surfaceX, int surfaceY, boolean manual) {
        if (closed.get() || surfaceX <= 0 || surfaceY <= 0) return;
        shapeX = surfaceX;
        shapeY = surfaceY;
        manualCapture = manual;
        resize(width, height);
    }

    private boolean manualCapture;
    private int captureWidth, captureHeight;

    private void applyViewport(CdpPage current) {
        int[] layout = fitToShape(budgetW, budgetH);
        int[] capture = fitCapture(budgetW, budgetH, layout, manualCapture ? 0 : shapeX,
                manualCapture ? 0 : shapeY, manualCapture ? MAX_CAPTURE_PIXELS : AUTO_CAPTURE_PIXELS);
        int w = layout[0];
        int h = layout[1];
        if (w != viewportWidth || h != viewportHeight || capture[0] != captureWidth || capture[1] != captureHeight) {
            CdpLog.info("Page layout " + viewportWidth + "x" + viewportHeight
                    + " -> " + w + "x" + h + " (surface " + shapeX + "x" + shapeY
                    + ", capture " + capture[0] + "x" + capture[1] + ")");
            viewportChanges.incrementAndGet();
        }
        viewportWidth = w;
        viewportHeight = h;
        captureWidth = capture[0];
        captureHeight = capture[1];
        int fullscreenEdge = manualCapture ? 0 : Math.max(
                desiredCaptureAxis(budgetW, shapeX), desiredCaptureAxis(budgetH, shapeY));
        if (current != null) current.setViewport(w, h, captureWidth, captureHeight, fullscreenEdge);
    }

    /** Raster size grows with the wall, within the fixed GPU upload budget. */
    private static final int PIXELS_PER_SURFACE_UNIT = 100;
    private static final int MAX_CAPTURE_PIXELS = 1920 * 1080;
    private static final int AUTO_CAPTURE_PIXELS = 1280 * 720;

    private static int desiredCaptureAxis(int configured, int surfaceUnits) {
        long autoPixels = (long) surfaceUnits * PIXELS_PER_SURFACE_UNIT;
        return (int) Math.min(CdpPage.MAX_STREAM_DIMENSION, Math.max(configured, autoPixels));
    }

    private static int[] fitCapture(int budgetW, int budgetH, int[] layout, int surfaceX, int surfaceY, int maxPixels) {
        double aspect = (double) layout[0] / layout[1];
        int maxW = Math.min(layout[0], desiredCaptureAxis(budgetW, surfaceX));
        int maxH = Math.min(layout[1], desiredCaptureAxis(budgetH, surfaceY));
        int w = Math.min(maxW, Math.max(1, (int) Math.round(maxH * aspect)));
        int h = Math.min(maxH, Math.max(1, (int) Math.round(w / aspect)));
        if (w * h > maxPixels) {
            double scale = Math.sqrt((double) maxPixels / (w * h));
            w = Math.max(1, (int) Math.floor(w * scale));
            h = Math.max(1, (int) Math.floor(h * scale));
        }
        return new int[]{w, h};
    }

    /**
     * The wall's block dimensions, or 0 until the owning screen reports them.
     *
     * <p>This is the shape the page must be laid out at. WebDisplays computes its
     * letterbox by <b>cropping</b> the page's UVs to the wall's aspect
     * ({@code fillPageUvs}), not by adding bars: a 16:9 page on a 3:2 wall loses
     * 16% of its height. Rendering the page at the wall's own aspect makes that
     * crop a no-op, so the whole page survives - and the content inside it is what
     * gets scaled to fit, which is the "like a monitor" behaviour.</p>
     */
    private volatile int shapeX;
    private volatile int shapeY;

    /**
     * Tell the browser the wall's block dimensions.
     *
     * <p>WebDisplays does not pass these through the browser API, but it does call
     * {@code resize} with the configured resolution - and that value is free text,
     * frequently not the wall's shape. Reporting the real shape is what lets the
     * page be laid out correctly.</p>
     */
    public synchronized void setWallShape(int sizeX, int sizeY) {
        if (sizeX <= 0 || sizeY <= 0) {
            return;
        }
        if (sizeX == shapeX && sizeY == shapeY) {
            return;
        }
        shapeX = sizeX;
        shapeY = sizeY;
        // Re-derive from the ORIGINAL budget, not from the current layout.
        //
        // resize() turns the budget into a layout size (at least 1280 wide). Feeding
        // that layout back in as a budget happens to be idempotent for the shapes
        // tried so far, but it is not idempotent in general - and the moment it is
        // not, every call grows the page a little. The budget is what WebDisplays
        // said; that is what gets remembered.
        resize(budgetW, budgetH);
    }

    /** The pixel budget last reported by WebDisplays. */
    private volatile int budgetW = 640;
    private volatile int budgetH = 400;

    /**
     * Largest page size with the wall's shape that fits the pixel budget.
     *
     * <p>Falls back to 16:9 before the wall's shape is known, so the first frames
     * are already a sane shape rather than the arbitrary configured resolution.</p>
     *
     * <p><b>The page is laid out at desktop size, not at the wall's pixel size.</b>
     * This is the difference between a page that looks like a browser and a page
     * that looks like a phone. Measured: at a 540x360 viewport the video element is
     * 668 px wide - <i>wider than the viewport</i> - because the site's responsive
     * layout switches to its narrow mode, and a login dialog in that mode covers
     * most of the screen. At 1600x900 the same video is 0.66 of the width and the
     * dialog looks like a dialog.</p>
     *
     * <p>The capture is downscaled to the wall's pixel budget afterwards, so the
     * frame that reaches the GPU is still small; only the layout is large. Text
     * stays crisp because the site renders at full size and the downscale is
     * smooth.</p>
     */
    private int[] fitToShape(int budgetW, int budgetH) {
        int sx = shapeX;
        int sy = shapeY;
        double target = (sx > 0 && sy > 0)
                ? (double) sx / (double) sy
                : SCREEN_ASPECT;

        // Keep a desktop viewport while giving larger walls a full-resolution source.
        int layoutW = Math.max(1280, desiredCaptureAxis(budgetW, sx));
        double desiredHeight = layoutW / target;
        if (desiredHeight > 3840) {
            layoutW = (int) Math.round(3840 * target);
            return new int[]{Math.max(1, layoutW), 3840};
        }
        return new int[]{layoutW, Math.max(1, (int) Math.round(desiredHeight))};
    }

    /**
     * Fallback shape before the wall reports its size.
     *
     * <p>16:9 because that is what video is, and a screen showing video is the
     * common case.</p>
     */
    private static final double SCREEN_ASPECT = 16.0 / 9.0;


    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    // ------------------------------------------------------------------ input

    /**
     * Mouse press.
     *
     * <p>{@code button} arrives already in CEF numbering (0 left, 1 middle,
     * 2 right) - WebDisplays converts from Minecraft's numbering before calling.
     * CDP uses the same numbering, so it is passed straight through.</p>
     */
    /**
     * Map a pixel from WebDisplays' coordinate space into the page's viewport.
     *
     * <p>WebDisplays computes pointer pixels from the screen's <b>configured
     * resolution</b> ({@code hitToScreenCoords} multiplies a 0..1 hit by
     * {@code resolution.x/y}). That value is not the layout budget: the page is laid
     * out at a desktop size derived from the wall's shape, so on a wall whose
     * configured resolution disagrees with its shape the two differ by a large
     * factor - a 768-wide configured resolution against a 1920-wide layout put every
     * click at roughly 40% of where it belonged, which reads as the picture being
     * cropped.</p>
     *
     * <p>The conversion therefore uses the configured resolution as the input space,
     * which is the space the caller actually speaks.</p>
     */
    private int viewportX(int pixel, CdpPage.Viewport layout) {
        return viewportCoordinate(pixel, budgetW, layout == null ? viewportWidth : layout.width());
    }

    private int viewportY(int pixel, CdpPage.Viewport layout) {
        return viewportCoordinate(pixel, budgetH, layout == null ? viewportHeight : layout.height());
    }

    private static int viewportCoordinate(int pixel, int inputSize, int viewportSize) {
        if (pixel < 0) return pixel;
        int css = (int) ((pixel + 0.5) * viewportSize / inputSize);
        return Math.min(css, viewportSize - 1);
    }

    public void sendMousePress(int x, int y, int button) {
        CdpPage current = page;
        if (current == null) {
            return;
        }
        var layout = current.currentViewport();
        if (current.acceptsPointer(layout)) {
            current.mousePressed(viewportX(x, layout), viewportY(y, layout), button, 1, 0);
        }
    }

    public void sendMouseRelease(int x, int y, int button) {
        CdpPage current = page;
        if (current == null) {
            return;
        }
        var layout = current.currentViewport();
        current.mouseReleased(viewportX(x, layout), viewportY(y, layout), button, 1, 0);
    }

    public void sendMouseMove(int x, int y) {
        CdpPage current = page;
        if (current == null) {
            return;
        }
        var layout = current.currentViewport();
        if (!current.acceptsPointer(layout)) return;
        int cssX = viewportX(x, layout);
        int cssY = viewportY(y, layout);
        current.mouseMoved(cssX, cssY, 0);
        maybeProbeCursor(cssX, cssY);
    }

    /**
     * Mouse wheel.
     *
     * <p>{@code amount} is what WebDisplays computed as {@code -scrollY * 4}, i.e.
     * already scaled to "pixels of content", so it maps directly onto CDP's
     * {@code deltaY} without further conversion.</p>
     */
    public void sendMouseWheel(int x, int y, double amount, int modifiers) {
        CdpPage current = page;
        if (current == null) {
            return;
        }
        var layout = current.currentViewport();
        if (current.acceptsPointer(layout)) {
            current.wheel(viewportX(x, layout), viewportY(y, layout), 0, amount, toCdpModifiers(modifiers));
        }
    }

    /** CEF compatibility entry point. {@code vk} is a Windows virtual-key code. */
    public void sendKeyPress(int vk, long scanCode, int modifiers) {
        CdpPage current = page;
        if (current != null) {
            int cdpModifiers = toCdpModifiers(modifiers);
            current.keyDown(CdpKeys.describe(vk, cdpModifiers), cdpModifiers);
        }
    }

    public void sendKeyRelease(int vk, long scanCode, int modifiers) {
        CdpPage current = page;
        if (current != null) {
            int cdpModifiers = toCdpModifiers(modifiers);
            current.keyUp(CdpKeys.describe(vk, cdpModifiers), cdpModifiers);
        }
    }

    /** WebDisplays may pass Minecraft's GLFW input directly to the CDP backend. */
    public void sendGlfwKeyPress(int glfwKey, long scanCode, int modifiers) {
        CdpPage current = page;
        if (current != null) {
            int cdpModifiers = toCdpModifiers(modifiers);
            current.keyDown(CdpKeys.describeGlfw(glfwKey, cdpModifiers), cdpModifiers);
        }
    }

    public void sendGlfwKeyRelease(int glfwKey, long scanCode, int modifiers) {
        CdpPage current = page;
        if (current != null) {
            int cdpModifiers = toCdpModifiers(modifiers);
            current.keyUp(CdpKeys.describeGlfw(glfwKey, cdpModifiers), cdpModifiers);
        }
    }

    /** CEF compatibility entry point for one character from the game. */
    public void sendKeyTyped(char c, int modifiers) {
        CdpPage current = page;
        if (current != null) {
            current.keyTyped(c);
        }
    }

    public void focusForInput() {
        CdpPage current = page;
        if (current != null) current.focusForInput();
    }

    /**
     * Leave a fullscreen player the page is showing.
     *
     * <p>WebDisplays resolves this by exact name through {@code BrowserHelper.method},
     * which returns null on a miss and then silently does nothing. Without this
     * method the Escape path in the keyboard panel looked implemented but never
     * fired, which is why a wall could end up stuck on an enlarged player.</p>
     *
     * <p>Asynchronous on purpose: the answer comes from the page, and waiting for it
     * on the render thread would stutter the game. The caller acts when the future
     * completes.</p>
     *
     * @return a future resolving to true when fullscreen was active and an exit was
     *         requested, so the caller can treat the key as consumed; never null
     */
    public java.util.concurrent.CompletableFuture<Boolean> exitFullscreen() {
        CdpPage current = page;
        if (current == null || closed.get()) {
            return java.util.concurrent.CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return current.exitFullscreen();
    }

    public void setTextComposition(String text, int cursor) {
        CdpPage current = page;
        if (current != null) current.setComposition(text, cursor);
    }

    /** Deliver a committed string in one event, including supplementary code points. */
    public void sendText(String text) {
        CdpPage current = page;
        if (current != null) {
            current.insertText(text);
        }
    }

    /**
     * Translate GLFW's Shift=1, Ctrl=2, Alt=4, Super=8 to CDP's
     * Alt=1, Ctrl=2, Meta=4, Shift=8.
     */
    private static int toCdpModifiers(int glfwModifiers) {
        int out = 0;
        if ((glfwModifiers & 0x0001) != 0) out |= CdpKeys.SHIFT;
        if ((glfwModifiers & 0x0002) != 0) out |= CdpKeys.CTRL;
        if ((glfwModifiers & 0x0004) != 0) out |= CdpKeys.ALT;
        if ((glfwModifiers & 0x0008) != 0) out |= CdpKeys.META;
        return out;
    }

    // ------------------------------------------------------------- javascript

    /**
     * Run JavaScript.
     *
     * <p>WebDisplays passes {@code (code, "", 0)} - the extra two arguments are a
     * CEF script URL and line number, which CDP has no use for. The result is not
     * requested because every call site ignores it.</p>
     */
    public void executeJavaScript(String code, String scriptUrl, int startLine) {
        CdpPage current = page;
        if (current == null || code == null || code.isEmpty()) {
            return;
        }
        current.execute(code);
    }

    // ----------------------------------------------------------------- cursor

    /**
     * Register a cursor-change listener.
     *
     * <p>The parameter type must be exactly {@code RinkuCursorChangeListener}:
     * WebDisplays resolves this method with {@code getMethod(name, thatClass)}, so
     * declaring {@code Object} makes the lookup fail and the drawn cursor stops
     * tracking the page.</p>
     */
    public void setCursorChangeListener(RinkuCursorChangeListener listener) {
        this.cursorListener = listener;
    }

    public RinkuCursorChangeListener getCursorChangeListener() {
        return cursorListener;
    }

    /**
     * Ask the page what cursor it would show at a point.
     *
     * <p>CDP has no cursor event - CEF's {@code onCursorChange} has no DevTools
     * equivalent - so it is computed from the element under the pointer. That is a
     * round trip, hence the rate limit: probing on every mouse move would add a
     * request per frame per screen for something purely cosmetic.</p>
     */
    private void maybeProbeCursor(int x, int y) {
        RinkuCursorChangeListener listener = cursorListener;
        if (listener == null) {
            return;
        }
        CdpPage current = page;
        if (current == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCursorProbeNanos < CURSOR_PROBE_INTERVAL_NANOS) {
            return;
        }
        lastCursorProbeNanos = now;

        // Only the handful of cursor shapes WebDisplays can draw are mapped; the
        // rest fall back to the arrow, which is what a page mostly shows anyway.
        String script = "(()=>{try{var e=document.elementFromPoint(" + x + "," + y + ");"
                + "if(!e)return 0;var c=getComputedStyle(e).cursor||'default';"
                + "if(c==='pointer')return 14;if(c==='text')return 4;"
                + "if(c==='wait'||c==='progress')return 3;"
                + "if(c==='move')return 11;if(c==='not-allowed')return 5;"
                + "if(c==='crosshair')return 2;return 0}catch(e){return 0}})()";
        current.evaluateForValue(script).thenAccept(value -> {
            int type = parseCursor(value);
            if (type != lastCursorType) {
                lastCursorType = type;
                RinkuCursorChangeListener target = cursorListener;
                if (target != null) {
                    try {
                        target.onCursorChange(type);
                    } catch (RuntimeException e) {
                        CdpLog.warn("Cursor listener threw", e);
                    }
                }
            }
        }).exceptionally(error -> null);
    }

    private static int parseCursor(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            // Runtime.evaluate returns the number as a JSON-ish string here.
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -------------------------------------------------------------- lifecycle

    private static volatile boolean gamePaused;

    public static void setGamePaused(boolean paused) {
        if (gamePaused == paused) return;
        gamePaused = paused;
        for (CdpBrowser browser : LIVE) browser.applyGamePause();
    }

    private synchronized void applyGamePause() {
        if (closed.get()) return;
        CdpPage current = page;
        if (current != null) {
            current.setMediaPaused(gamePaused);
            if (gamePaused) flushAudio();
        }
    }

    private void flushAudio() {
        var mixer = CdpAudioMixer.get();
        if (mixer != null) {
            com.wdcdp.cdpscreen.audio.PageAudio.control(
                    () -> CdpAudioMixer.flush(mixer, pageId));
        }
    }

    /** Render-thread hook target; see {@link #syncTexture()}. */
    public static void syncAll() {
        SYNC_CALLS.incrementAndGet();
        for (CdpBrowser browser : LIVE) {
            try {
                browser.syncTexture();
            } catch (RuntimeException e) {
                CdpLog.warn("Texture sync failed for a page", e);
            }
        }
    }

    /** How many times the render-thread hook has run; proves the hook fires. */
    private static final AtomicLong SYNC_CALLS = new AtomicLong();

    public static long syncCalls() {
        return SYNC_CALLS.get();
    }

    /** How many browsers are alive; used by the leak check. */
    public static int liveCount() {
        return LIVE.size();
    }

    /** Ask every page to describe its audio graph, once. */
    public static void reportAudioState() {
        for (CdpBrowser browser : LIVE) {
            CdpPage current = browser.page;
            if (current != null) {
                com.wdcdp.cdpscreen.audio.PageAudio.reportState(current);
            }
        }
    }

    /** Snapshot of the live browsers, for the audio pump. */
    public static java.util.List<CdpBrowser> liveBrowsers() {
        return java.util.List.copyOf(LIVE);
    }

    /** How many browsers have at least one frame on the GPU. */
    public static int liveReadyCount() {
        int ready = 0;
        for (CdpBrowser browser : LIVE) {
            if (browser.texture.isReady()) {
                ready++;
            }
        }
        return ready;
    }

    /** Total frames received across every page; the frame-rate probe reads this. */
    public static long totalFramesSeen() {
        long total = 0;
        for (CdpBrowser browser : LIVE) {
            total += browser.framesSeen.get();
        }
        return total;
    }

    /**
     * Adjust every page's stream rate to what that page is actually achieving.
     *
     * <p>Measured per page, not as a wall-wide average: dividing a total by the
     * number of screens made one busy wall and two idle ones look equally slow, and
     * an idle page would then drag a working one's ceiling down while never being
     * allowed to rise.</p>
     *
     * <p><b>Measured on arrivals, not on admitted frames.</b> The admitted count is
     * capped by {@code maxFps} itself, so it can never report a rate above the
     * current ceiling - feeding it back made the "raise to 60" branch unreachable
     * and left every page pinned at whatever it started with. The arrival counter is
     * what Chrome actually produced.</p>
     */
    public static void adaptToMeasuredRate(double unusedAggregateFps) {
        if (LIVE.isEmpty()) return;
        long now = System.nanoTime();
        for (CdpBrowser browser : LIVE) {
            CdpPage current = browser.page;
            if (current == null) continue;
            long frames = current.framesReceived();
            long previousFrames = browser.adaptFrames;
            long previousNanos = browser.adaptNanos;
            browser.adaptFrames = frames;
            browser.adaptNanos = now;
            if (previousNanos == 0L) continue;
            double seconds = (now - previousNanos) / 1_000_000_000.0;
            if (seconds <= 0.2) continue;
            double measured = (frames - previousFrames) / seconds;
            if (measured > 0) current.adaptRate((int) Math.round(measured));
        }
    }

    /** Per-page sampling state for {@link #adaptToMeasuredRate}. */
    private volatile long adaptFrames;
    private volatile long adaptNanos;

    /**
     * One line per live browser, for the "black screen" diagnosis.
     *
     * <p>Reports each stage separately, because a black screen looks the same
     * whether the page never attached, the screencast never started, or every
     * decode failed.</p>
     */
    public static String describeLive() {
        if (LIVE.isEmpty()) {
            return "no browsers";
        }
        StringBuilder out = new StringBuilder();
        out.append("[hookCalls=").append(SYNC_CALLS.get()).append("]");
        for (CdpBrowser browser : LIVE) {
            out.append(" | ");
            CdpPage current = browser.page;
            out.append("url=").append(browser.getURL())
                    .append(" page=").append(current == null ? "attaching" : "attached")
                    .append(" streaming=").append(current != null && current.isStreaming())
                    .append(" frames=").append(current == null ? 0 : current.framesReceived())
                    .append(" dropped=").append(current == null ? 0 : current.framesDropped())
                    .append(" acks=").append(current == null ? 0 : current.framesAcked())
                    .append(" decodeFail=").append(current == null ? 0 : current.decodeFailureCount())
                    .append(" viewportChg=").append(browser.viewportChanges.get())
                    .append(" rebuilds=").append(browser.texture.rebuildCount())
                    .append(" pending=").append(browser.texture.hasPending())
                    .append(" uploadFail=").append(browser.texture.uploadFailureCount())
                    .append(" gpu=").append(browser.texture.isReady()
                            ? browser.texture.width() + "x" + browser.texture.height()
                            : "none");
            if (browser.texture.uploadFailureCount() > 0) {
                out.append(" upErr=").append(browser.texture.lastUploadError());
            }
            if (current != null && current.decodeFailureCount() > 0) {
                out.append(" err=").append(current.lastDecodeError());
            }
        }
        return out.toString();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long framesSeen() {
        return framesSeen.get();
    }

    public String lastError() {
        return lastError;
    }

    public boolean isTransparent() {
        return transparent;
    }

    /**
     * Tear the page down.
     *
     * <p>Unlike CEF, closing a CDP page does not risk the process: Chrome is a
     * separate process and closing a target is just a protocol message. The
     * texture, however, is a GPU object and must be released on the render
     * thread, so that part is posted rather than done inline.</p>
     */
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LIVE.remove(this);

        CdpPage current = page;
        page = null;
        if (current != null) {
            current.close();
            CdpBackend.get().forget(current);
        }

        var mixer = CdpAudioMixer.get();
        if (mixer != null) {
            com.wdcdp.cdpscreen.audio.PageAudio.control(
                    () -> CdpAudioMixer.stop(mixer, pageId));
        }
        // The mixer keeps per-page state; clearing it here is what lets a screen
        // built again later open its line fresh instead of staying silent.
        CdpHooks.forgetRate(pageId);

        // GPU objects may only be touched on the render thread, and close() can be
        // called from the server thread when a block is broken, so the texture is
        // handed to the render-thread queue rather than released inline.
        CdpRenderQueue.release(texture);
    }
}
