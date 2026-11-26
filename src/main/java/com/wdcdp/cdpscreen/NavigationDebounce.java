package com.wdcdp.cdpscreen;

/**
 * Decides when a navigation request is actually worth a page load.
 *
 * <p>WebDisplays reports a screen's URL whenever its configuration syncs, and the
 * client also re-reads it on a timer. A burst of identical or rapidly changing
 * reports used to become a burst of real navigations, and every navigation throws
 * away the page: the picture blanks, the video has to start over, the audio graph
 * is rebuilt, and Chrome spins up a renderer for each abandoned load. That is what
 * "jumping pages stutters" looked like from the outside.</p>
 *
 * <p>Two rules fix it, and both are cheap:</p>
 * <ul>
 *   <li><b>Ignore what is already showing.</b> A request for the page that is
 *       currently displayed, or one repeated within a few seconds, is not a
 *       navigation - it is the same report arriving twice.</li>
 *   <li><b>Coalesce a burst.</b> Requests inside the debounce window are held and
 *       only the newest survives, so a screen that is switched A -&gt; B -&gt; C in
 *       quick succession loads C once instead of loading A, B and C.</li>
 * </ul>
 *
 * <p>Pure state and time as a parameter, so the behaviour is testable without a
 * browser, a clock or a game.</p>
 */
public final class NavigationDebounce {

    /** What the caller should do with a request. */
    public enum Decision {
        /** Navigate with the URL returned by {@link #take(long)}. */
        NOW,
        /** Held; {@link #take(long)} will return it once the window is quiet. */
        LATER,
        /** Nothing to do - the page already shows this. */
        IGNORE
    }

    /** Requests closer together than this are treated as one burst. */
    private static final long WINDOW_NANOS = 400_000_000L;

    /** A repeat of the same URL inside this window is the same report. */
    private static final long REPEAT_HOLD_NANOS = 3_000_000_000L;

    private final long windowNanos;
    private final long repeatHoldNanos;

    private String lastTarget = "";
    private long lastAt;
    private boolean navigated;
    private String pending;
    private long pendingAt;

    public NavigationDebounce() {
        this(WINDOW_NANOS, REPEAT_HOLD_NANOS);
    }

    NavigationDebounce(long windowNanos, long repeatHoldNanos) {
        this.windowNanos = Math.max(0L, windowNanos);
        this.repeatHoldNanos = Math.max(0L, repeatHoldNanos);
    }

    /**
     * Offer a navigation request.
     *
     * @param target   the URL being requested
     * @param liveUrl  the URL the page is showing (empty if unknown)
     * @param loading  whether that page is still loading
     * @param now      monotonic time in nanoseconds
     */
    public Decision request(String target, String liveUrl, boolean loading, long now) {
        if (target == null || target.isBlank()) return Decision.IGNORE;
        String wanted = canonical(target);
        boolean blank = isBlankPage(target);
        // Unloading is never delayed: a removed screen should stop running its page
        // immediately, and there is nothing to coalesce.
        if (blank) {
            pending = null;
            lastTarget = wanted;
            lastAt = now;
            navigated = true;
            return Decision.NOW;
        }
        // Already the page's address - whether it has finished loading or not.
        // Re-navigating to a page that is still loading restarts it from scratch,
        // and a caller that reports the URL every tick would then never let any
        // page finish loading at all.
        if (!blank && wanted.equals(canonical(liveUrl))) {
            return Decision.IGNORE;
        }
        if (!blank && wanted.equals(lastTarget) && now - lastAt < repeatHoldNanos) {
            return Decision.IGNORE;
        }
        if (navigated && now - lastAt < windowNanos) {
            pending = target;
            pendingAt = now;
            return Decision.LATER;
        }
        lastTarget = wanted;
        lastAt = now;
        navigated = true;
        pending = null;
        return Decision.NOW;
    }

    /**
     * Take the pending URL if the quiet window has elapsed.
     *
     * @return the URL to load now, or {@code null} when it must wait
     */
    public String take(long now) {
        String target = pending;
        if (target == null) return null;
        if (now - pendingAt < windowNanos) return null;
        pending = null;
        lastTarget = canonical(target);
        lastAt = now;
        navigated = true;
        return target;
    }

    /** True while a request is waiting for the window to go quiet. */
    public boolean hasPending() {
        return pending != null;
    }

    private static boolean isBlankPage(String url) {
        return url.isBlank() || "about:blank".equals(url);
    }

    /**
     * Compare two URLs ignoring what the site adds on every visit.
     *
     * <p>Only the fragment and a trailing slash are dropped: query parameters are
     * significant (a search page differs by {@code keyword}), so removing them
     * would suppress a real navigation.</p>
     */
    public static String canonical(String url) {
        if (url == null) return "";
        String text = url.trim();
        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
