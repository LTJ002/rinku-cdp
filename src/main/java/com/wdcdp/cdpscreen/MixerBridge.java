package com.wdcdp.cdpscreen;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection bridge to WebDisplays' {@code BrowserAudio}.
 *
 * <p>This mod must compile and load without WebDisplays' classes on its classpath,
 * so every call into the mixer goes through here instead of a direct reference.
 * The mixer's own API is small and stable - one output line per page, keyed by the
 * browser id:</p>
 *
 * <pre>
 *   boolean isOpen(int id)
 *   void    startCdp(int id, int sampleRate, int channels)
 *   void    packetFloat(int id, float[][] planar, int frames)
 *   void    stop(int id)
 *   void    flush(int id)
 * </pre>
 *
 * <p>Missing methods are reported once and then treated as no-ops. A mixer that
 * lacks them is an older or newer WebDisplays, and audio degrading to silence is
 * much better than a wall that refuses to render.</p>
 */
public final class MixerBridge {

    /** Methods already reported missing, so the log stays readable. */
    private static final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

    private MixerBridge() {
    }

    /** True when the mixer reports an open line for this page. */
    public static boolean isOpen(Object mixer, int id) {
        Object result = call(mixer, "isOpen", new Class<?>[]{int.class}, id);
        return result instanceof Boolean open && open;
    }

    /** Open (or reopen) this page's output line at the page's own sample rate. */
    public static void startCdp(Object mixer, int id, int sampleRate, int channels) {
        call(mixer, "startCdp", new Class<?>[]{int.class, int.class, int.class},
                id, sampleRate, channels);
    }

    /** Hand one block of planar float samples to the mixer. */
    public static void packetFloat(Object mixer, int id, float[][] planar, int frames) {
        call(mixer, "packetFloat", new Class<?>[]{int.class, float[][].class, int.class},
                id, planar, frames);
    }

    /** Release this page's output line. */
    public static void stop(Object mixer, int id) {
        call(mixer, "stop", new Class<?>[]{int.class}, id);
    }

    /** Drop anything queued for this page. */
    public static void flush(Object mixer, int id) {
        call(mixer, "flush", new Class<?>[]{int.class}, id);
    }

    /** Invoke {@code name} on the mixer, returning its result or null. */
    private static Object call(Object mixer, String name, Class<?>[] types, Object... args) {
        if (mixer == null) {
            return null;
        }
        try {
            Method method = mixer.getClass().getMethod(name, types);
            return method.invoke(mixer, args);
        } catch (NoSuchMethodException e) {
            if (warned.add(name)) {
                CdpLog.warn("The WebDisplays audio mixer has no " + name
                        + "(); page audio may be silent", e);
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CdpLog.warn("The audio mixer's " + name + "() failed", e);
            return null;
        }
    }

    /** Report whether the mixer exposes a method, for diagnostics. */
    public static boolean has(Object mixer, String name, Class<?>... types) {
        if (mixer == null) {
            return false;
        }
        try {
            mixer.getClass().getMethod(name, types);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Set of method names this bridge has already warned about. */
    public static java.util.Set<String> warnedMethods() {
        return java.util.Set.copyOf(warned);
    }

    /** Guard used by callers that must not run a second install. */
    public static final AtomicBoolean INSTALLED = new AtomicBoolean();
}
