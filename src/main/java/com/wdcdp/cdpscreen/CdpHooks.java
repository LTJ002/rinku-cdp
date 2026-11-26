package com.wdcdp.cdpscreen;

import com.wdcdp.cdpscreen.validation.ReflectionValidator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.api.distmarker.Dist;

import java.nio.file.Path;

/**
 * Render-thread and tick hooks for the browser backend.
 *
 * <h2>Why this is a registered class, not a lambda</h2>
 * <p>These handlers were originally attached with
 * {@code NeoForge.EVENT_BUS.addListener(this::onRenderFrame)}. That form binds
 * <b>silently never fires</b> here: NeoForge resolves the event type from the
 * listener's functional interface, and a method reference on a private instance
 * method does not bind to a {@code Consumer<Event>} the way it appears to. There
 * is no error - the listener is simply never called - so the frame hook never
 * ran, no frame was ever uploaded, and every screen stayed black while every log
 * line said the backend was healthy.</p>
 *
 * <p>Registering the class and marking handlers with {@link SubscribeEvent} lets
 * the bus read the event type from the method signature, which is the form
 * NeoForge actually documents. WebDisplays' own client code carries the same
 * workaround for the same reason.</p>
 */
@EventBusSubscriber(modid = CdpScreenMod.MOD_ID, value = Dist.CLIENT)
public final class CdpHooks {

    /**
     * Set once setup has run, so the tick handler can tell "not started yet" from
     * "started and failed".
     */
    private static volatile boolean setupDone;

    /** Backoff for startup retries: a failed boot is retried, but not per tick. */
    private static volatile long nextInitAttemptNanos;

    private static final long INIT_RETRY_INTERVAL_NANOS = 10_000_000_000L;

    /** Ticks between "still no frame" reports, so a stuck page is diagnosable. */
    private static final int STALL_REPORT_TICKS = 200;
    private static int stallTicks;
    private static boolean seenClientLevel;
    private static int ticksWithoutLevel;

    private CdpHooks() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        CdpLog.info("WD CDP browser backend " + de.keksuccino.rinku.Rinku.VERSION + " loading");

        // Stage 0A in the real runtime: the reflection contract is the one failure
        // mode that produces no exception, only a black screen, so checking it on
        // every launch is cheaper than debugging it once.
        runContractCheck();

        // Browser state lives beside the game, not in the system temp directory:
        // it has to survive a restart so the Chrome profile keeps its caches and
        // cookies, and it must be obvious to a user who wants to clear it.
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path dataDir = gameDir.resolve("wdcdp");
        CdpBackend.get().setDataDirectory(dataDir);
        CdpLog.info("Browser profile directory: " + dataDir.resolve("chrome-profile"));

