package com.wdcdp.cdpscreen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Small typed accessors over Gson's tree model.
 *
 * <p>Every CDP response is untyped JSON, and a missing field is normal rather
 * than exceptional (a command may omit anything it has nothing to say about).
 * Reading through these helpers keeps the call sites free of {@code has()} /
 * {@code isJsonNull()} chains, and gives one place to decide the default.</p>
 */
public final class CdpJson {

    private CdpJson() {
    }

    /** Build a params object from alternating key/value pairs. */
    public static JsonObject obj(Object... keyValues) {
        JsonObject out = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                continue;
            }
            if (value instanceof String s) {
                out.addProperty(key, s);
            } else if (value instanceof Number n) {
                out.addProperty(key, n);
            } else if (value instanceof Boolean b) {
                out.addProperty(key, b);
            } else if (value instanceof JsonElement e) {
                out.add(key, e);
            } else {
                out.addProperty(key, String.valueOf(value));
            }
        }
        return out;
    }

    /** The named child object, or an empty object when absent or not an object. */
    public static JsonObject child(JsonObject parent, String name) {
        if (parent == null) {
            return new JsonObject();
        }
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        return element.getAsJsonObject();
    }

    /** The named child array, or an empty array when absent or not an array. */
    public static JsonArray array(JsonObject parent, String name) {
        if (parent == null) {
            return new JsonArray();
        }
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonArray()) {
            return new JsonArray();
        }
        return element.getAsJsonArray();
    }

    public static String string(JsonObject parent, String name, String fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int integer(JsonObject parent, String name, int fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static double number(JsonObject parent, String name, double fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject parent, String name, boolean fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
