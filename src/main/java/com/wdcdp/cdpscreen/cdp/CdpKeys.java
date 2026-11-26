package com.wdcdp.cdpscreen.cdp;

import java.util.Map;

/**
 * Key descriptions for CDP, derived separately from Minecraft's GLFW key IDs
 * and from the Windows virtual-key codes used by the CEF-compatible API.
 *
 * <p>For printable keys, {@code key} is a US-layout approximation for DOM
 * keyboard listeners. The actual character comes from a separate character
 * callback, so generating text from the physical key would double-type or
 * substitute the wrong letter on another layout.</p>
 */
public final class CdpKeys {

    public record Key(int windowsVirtualKeyCode, String key, String code, int location) {
    }

    private CdpKeys() {
    }

    /** Physical-key names for the punctuation and control keys. */
    private static final Map<Integer, String> CODES = Map.ofEntries(
            Map.entry(0x08, "Backspace"),
            Map.entry(0x09, "Tab"),
            Map.entry(0x0D, "Enter"),
            Map.entry(0x13, "Pause"),
            Map.entry(0x14, "CapsLock"),
            Map.entry(0x1B, "Escape"),
            Map.entry(0x20, "Space"),
            Map.entry(0x21, "PageUp"),
            Map.entry(0x22, "PageDown"),
            Map.entry(0x23, "End"),
            Map.entry(0x24, "Home"),
            Map.entry(0x25, "ArrowLeft"),
            Map.entry(0x26, "ArrowUp"),
            Map.entry(0x27, "ArrowRight"),
            Map.entry(0x28, "ArrowDown"),
            Map.entry(0x2C, "PrintScreen"),
            Map.entry(0x2D, "Insert"),
            Map.entry(0x2E, "Delete"),
            Map.entry(0x5D, "ContextMenu"),
            Map.entry(0x90, "NumLock"),
            Map.entry(0x91, "ScrollLock"),
            Map.entry(0xAD, "AudioVolumeMute"),
            Map.entry(0xAE, "AudioVolumeDown"),
            Map.entry(0xAF, "AudioVolumeUp"),
            Map.entry(0xBA, "Semicolon"),
            Map.entry(0xBB, "Equal"),
            Map.entry(0xBC, "Comma"),
            Map.entry(0xBD, "Minus"),
            Map.entry(0xBE, "Period"),
            Map.entry(0xBF, "Slash"),
            Map.entry(0xC0, "Backquote"),
            Map.entry(0xDB, "BracketLeft"),
            Map.entry(0xDC, "Backslash"),
            Map.entry(0xDD, "BracketRight"),
            Map.entry(0xDE, "Quote"));

    /** Unshifted US characters, used for {@code key} when no better value exists. */
    private static final Map<Integer, String> CHARS = Map.ofEntries(
            Map.entry(0xBA, ";"), Map.entry(0xBB, "="), Map.entry(0xBC, ","),
            Map.entry(0xBD, "-"), Map.entry(0xBE, "."), Map.entry(0xBF, "/"),
            Map.entry(0xC0, "`"), Map.entry(0xDB, "["), Map.entry(0xDC, "\\"),
            Map.entry(0xDD, "]"), Map.entry(0xDE, "'"),
            Map.entry(0x20, " "));

    /** Non-ASCII GLFW IDs are distinct from the VK values used by the compatibility API. */
    public static Key describeGlfw(int glfwKey, int modifiers) {
        if (glfwKey >= 290 && glfwKey <= 313) {
            return describe(glfwKey - 290 + 0x70, modifiers);
        }
        if (glfwKey >= 320 && glfwKey <= 329) {
            return describe(glfwKey - 320 + 0x60, modifiers);
        }
        if (glfwKey == 335) {
            return new Key(0x0D, "Enter", "NumpadEnter", 3);
        }
        if (glfwKey == 336) {
            return new Key(0xBB, "=", "NumpadEqual", 3);
        }
        int vk = switch (glfwKey) {
            case 39 -> 0xDE; // Apostrophe
            case 44 -> 0xBC; // Comma
            case 45 -> 0xBD; // Minus
            case 46 -> 0xBE; // Period
            case 47 -> 0xBF; // Slash
            case 59 -> 0xBA; // Semicolon
            case 61 -> 0xBB; // Equal
            case 91 -> 0xDB; // Left bracket
            case 92 -> 0xDC; // Backslash
            case 93 -> 0xDD; // Right bracket
            case 96 -> 0xC0; // Grave accent
            case 256 -> 0x1B; // Escape
            case 257 -> 0x0D; // Enter
            case 258 -> 0x09; // Tab
            case 259 -> 0x08; // Backspace
            case 260 -> 0x2D; // Insert
            case 261 -> 0x2E; // Delete
            case 262 -> 0x27; // Right
            case 263 -> 0x25; // Left
            case 264 -> 0x28; // Down
            case 265 -> 0x26; // Up
            case 266 -> 0x21; // Page up
            case 267 -> 0x22; // Page down
            case 268 -> 0x24; // Home
            case 269 -> 0x23; // End
            case 280 -> 0x14; // Caps Lock
            case 281 -> 0x91; // Scroll Lock
            case 282 -> 0x90; // Num Lock
            case 283 -> 0x2C; // Print Screen
            case 284 -> 0x13; // Pause
            case 330 -> 0x6E; // Keypad decimal
            case 331 -> 0x6F; // Keypad divide
            case 332 -> 0x6A; // Keypad multiply
            case 333 -> 0x6D; // Keypad subtract
            case 334 -> 0x6B; // Keypad add
            case 340 -> 0xA0; // Left Shift
            case 341 -> 0xA2; // Left Control
            case 342 -> 0xA4; // Left Alt
            case 343 -> 0x5B; // Left Super
            case 344 -> 0xA1; // Right Shift
            case 345 -> 0xA3; // Right Control
            case 346 -> 0xA5; // Right Alt
            case 347 -> 0x5C; // Right Super
            case 348 -> 0x5D; // Menu
            default -> glfwKey == 32 || (glfwKey >= '0' && glfwKey <= '9')
                    || (glfwKey >= 'A' && glfwKey <= 'Z') ? glfwKey : -1;
        };
        return describe(vk, modifiers);
    }

