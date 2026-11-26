package com.wdcdp.cdpscreen;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.wdcdp.cdpscreen.cdp.CdpClient;
import com.wdcdp.cdpscreen.cdp.CdpJson;
import com.wdcdp.cdpscreen.cdp.CdpPage;
import com.wdcdp.cdpscreen.cdp.ChromeFinder;
import com.wdcdp.cdpscreen.cdp.ChromeProcess;
import com.wdcdp.cdpscreen.render.PageTexture;

/**
 * Owns the Chrome process, the DevTools connection, and the page table.
 *
 * <p>One instance per game process. WebDisplays treats the browser backend as a
 * global that either is or is not initialised, so the state machine here is the
 * authority on that question - see {@link State}.</p>
 *
 * <h2>The state machine is load-bearing</h2>
 * <p>WebDisplays' {@code scheduleInit} short-circuits when the backend reports
 * itself initialised, and then goes straight to {@code createBrowser}. Reporting
 * {@code INITIALIZED} before Chrome is actually reachable therefore does not make
 * startup faster - it makes every screen create a null browser, and the mod's
 * own guard turns that into a silent black screen with one warning line. So the
 * transition to {@link State#INITIALIZED} happens only after the WebSocket
 * handshake succeeds, and any failure lands in {@link State#FAILED} where it can
 * be retried.</p>
 */
public final class CdpBackend {

    private static final Logger LOG = Logger.getLogger("wdcdp.backend");

    /** Backend lifecycle, mirroring what WebDisplays expects to observe. */
    public enum State {
        /** Nothing started yet. */
        UNINITIALIZED,
        /** Chrome is booting or the DevTools handshake is in flight. */
        INITIALIZING,
        /** Pages can be created. */
        INITIALIZED,
        /** Startup failed; a retry is allowed. */
        FAILED
    }

    private static final CdpBackend INSTANCE = new CdpBackend();

    public static CdpBackend get() {
        return INSTANCE;
    }

    private volatile State state = State.UNINITIALIZED;

    private volatile ChromeProcess chrome;
    private volatile CdpClient client;
    private volatile Path dataDir;
    private volatile boolean worldActive = true;
    private long generation;
    private CompletableFuture<Boolean> booting;
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "wdcdp-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    /** Decode threads. Small on purpose: decoding is bursty and each frame is MBs. */
    private ExecutorService decoderPool;

    /** Pages by CDP session id, for routing events off the single connection. */
    private final Map<String, CdpPage> pagesBySession = new ConcurrentHashMap<>();

    private final AtomicInteger pageCounter = new AtomicInteger();

    /** Set once, so a second world entry does not restart Chrome. */
    private volatile boolean shutdownHookInstalled;

    private CdpBackend() {
    }

    public State state() {
        return state;
    }

    public boolean isInitialized() {
        CdpClient connection = client;
        return worldActive && state == State.INITIALIZED && connection != null && !connection.isClosed();
    }

    /** Configure where profile and logs live. Must be called before {@link #start}. */
    public void setDataDirectory(Path dir) {
        this.dataDir = dir;
    }

    private Path resolveDataDir() {
        Path dir = dataDir;
        if (dir == null) {
            // Standalone (probe / validator) fallback.
            String tmp = System.getProperty("java.io.tmpdir");
            dir = Path.of(tmp, "wdcdp");
        }
        return dir;
    }

    // ------------------------------------------------------------------- boot

    /**
     * Start Chrome and connect, reporting the outcome through the future.
     *
     * <p>Idempotent: calling it while {@link State#INITIALIZING} returns the same
     * in-flight future rather than starting a second browser.</p>
     */
    public synchronized CompletableFuture<Boolean> start() {
        if (!worldActive) return CompletableFuture.completedFuture(false);
        if (isInitialized()) return CompletableFuture.completedFuture(true);
        if (booting != null) return booting;
        // A disconnected browser may still own this same profile.
        if (chrome != null || client != null) shutdownAsync();
        installShutdownHook();
        state = State.INITIALIZING;
        long token = ++generation;
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        booting = outcome;
        lifecycle.execute(() -> {
            boolean ready = false;
            try {
                ready = bootBlocking(token);
            } catch (Exception t) {
                LOG.log(Level.SEVERE, "CDP backend failed to start: " + t.getMessage(), t);
            } finally {
                synchronized (this) {
                    if (generation == token) {
                        booting = null;
                        if (!ready) state = State.FAILED;
                    }
                }
                outcome.complete(ready && isInitialized());
            }
        });
        return outcome;
    }