        // Do not block setup on Chrome: it takes a couple of seconds and setup runs
        // while the loading screen is still up.
        setupDone = true;
        nextInitAttemptNanos = 0L;
        startBackendAsync();
    }

    /** Whether the audio sink has been wired; retried until the mixer exists. */
    private static boolean audioInstalled;

    /**
     * Wire page audio into the game's mixer, retrying until the mixer is available.
     *
     * <p>{@code ClientInit.pageAudio()} is created by WebDisplays during its own
     * client setup, and mod load order does not guarantee it exists when this mod's
     * setup runs. Installing once at setup therefore found a null mixer and left
     * every page silent - with the "audio connected" line never printed, which was
     * easy to miss.</p>
     */
    private static void ensureAudioInstalled() {
        if (audioInstalled) {
            return;
        }
        if (mixer() == null) {
            return;
        }
        audioInstalled = true;
        installAudio();
    }

    /**
     * Wire page audio into the game's mixer.
     *
     * <p>CDP's screencast is video only, so the samples come from a script injected
     * into the page that taps its Web Audio graph - see {@code PageAudio}. This
     * method only connects that source to {@code BrowserAudio}, which already knows
     * how to open an output line and attenuate it by distance.</p>
     *
     * <p>Optional at every step: a machine with no mixer, or a page that plays no
     * audio, must still render.</p>
     */
    private static void installAudio() {
        try {
            Object mixer = mixer();
            if (mixer == null) {
                CdpLog.info("Page audio skipped - the WebDisplays mixer is not available");
                return;
            }
            // Remember it so a page being torn down can release its own line.
            CdpAudioMixer.set(mixer);
            // One mixer line per page, keyed by the browser's own id. The id comes
            // through the sink rather than being inferred, because a sink that
            // guessed would send one page's audio to every open line.
            // The rate comes from the page, not from an assumption. Chrome may run
            // the context at the device's rate, and feeding a 44.1 kHz stream to a
            // line opened at 48 kHz is heard as continuous crackle and hiss.
            CdpBrowser.setRateAwareAudioSink((planar, browserId, sampleRate) -> {
                try {
                    // The line is (re)opened whenever it is missing, or the page
                    // started reporting a different rate. isOpen alone is not enough:
                    // a line removed on teardown leaves the remembered rate behind,
                    // and a rebuilt page with the same rate would then never be
                    // opened again - silence with nothing in the log.
                    Integer known = lastRate.get(browserId);
                    // Value comparison, not reference: `known != sampleRate` compares
                    // Integer identities, and 44100 is far outside the cached range,
                    // so it answered "different" on every packet - reopening the
                    // device ~23 times a second, which is heard as constant stutter.
                    if (!MixerBridge.isOpen(mixer, browserId)
                            || known == null || known.intValue() != sampleRate) {
                        MixerBridge.startCdp(mixer, browserId, sampleRate, planar.length);
                        lastRate.put(browserId, sampleRate);
                    }
                    MixerBridge.packetFloat(mixer, browserId, planar, planar[0].length);
                } catch (Throwable t) {
                    // A mixer failure must not take the frame path down.
                    CdpLog.warn("Page audio packet failed: " + t);
                    lastRate.remove(browserId);
                }
            });
            CdpLog.info("Page audio connected to the mixer");
        } catch (Throwable t) {
            // The mixer lives in WebDisplays; without it this mod still works, just
            // silently.
            CdpLog.warn("Page audio unavailable: " + t);
        }
    }

    /** The rate each page's output line is open at, so a change reopens it. */
    private static final java.util.Map<Integer, Integer> lastRate =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Forget a page's remembered rate when its audio line is torn down. */
    public static void forgetRate(int browserId) {
        lastRate.remove(browserId);
    }

    /**
     * Tell WebDisplays to drop its per-world screen list.
     *
     * <p>A discarded client level does not necessarily call {@code setRemoved()}
     * for every wall, so WebDisplays keeps a static list of block entities that
     * belong to a level that no longer exists. Called through reflection because
     * this mod does not compile against WebDisplays.</p>
     */
    private static void forgetWallScreens() {
        try {
            Class<?> type = Class.forName("net.montoyo.wd.entity.ScreenBlockEntity");
            type.getMethod("forgetClientScreens").invoke(null);
        } catch (Throwable t) {
            // No WebDisplays, or an older one without the hook: the stale list
            // costs memory but does not break rendering.
            CdpLog.warn("Could not clear WebDisplays' screen list", t);
        }
    }

    /**
     * The mixer WebDisplays owns, or null when it is not present.
     *
     * <p>Reached by reflection so this mod does not need WebDisplays on its
     * compile classpath. A missing class or method simply means no page audio.</p>
     */
    private static Object mixer() {
        try {
            Class<?> init = Class.forName("net.montoyo.wd.client.ClientInit");
            return init.getMethod("pageAudio").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Upload decoded frames once per frame, on the render thread.
     *
     * <p><b>Which event, and why it took three tries.</b> Texture uploads have to
     * happen on the render thread, and the first two choices were both wrong in
     * different ways:</p>
     *
     * <ul>
     *   <li>{@code RenderFrameEvent.Pre} is the natural fit - it runs once per
     *       frame in {@code Minecraft.runTick}, whether or not the world is being
     *       drawn. It was tried first and appeared not to fire, which sent the
     *       search in the wrong direction for a while.</li>
     *   <li>{@code SubmitCustomGeometryEvent} does fire, but only while the level
     *       is being rendered. Opening the pause menu stops it, so the page froze
     *       whenever the player was not looking at the world - and, worse, a frame
     *       stayed stuck in the queue, which looks exactly like "the video is
     *       stuttering".</li>
     * </ul>
     *
     * <p>So this uses {@code RenderFrameEvent.Pre}, with {@link #onClientTick} as
     * a safety net: a tick pump is slower (20 Hz) but it is unconditional, and a
     * page that updates at 20 Hz beats a page that stops updating entirely. Both
     * paths funnel through the same idempotent upload, so running both costs
     * nothing.</p>
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (!setupDone) return;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        CdpBrowser.setGamePaused(minecraft.isPaused()
                || minecraft.screen instanceof net.minecraft.client.gui.screens.PauseScreen);
        pumpTextures();
    }

    private static void pumpTextures() {
        if (!setupDone) {
            return;
        }
        CdpBrowser.syncAll();
        CdpRenderQueue.drain();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!setupDone) {
            return;
        }
        CdpBackend backend = CdpBackend.get();
        if (net.minecraft.client.Minecraft.getInstance().level != null) {
            seenClientLevel = true;
            ticksWithoutLevel = 0;
            if (!backend.worldActive()) {
                backend.setWorldActive(true);
                nextInitAttemptNanos = System.nanoTime() + INIT_RETRY_INTERVAL_NANOS;
                CdpBrowser.setGamePaused(false);
                startBackendAsync();
            }
        } else if (seenClientLevel && ++ticksWithoutLevel >= 2) {
            seenClientLevel = false;
            backend.setWorldActive(false);
            for (CdpBrowser browser : CdpBrowser.liveBrowsers()) browser.close();
            forgetWallScreens();
            CdpLog.info("Client left its world; released wall screens and stopped the dedicated browser");
        }
        if (!backend.worldActive()) return;
        ensureAudioInstalled();

        if (!backend.isInitialized()) {
            if (backend.state() == CdpBackend.State.INITIALIZING) {
                return;
            }
            long now = System.nanoTime();
            if (now < nextInitAttemptNanos) {
                return;
            }
            nextInitAttemptNanos = now + INIT_RETRY_INTERVAL_NANOS;
            CdpLog.info("Retrying browser backend startup");
            startBackendAsync();
            return;
        }

        reportStalls();
        reportAudioOnce();
        reportLetterboxOnce();
    }

    /** Whether the letterbox state has been reported. */
    private static boolean letterboxReported;
    private static int letterboxTicks;

    /**
     * Report the numbers the wall's letterbox maths uses, once.
     *
     * <p>"The picture is stretched" has two causes that look identical: the page is
     * rendered at the wrong shape, or {@code resX/resY} does not match the page so
     * the borders are computed against the wrong ratio. Printing both ends it.</p>
     */
    private static void reportLetterboxOnce() {
        if (letterboxReported || CdpBrowser.liveCount() == 0) {
            return;
        }
        if (++letterboxTicks < 150) {
            return;
        }
        letterboxReported = true;
        for (CdpBrowser browser : CdpBrowser.liveBrowsers()) {
            int[] size = browser.getTextureSize();
            CdpLog.info("Letterbox: page=" + browser.viewportWidth() + "x"
                    + browser.viewportHeight()
                    + " frame=" + (size == null ? "none" : size[0] + "x" + size[1])
                    + " ready=" + browser.isTextureReady());
        }
    }

    /** Whether the audio state has been reported for the current page set. */
    private static boolean audioReported;
    private static int audioReportTicks;

    /**
     * Report the page's audio graph once, a few seconds after the first page.
     *
     * <p>Silence has two very different causes - the page plays nothing, or the
     * capture tap never attached - and from outside they are identical. One probe
     * distinguishes them, which is cheaper than guessing at the script.</p>
     */
    private static void reportAudioOnce() {
        if (audioReported || CdpBrowser.liveCount() == 0) {
            return;
        }
        if (++audioReportTicks < 120) {
            return;
        }
        audioReported = true;
        CdpBrowser.reportAudioState();
    }

    /**
     * Say something when pages exist but no frame has ever reached the GPU.
     *
     * <p>A black screen has several causes that all look identical from outside:
     * a page that never attached, a screencast that never started, a decode that
     * fails every frame, or a render hook that is not running at all. The counters
     * distinguish them, and printing them periodically costs nothing.</p>
     *
     * <p>Only reported while the situation is still broken. Once a page is on the
     * GPU there is nothing to say, and logging it every ten seconds was itself
     * costing frames.</p>
     */
    private static void reportStalls() {
        if (CdpBrowser.liveCount() == 0) {
            return;
        }
        if (CdpBrowser.liveReadyCount() > 0) {
            // Working. Reset so a later stall is reported promptly.
            stallTicks = 0;
            return;
        }
        if (++stallTicks < STALL_REPORT_TICKS) {
            return;
        }
        stallTicks = 0;
        CdpLog.warn("Screens exist but no page frame has reached the GPU: "
                + CdpBrowser.describeLive());
    }

    private static void startBackendAsync() {
        CdpBackend backend = CdpBackend.get();
        if (backend.isInitialized() || backend.state() == CdpBackend.State.INITIALIZING) {
            return;
        }
        backend.start().whenComplete((ok, error) -> {
            if (Boolean.TRUE.equals(ok)) {
                CdpLog.info("Browser backend ready");
            } else {
                CdpLog.warn("Browser backend did not start: "
                        + de.keksuccino.rinku.Rinku.lastFailure() + " - will retry");
            }
        });
    }

    private static void runContractCheck() {
        try {
            // The validator writes to stdout by design (it is also a standalone
            // tool), so the result is summarised into the game log here.
            ReflectionValidator.main(new String[0]);
            CdpLog.info("Reflection contract verified ("
                    + ReflectionValidator.totalChecks() + " checks)");
        } catch (RuntimeException e) {
            CdpLog.error("Reflection contract check crashed - screens may stay blank", e);
        }
    }
}