    private static final Map<Integer, String> SHIFTED_CHARS = Map.ofEntries(
            Map.entry(0xBA, ":"), Map.entry(0xBB, "+"), Map.entry(0xBC, "<"),
            Map.entry(0xBD, "_"), Map.entry(0xBE, ">"), Map.entry(0xBF, "?"),
            Map.entry(0xC0, "~"), Map.entry(0xDB, "{"), Map.entry(0xDC, "|"),
            Map.entry(0xDD, "}"), Map.entry(0xDE, "\""));

    /** Describe a Windows VK key. Unknown keys cannot be dispatched accurately. */
    public static Key describe(int vk, int modifiers) {
        boolean shift = (modifiers & SHIFT) != 0;
        if (vk >= '0' && vk <= '9') {
            String digit = String.valueOf((char) vk);
            String key = shift ? String.valueOf(")!@#$%^&*(".charAt(vk - '0')) : digit;
            return new Key(vk, key, "Digit" + digit, 0);
        }
        if (vk >= 'A' && vk <= 'Z') {
            String letter = String.valueOf((char) vk);
            String key = shift ? letter : letter.toLowerCase(java.util.Locale.ROOT);
            return new Key(vk, key, "Key" + letter, 0);
        }
        if (vk >= 0x60 && vk <= 0x69) {
            String digit = String.valueOf(vk - 0x60);
            return new Key(vk, digit, "Numpad" + digit, 3);
        }
        if (vk >= 0x70 && vk <= 0x87) {
            String name = "F" + (vk - 0x6F);
            return new Key(vk, name, name, 0);
        }

        // Standard Windows VK codes are shared by the left and right modifiers.
        // The DOM code and location identify a side when the input provides it.
        return switch (vk) {
            case 0x10, 0xA0 -> new Key(0x10, "Shift", "ShiftLeft", 1);
            case 0xA1 -> new Key(0x10, "Shift", "ShiftRight", 2);
            case 0x11, 0xA2 -> new Key(0x11, "Control", "ControlLeft", 1);
            case 0xA3 -> new Key(0x11, "Control", "ControlRight", 2);
            case 0x12, 0xA4 -> new Key(0x12, "Alt", "AltLeft", 1);
            case 0xA5 -> new Key(0x12, "Alt", "AltRight", 2);
            case 0x5B -> new Key(vk, "Meta", "MetaLeft", 1);
            case 0x5C -> new Key(vk, "Meta", "MetaRight", 2);
            case 0x6A -> new Key(vk, "*", "NumpadMultiply", 3);
            case 0x6B -> new Key(vk, "+", "NumpadAdd", 3);
            case 0x6D -> new Key(vk, "-", "NumpadSubtract", 3);
            case 0x6E -> new Key(vk, ".", "NumpadDecimal", 3);
            case 0x6F -> new Key(vk, "/", "NumpadDivide", 3);
            default -> {
                String code = CODES.get(vk);
                if (code == null) yield null;
                String key = CHARS.getOrDefault(vk, code);
                yield new Key(vk, shift ? SHIFTED_CHARS.getOrDefault(vk, key) : key, code, 0);
            }
        };
    }

    /**
     * CDP's {@code modifiers} bitmask.
     *
     * <p>Note the ordering: Alt=1, Ctrl=2, Meta=4, Shift=8. This is <b>not</b> the
     * same as GLFW's or AWT's, and it is not what WebDisplays passes down, so it is
     * recomputed from the modifier set rather than forwarded.</p>
     */
    public static final int ALT = 1;
    public static final int CTRL = 2;
    public static final int META = 4;
    public static final int SHIFT = 8;

    /** CDP mouse button name for a CEF button id (0 left, 1 middle, 2 right). */
    public static String mouseButton(int cefButton) {
        return switch (cefButton) {
            case 0 -> "left";
            case 1 -> "middle";
            case 2 -> "right";
            default -> "none";
        };
    }

    /** CDP {@code buttons} bitmask while a CEF button is held. */
    public static int buttonMask(int cefButton) {
        return switch (cefButton) {
            case 0 -> 1;    // left
            case 1 -> 4;    // middle
            case 2 -> 2;    // right
            default -> 0;
        };
    }
}