    /** Park the browser after the client actually loses its level, not on a login event. */
    public synchronized void setWorldActive(boolean active) {
        if (worldActive == active) return;
        worldActive = active;
        if (!active) shutdownAsync();
    }

    public boolean worldActive() {
        return worldActive;
    }

    private boolean bootBlocking(long token) throws IOException {
        synchronized (this) {
            if (token != generation) return false;
        }
        ChromeFinder.Found browser = ChromeFinder.find().orElseThrow(() -> new IOException(
                "No Chrome or Edge found. Install one, or pass -D"
                        + ChromeFinder.OVERRIDE_PROPERTY + "=<path>. "
                        + ChromeFinder.describeSearch()));

        Path dir = resolveDataDir();
        Files.createDirectories(dir);

        Path profile = dir.resolve("chrome-profile");
        int width = 1280;
        int height = 720;

        // Everything is built into locals and published under the lock at the end.
        // Writing the fields as we go would expose a half-built backend to
        // shutdown(), which runs on another thread and would close a process the
        // boot thread is still configuring.
        ChromeProcess started = ChromeProcess.start(
                browser, profile, dir, true, width, height, 25_000L);

        CdpClient connected;
        try {
            connected = CdpClient.connect(started.endpoint(), Duration.ofSeconds(10))
                    .get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.close();
            throw new IOException("Interrupted while connecting to the browser", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            started.close();
            throw new IOException("Could not open a DevTools connection to "
                    + started.endpoint(), e);
        }
        connected.onDisconnect(() -> {
            synchronized (this) {
                if (client != connected) return;
                LOG.warning("DevTools connection lost - the browser backend is down");
                state = State.FAILED;
            }
        });
        // The four-argument form is the one that carries a screencast frame's
        // pre-decoded bytes. A lambda here would bind to the three-argument abstract
        // method and silently discard every payload - the frames would arrive as an
        // empty event and nothing would decode. Hence the explicit override.
        connected.onEvent(new CdpClient.EventSink() {
            @Override
            public void accept(String sessionId, String method, JsonObject params) {
                routeEvent(sessionId, method, params, null);
            }

            @Override
            public void accept(String sessionId, String method, JsonObject params,
                               byte[] payload) {
                routeEvent(sessionId, method, params, payload);
            }
        });

        boolean published;
        synchronized (this) {
            published = token == generation && !connected.isClosed();
            if (published) {
                ensureDecoderPool();
                chrome = started;
                client = connected;
                state = State.INITIALIZED;
            }
        }
        if (!published) {
            stopResources(connected, started, null, List.of());
            return false;
        }
        LOG.info("CDP backend ready on " + started.endpoint() + " using " + browser.name());
        closeRestoredTabs(connected);
        startStrayTabWatch();
        return true;
    }
    /**
     * Remote-debugging discovery is deliberately never enabled.
     *
     * <p>{@code Target.setDiscoverTargets} looks like the obvious way to notice a
     * link opening a new window, and it was implemented and measured: enabling it
     * made the second backend start on a profile capture no frames at all. A
     * profile-wide subscription changes what Chrome reports for pages this backend
     * owns too, so the screen's own target was reaching the stray-tab path. Rather
     * than special-case that, discovery stays off. The polling path below handles
     * tabs that Chrome actually opened, after their initial address is available.</p>
     */
    private void closeRestoredTabs(CdpClient connection) {
        connection.call(null, "Target.getTargets", new JsonObject())
                .thenAccept(result -> {
                    if (connection != client) return;
                    var targets = CdpJson.array(result, "targetInfos");
                    for (var element : targets) {
                        if (!element.isJsonObject()) continue;
                        var target = element.getAsJsonObject();
                        if (!"page".equals(CdpJson.string(target, "type", ""))) continue;
                        String targetId = CdpJson.string(target, "targetId", "");
                        String targetUrl = CdpJson.string(target, "url", "");
                        if (targetId.isEmpty() || OWN_TARGETS.contains(targetId)) continue;
                        // Never close the blank page this backend starts on. A page
                        // created a moment later may still be reported by URL as
                        // about:blank, and closing it would kill a screen.
                        if (targetUrl == null || targetUrl.isEmpty()
                                || "about:blank".equals(targetUrl)) continue;
                        LOG.info("Closing a restored browser tab: " + targetUrl);
                        connection.fire(null, "Target.closeTarget",
                                CdpJson.obj("targetId", targetId));
                    }
                })
                .exceptionally(error -> {
                    LOG.log(Level.FINE, "Could not list browser targets", error);
                    return null;
                });
    }

    /** Target ids this backend created; everything else on the profile is stray. */
    private static final Set<String> OWN_TARGETS = ConcurrentHashMap.newKeySet();

    /** Targets whose creation is in flight, so their creation event is not stray. */
    private static final Set<String> CREATING = ConcurrentHashMap.newKeySet();

    /** Targets a close request is already in flight for, so it is sent once. */
    private static final Set<String> CLOSING = ConcurrentHashMap.newKeySet();

    /** A real document address; anything else (about:, empty) is not a page. */
    private static final java.util.regex.Pattern URL_SCHEME =
            java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    /** Called by {@link CdpPage} as soon as a target id exists. */
    public static void notifyCreating(String targetId) {
        if (targetId != null && !targetId.isEmpty()) CREATING.add(targetId);
    }

    /** Called by {@link CdpPage} when a target it reserved is abandoned. */
    public static void forgetCreating(String targetId) {
        if (targetId != null && !targetId.isEmpty()) CREATING.remove(targetId);
    }

    /**
     * Deliver one event to the page that owns its session.
     *
     * @param payload the pre-decoded bytes of a screencast frame, or null
     */
    private void routeEvent(String sessionId, String method, JsonObject params, byte[] payload) {
        if (sessionId == null) {
            // Browser-level event (target lifecycle). Only one matters: a target
            // that went away on its own leaves its page unusable.
            if ("Target.targetDestroyed".equals(method)) {
                String targetId = CdpJson.string(params, "targetId", "");
                if (!targetId.isEmpty()) {
                    pagesBySession.values().removeIf(page -> page.targetId().equals(targetId));
                }
            }
            return;
        }
        CdpPage page = pagesBySession.get(sessionId);
        if (page != null) {
            page.handleEvent(method, params, payload);
        }
    }

    /**
     * Watch for pages opened by the page itself, by polling.
     *
     * <p>{@code Target.setDiscoverTargets} is the obvious mechanism and it was
     * measured to be unusable here: subscribing profile-wide made the next backend
     * start capture nothing at all. A plain {@code Target.getTargets} poll has none
     * of that coupling - it is a read-only query with no persistent subscription -
     * so it is what this uses.</p>
     *
     * <p>Every page target that this backend did not create is a link's new window
     * or a tab restored from an unclean exit. Either way it is running offscreen
     * with nobody watching it, so its address is handed to the screen and the tab
     * is closed.</p>
     *
     * <p><b>Registered once.</b> {@code start()} is called again after a failure,
     * and a second registration would leave two timers sweeping the same profile -
     * and, worse, would keep running after the browser they were made for is gone.</p>
     */
    private void startStrayTabWatch() {
        if (strayWatchStarted) return;
        strayWatchStarted = true;
        SCHEDULER.scheduleWithFixedDelay(() -> {
            CdpClient connection = client;
            if (!isInitialized() || connection == null) return;
            try {
                pollStrayTabs(connection);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Stray-tab poll failed", e);
            }
        }, 3_000L, 3_000L, TimeUnit.MILLISECONDS);
    }

    /** Guards against stacking a second watchdog on every restart. */
    private volatile boolean strayWatchStarted;

    private void pollStrayTabs(CdpClient connection) {
        if (connection != client || !isInitialized()) return;
        connection.call(null, "Target.getTargets", new JsonObject())
                .thenAccept(result -> sweepStrayTabs(connection, result))
                .exceptionally(error -> null);
    }

    /** WindowOpen is only a hint: some in-page handlers cancel it or open about:blank first. */
    public void pollForPopup(CdpPage opener) {
        CdpClient connection = client;
        if (connection == null || pagesBySession.get(opener.sessionId()) != opener) return;
        for (long delay : new long[]{70L, 250L, 800L}) {
            SCHEDULER.schedule(() -> pollStrayTabs(connection), delay, TimeUnit.MILLISECONDS);
        }
    }

    /** Adopt or close every page target that is not one of ours. */
    private void sweepStrayTabs(CdpClient connection, JsonObject result) {
        if (connection != client || !isInitialized()) return;
        for (var element : CdpJson.array(result, "targetInfos")) {
            if (!element.isJsonObject()) continue;
            var target = element.getAsJsonObject();
            if (!"page".equals(CdpJson.string(target, "type", ""))) continue;
            String targetId = CdpJson.string(target, "targetId", "");
            if (targetId.isEmpty() || OWN_TARGETS.contains(targetId)
                    || CREATING.contains(targetId)) continue;
            String url = CdpJson.string(target, "url", "");
            if (url.isEmpty() || "about:blank".equals(url)) continue;
            adoptOrClose(connection, targetId, url, CdpJson.string(target, "openerId", ""));
        }
    }
    private final java.util.concurrent.ScheduledExecutorService SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "wdcdp-tabs");
                thread.setDaemon(true);
                return thread;
            });

    /** Close the offscreen player before the wall starts the same URL. */
    private void adoptOrClose(CdpClient connection, String targetId, String url, String openerId) {
        if (!URL_SCHEME.matcher(url).lookingAt() || !CLOSING.add(targetId)) return;
        CdpPage target = openerId.isEmpty() ? preferredPage(url) : pagesBySession.values().stream()
                .filter(page -> !page.isClosed() && openerId.equals(page.targetId()))
                .findFirst().orElse(null);
        String previous = target == null ? null : target.url();
        connection.call(null, "Target.closeTarget", CdpJson.obj("targetId", targetId))
                .whenComplete((result, error) -> {
                    CLOSING.remove(targetId);
                    if (connection != client || error != null || result == null
                            || !result.has("success") || !result.get("success").getAsBoolean()) return;
                    if (target != null && !target.isClosed() && previous.equals(target.url())
                            && !url.equals(previous)) {
                        LOG.info("Showing a newly opened tab on the screen: " + url);
                        target.navigate(url);
                    }
                });
    }
    /** The screen that should show a page, preferring one already on that site. */
    private CdpPage preferredPage(String url) {
        CdpPage fallback = null;
        for (CdpPage page : pagesBySession.values()) {
            if (fallback == null) fallback = page;
            else if (sameHost(page.url(), url)) return page;
        }
        return fallback;
    }

    private static boolean sameHost(String first, String second) {
        try {
            String a = java.net.URI.create(first).getHost();
            String b = java.net.URI.create(second).getHost();
            return a != null && a.equals(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private synchronized void ensureDecoderPool() {
        if (decoderPool != null && !decoderPool.isShutdown()) {
            return;
        }
        // Decoding is background work and must never outrank the game's own threads,
        // but it also has to keep up: measured on an 8-thread CPU, a 60 fps 1280x720
        // page delivered 60 frames while only 40-55 were decoded, and the rest were
        // discarded. Two threads was the binding constraint, not the encoder.
        //
        // Four is the ceiling: the queue is bounded and the pending texture holds a
        // single frame, so more workers would only decode frames nobody draws. At
        // MIN_PRIORITY+2 they yield to the render thread whenever it is busy.
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "wdcdp-decode-" + pageCounter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 2);
            return thread;
        };
        decoderPool = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                // Each page contributes at most one task and keeps only its newest
                // pending input. Dropping the task would strand that page's input.
                new java.util.concurrent.LinkedBlockingQueue<>(), factory);
        LOG.fine("Frame decoder pool: " + threads + " threads");
    }

    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (Throwable ignored) {
                // Nothing useful to do while the JVM is going down.
            }
        }, "wdcdp-shutdown"));
    }

    // ------------------------------------------------------------------ pages

    /**
     * Create a page that uploads its frames into the caller's texture.
     *
     * <p>The texture is passed in rather than created here on purpose. An earlier
     * version made one inside this method while {@code CdpBrowser} held its own,
     * so frames were decoded into a texture nobody ever drew: hundreds of frames,
     * zero decode failures, zero upload failures, and a black wall. There is
     * exactly one texture per page and the object that draws it owns it.</p>
     *
     * @param texture where decoded frames go; the caller owns it
     * @param width   initial viewport width
     * @param height  initial viewport height
     */
    public CompletableFuture<CdpPage> createPage(PageTexture texture, int width, int height) {
        CdpClient connection;
        ExecutorService pool;
        synchronized (this) {
            if (!isInitialized()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("CDP backend is not initialised (" + state + ")"));
            }
            connection = client;
            pool = decoderPool;
        }
        return CdpPage.create(connection, texture, pool, "about:blank").thenApply(page -> {
            synchronized (this) {
                CREATING.remove(page.targetId());
                if (connection != client || !isInitialized()) {
                    page.close();
                    throw new IllegalStateException("The owning Chrome session has ended");
                }
                OWN_TARGETS.add(page.targetId());
                pagesBySession.put(page.sessionId(), page);
            }
            page.setViewport(width, height);
            return page;
        });
    }
    /** Forget a page. The caller closes it first. */
    public void forget(CdpPage page) {
        if (page != null) {
            pagesBySession.remove(page.sessionId(), page);
            OWN_TARGETS.remove(page.targetId());
        }
    }

    public int livePageCount() {
        return pagesBySession.size();
    }

    // -------------------------------------------------------------- shutdown

    /**
     * Stop everything.
     *
     * <p>Safe to call more than once and from any thread. Called when leaving a
     * world for good, and from the JVM shutdown hook.</p>
     */
    public void shutdown() {
        shutdownAsync().join();
    }

    /** Invalidate old pages immediately; profile release runs before any queued restart. */
    public synchronized CompletableFuture<Void> shutdownAsync() {
        if (state == State.UNINITIALIZED && booting == null) return stopping;
        ++generation;
        state = State.UNINITIALIZED;
        booting = null;
        var pages = new ArrayList<>(pagesBySession.values());
        pagesBySession.clear();
        OWN_TARGETS.clear();
        CREATING.clear();
        CLOSING.clear();
        CdpClient connection = client;
        client = null;
        ChromeProcess process = chrome;
        chrome = null;
        ExecutorService pool = decoderPool;
        decoderPool = null;
        stopping = CompletableFuture.runAsync(() -> stopResources(connection, process, pool, pages), lifecycle);
        return stopping;
    }

    private static void stopResources(CdpClient connection, ChromeProcess process,
                                      ExecutorService pool, List<CdpPage> pages) {
        for (CdpPage page : pages) {
            try {
                page.close();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Page close failed during shutdown", e);
            }
        }
        if (pool != null) pool.shutdownNow();
        try {
            if (connection != null && !connection.isClosed()) {
                // Give Chrome a chance to flush the persistent profile first.
                connection.call(null, "Browser.close", new JsonObject()).get(1, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Browser.close did not acknowledge shutdown", e);
        } finally {
            try {
                if (connection != null) connection.close();
            } finally {
                if (process != null) process.close();
            }
        }
        LOG.info("CDP backend stopped");
    }
    /** Endpoint of the running browser, for diagnostics. */
    public String describe() {
        ChromeProcess process = chrome;
        URI endpoint = process == null ? null : process.endpoint();
        return "state=" + state + " pages=" + pagesBySession.size()
                + " endpoint=" + (endpoint == null ? "none" : endpoint);
    }

    /** Tail of Chrome's own log; only meaningful after a failed start. */
    public String chromeLog(int lines) {
        ChromeProcess process = chrome;
        return process == null ? "" : process.recentLog(lines);
    }

    /**
     * Open a normal Chrome window on the game's profile, for signing in.
     *
     * <p>Hiding a site's login dialog is cosmetic: the page is still logged out, so
     * the dialog returns. Signing in once fixes it for good, because the cookies
     * land in the profile the in-game browser uses.</p>
     *
     * <p>This stops the in-game browser first - a profile can only be opened by one
     * Chrome at a time - and the backend stays FAILED until it is restarted, which
     * the mod's retry path does on the next tick.</p>
     *
     * @return true when a window was opened
     */
    public boolean openSignInWindow(String url) {
        ChromeFinder.Found browser = ChromeFinder.find().orElse(null);
        if (browser == null) {
            LOG.warning("No browser available to sign in with");
            return false;
        }
        Path profile = resolveDataDir().resolve("chrome-profile");
        try {
            shutdown();
            Thread.sleep(1_000L);
            ChromeProcess.startSignInWindow(browser, profile, url);
            LOG.info("Sign-in window opened on " + profile
                    + " - sign in there, close it, then the screens will use the session");
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not open the sign-in window", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Number of decode threads currently available. */
    public int decoderThreads() {
        ExecutorService pool = decoderPool;
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor executor) {
            return executor.getCorePoolSize();
        }
        return 0;
    }

    /** Test hook: the list of live pages. */
    public List<CdpPage> livePages() {
        return List.copyOf(pagesBySession.values());
    }
}
