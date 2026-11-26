package de.keksuccino.rinku;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.wdcdp.cdpscreen.CdpBackend;
import com.wdcdp.cdpscreen.CdpBrowser;
import com.wdcdp.cdpscreen.CdpLog;
import de.keksuccino.rinku.listeners.RinkuInitListener;

/**
 * Drop-in replacement for Rinku's entry point, backed by Chrome's DevTools
 * protocol instead of CEF.
 *
 * <h2>Why this exists</h2>
 * <p>WebDisplays only talks to its browser library through
 * {@code BrowserHelper}'s reflection, and the only thing it insists on is a mod
 * with id {@code rinku} being loaded plus these five static methods on
 * {@code de.keksuccino.rinku.Rinku}. That makes the browser engine replaceable
 * without touching WebDisplays at all - which is the whole point, because the
 * bundled CEF 151 has no H.264 decoder and sites that check for it refuse to play.
 * Chrome 157 has one.</p>
 *
 * <h2>The contract is exact-match reflection</h2>
 * <p>{@code BrowserHelper.method()} uses
 * {@code getMethod(name, exactParamTypes...)}. A mismatch does not throw - it
 * returns null, the call site skips the call, and the screen stays black with one
 * warning line. So these signatures are frozen, and
 * {@code com.wdcdp.cdpscreen.validation.ReflectionValidator} re-checks every one of
 * them at startup rather than waiting for a black screen:</p>
 *
 * <pre>
 *   static boolean isInitialized()
 *   static void    scheduleForInit(RinkuInitListener)
 *   static Object  createBrowser(String, boolean, int, int)
 *   static void    shutdown()
 *   static Object  getClient()
 * </pre>
 *
 * <h2>Initialisation state</h2>
 * <p>{@code isInitialized()} reports true only once Chrome is reachable over
 * DevTools. Reporting it early is the one failure mode that is invisible: the
 * mod's {@code scheduleInit} short-circuits on it and immediately asks for a
 * browser that cannot exist yet.</p>
 */
public final class Rinku {

    /** Mod id WebDisplays checks for with {@code ModList.isLoaded("rinku")}. */
    public static final String MOD_ID = "rinku";

    /** Version string. Must satisfy WebDisplays' {@code [3.0.4,)} range. */
    public static final String VERSION = "3.1.0-cdp.1.0.0";

    private static final Logger LOG = Logger.getLogger("rinku");

    /**
     * Kept because {@code BrowserHelper.isInitialized()} reads it by name as a
     * fallback path. The field must exist and its object must expose a readable
     * {@code state} field whose string form is {@code "INITIALIZED"} when ready.
     */
    private static final InitializationController INITIALIZATION_CONTROLLER =
            new InitializationController();

    /** Listeners waiting for the current boot, if one is in flight. */
    private static final List<RinkuInitListener> AWAITING_INIT = new CopyOnWriteArrayList<>();

    /** Bumped per boot, so a stale completion cannot report success for a newer one. */
    private static final AtomicReference<String> LAST_FAILURE = new AtomicReference<>("");

    private Rinku() {
    }

    /**
     * Mirror of Rinku's own controller, present only so the field-read fallback in
     * {@code BrowserHelper.isInitialized()} keeps working.
     *
     * <p>The {@code state} field is read reflectively by name and compared as
     * {@code "INITIALIZED"}, so its type is an enum whose constant name is exactly
     * that.</p>
     */
    private static final class InitializationController {
        @SuppressWarnings("unused")
        private volatile State state = State.UNINITIALIZED;
    }

