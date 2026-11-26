package com.wdcdp.cdpscreen;

/**
 * Holds the game's audio mixer so pages can control their own audio line.
 *
 * <p>{@code BrowserAudio} lives in WebDisplays, and this mod must keep working
 * without a compile-time dependency on it - that keeps this repository buildable
 * on its own, and keeps it usable with any WebDisplays version that has the same
 * method names. The mixer is therefore held as {@link Object} and reached through
 * reflection, exactly like WebDisplays reaches this mod.</p>
 *
 * <p>Without this the page had no way to reach the mixer, so closing a screen left
 * its output line open forever - silent, but still holding a device line, and
 * accumulating with every screen built and broken.</p>
 */
public final class CdpAudioMixer {

    private static volatile Object mixer;

    private CdpAudioMixer() {
    }

    /** Record the mixer. Called once, when WebDisplays' audio is available. */
    public static void set(Object instance) {
        mixer = instance;
    }

    /** The mixer, or null when WebDisplays' audio is not available. */
    public static Object get() {
        return mixer;
    }

    /** True when a mixer has been recorded. */
    public static boolean isPresent() {
        return mixer != null;
    }

    /**
     * Ask the mixer to release the output line belonging to one page.
     *
     * @param target the mixer returned by {@link #get()}
     * @param pageId the id passed to the audio sink for that page
     */
    public static void stop(Object target, int pageId) {
        invoke(target, "stop", pageId);
    }

    /**
     * Drop anything the mixer has queued for one page, so a pause does not leave
     * a tail playing after the picture has stopped.
     *
     * @param target the mixer returned by {@link #get()}
     * @param pageId the id passed to the audio sink for that page
     */
    public static void flush(Object target, int pageId) {
        invoke(target, "flush", pageId);
    }

    /**
     * Call {@code name(int)} on the mixer.
     *
     * <p>A mixer without the method is an older or newer WebDisplays. That is not
     * fatal - the line then stays open until the browser closes - so this warns
     * once per missing method instead of throwing on a render-thread path.</p>
     */
    private static void invoke(Object target, String name, int pageId) {
        if (target == null) {
            return;
        }
        try {
            target.getClass().getMethod(name, int.class).invoke(target, pageId);
        } catch (NoSuchMethodException e) {
            if (warned.add(name)) {
                CdpLog.warn("The audio mixer has no " + name
                        + "(int); page audio may outlive its page", e);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            CdpLog.warn("Could not call the mixer's " + name + " for page " + pageId, e);
        }
    }

    /** Method names already reported missing, so the log stays readable. */
    private static final java.util.Set<String> warned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
}
