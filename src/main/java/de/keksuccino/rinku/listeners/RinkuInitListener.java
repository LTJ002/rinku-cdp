package de.keksuccino.rinku.listeners;

/**
 * Callback for {@code Rinku.scheduleForInit}.
 *
 * <p>WebDisplays builds this with {@code Class.forName} and
 * {@code Proxy.newProxyInstance}, then hands the proxy to
 * {@code Rinku.scheduleForInit(RinkuInitListener)} via
 * {@code getMethod("scheduleForInit", RinkuInitListener.class)}. That lookup is
 * an <b>exact</b> parameter match, so this interface must exist under this exact
 * binary name and the method must be exactly {@code void onInit(boolean)}.
 * Anything else makes {@code BrowserHelper.method()} return null and the screen
 * stays black with a single warning line.</p>
 *
 * <p>It is a functional interface on purpose: that is what lets the
 * {@code Proxy} handler see a single, unambiguous {@code onInit} invocation.</p>
 */
@FunctionalInterface
public interface RinkuInitListener {

    /**
     * @param success true when the browser runtime is up and
     *                {@code Rinku.createBrowser} can be expected to succeed
     */
    void onInit(boolean success);
}