    /** State names must match Rinku's, since they are compared as strings. */
    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        FAILED
    }

    // ------------------------------------------------------------------- API

    /**
     * True when a browser can be created right now.
     *
     * <p>Backed by the real backend state, not by a flag set at the start of
     * booting - see the class comment.</p>
     */
    public static boolean isInitialized() {
        CdpBackend backend = CdpBackend.get();
        var state = backend.state();
        boolean ready = backend.isInitialized();
        INITIALIZATION_CONTROLLER.state = ready ? State.INITIALIZED
                : state == CdpBackend.State.INITIALIZED ? State.FAILED : State.valueOf(state.name());
        return ready;
    }

    /**
     * Boot the backend and call {@code listener.onInit(true)} when it is ready.
     *
     * <p>WebDisplays calls this once from client setup. It is asynchronous by
     * contract: Chrome takes a few hundred milliseconds to open a DevTools port,
     * and blocking the setup event on that would stall the loading screen. The
     * callback is invoked exactly once, on the boot thread.</p>
     *
     * <p>A second call while a boot is in flight joins the same boot rather than
     * starting another Chrome - two browsers would double memory and fight over
     * the profile directory.</p>
     */
    public static void scheduleForInit(RinkuInitListener listener) {
        if (listener == null) {
            LOG.warning("scheduleForInit called with a null listener");
            return;
        }
        if (isInitialized()) {
            // Already up (second world, or a second screen): answer immediately
            // instead of waiting for a callback that will never come.
            listener.onInit(true);
            return;
        }

        AWAITING_INIT.add(listener);

        CdpBackend backend = CdpBackend.get();
        if (backend.state() == CdpBackend.State.INITIALIZING) {
            // Join the boot already in flight; its completion notifies everyone.
            return;
        }

        INITIALIZATION_CONTROLLER.state = State.INITIALIZING;
        backend.start().whenComplete((ok, error) -> {
            boolean success = Boolean.TRUE.equals(ok) && backend.isInitialized();
            INITIALIZATION_CONTROLLER.state = success ? State.INITIALIZED : State.FAILED;
            if (!success) {
                LAST_FAILURE.set(error == null ? "browser did not start"
                        : String.valueOf(error.getMessage()));
                LOG.severe("Browser backend failed to initialise: " + LAST_FAILURE.get());
            } else {
                LOG.info("Browser backend initialised (CDP, " + VERSION + ")");
            }
            notifyWaiters(success);
        });
    }

    private static void notifyWaiters(boolean success) {
        for (RinkuInitListener listener : AWAITING_INIT) {
            AWAITING_INIT.remove(listener);
            try {
                listener.onInit(success);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "An init listener threw", e);
            }
        }
    }

    /**
     * Create a browser object for a screen.
     *
     * <p>Returns immediately with an object whose texture identifier is already
     * valid; the underlying page attaches asynchronously. Returns null only when
     * the backend is not initialised, which WebDisplays already guards against -
     * returning a half-built object instead would hide the real problem.</p>
     *
     * @param url         initial page; WebDisplays passes {@code about:blank} and
     *                    navigates afterwards
     * @param transparent whether the page should composite over the world
     */
    public static Object createBrowser(String url, boolean transparent, int width, int height) {
        CdpBackend backend = CdpBackend.get();
        if (!backend.isInitialized()) {
            LOG.warning("createBrowser called before the backend is ready (state="
                    + backend.state() + ")");
            return null;
        }

        CdpBrowser browser = CdpBrowser.create(backend, transparent, width, height);
        if (url != null && !url.isBlank()) {
            browser.loadURL(url);
        }
        return browser;
    }

    /**
     * Stop Chrome.
     *
     * <p>Called when leaving a world for good. Unlike Rinku's CEF shutdown this is
     * safe to call at any time: Chrome is a separate process, so nothing here can
     * corrupt the game's own state.</p>
     */
    public static void shutdown() {
        AWAITING_INIT.clear();
        INITIALIZATION_CONTROLLER.state = State.UNINITIALIZED;
        try {
            CdpBackend.get().shutdown();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Browser shutdown failed", e);
        }
    }

    /**
     * The client object, or null.
     *
     * <p>Only {@code BrowserHelper.installAudioHandler()} calls this, and it
     * returns early on null. This backend has no audio at all - CDP's screencast
     * carries video only - so null is the honest answer and the audio path stays
     * disabled instead of half-working.</p>
     */
    public static Object getClient() {
        return null;
    }

    // --------------------------------------------------------- introspection

    /** Extra diagnostics; not part of the reflection contract. */
    public static String describe() {
        return CdpBackend.get().describe();
    }

    /** Last startup failure message, or empty. */
    public static String lastFailure() {
        return LAST_FAILURE.get();
    }

    /** Live browser count, for the leak check. */
    public static int liveBrowsers() {
        return CdpBrowser.liveCount();
    }

    /** Convenience for tools: boot synchronously. */
    public static boolean initializeBlocking(long timeoutMillis) {
        try {
            return CdpBackend.get().start().get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            CdpLog.warn("Blocking init failed: " + e.getMessage(), e);
            return false;
        }
    }
}
