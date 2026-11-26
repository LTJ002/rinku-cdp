package de.keksuccino.rinku.listeners;

/**
 * Callback for {@code browser.setCursorChangeListener}.
 *
 * <p>Same contract note as {@link RinkuInitListener}: WebDisplays resolves the
 * setter with {@code getMethod("setCursorChangeListener",
 * RinkuCursorChangeListener.class)}, so the parameter type must be this exact
 * interface. Passing {@code Object} throws {@code NoSuchMethodException} and the
 * page cursor silently stops updating.</p>
 *
 * <p>The {@code int} is a CEF cursor id, not a GLFW one. WebDisplays only uses
 * it to decide which glyph to draw on the screen surface - it never wants the
 * system cursor changed, because the page is rendered inside the world.</p>
 */
@FunctionalInterface
public interface RinkuCursorChangeListener {

    /** @param cefCursorType the page's current CEF cursor id (CT_* constants) */
    void onCursorChange(int cefCursorType);
}
