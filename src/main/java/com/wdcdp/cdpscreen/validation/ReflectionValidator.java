package com.wdcdp.cdpscreen.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.keksuccino.rinku.listeners.RinkuCursorChangeListener;
import de.keksuccino.rinku.listeners.RinkuInitListener;

/**
 * Stage 0A: prove the reflection contract before any of it is relied on.
 *
 * <p>Every call WebDisplays makes into the browser backend goes through
 * {@code BrowserHelper.method()}, which is
 * {@code getMethod(name, exactParamTypes...)}. When that lookup fails it logs one
 * warning and returns null, the call site skips, and the screen is simply black.
 * There is no exception, no stack trace, and nothing to grep for except a line
 * that says "not found".</p>
 *
 * <p>This class checks the required entry points plus three things {@code getMethod}
 * alone does not catch: class visibility, method visibility, and exact return types. It runs
 * with no Minecraft, no Chrome and no GL, which is what makes it a two-minute
 * check instead of a two-hour one.</p>
 *
 * <p>Run it directly: {@code java -cp <classes> com.wdcdp.cdpscreen.validation.ReflectionValidator}.
 * It exits non-zero when anything fails, so it can gate a build.</p>
 */
public final class ReflectionValidator {

    private static final String RINKU = "de.keksuccino.rinku.Rinku";
    private static final String BROWSER = "com.wdcdp.cdpscreen.CdpBrowser";

    private static int passed;
    private static int failed;
    private static final List<String> FAILURES = new ArrayList<>();

    private ReflectionValidator() {
    }

    public static void main(String[] args) {
        System.out.println("=== WebDisplays reflection contract (stage 0A) ===");
        System.out.println();

        checkClass(RINKU);
        checkStatics();
        checkListeners();
        checkBrowser();

        System.out.println();
        report();

        if (failed > 0) {
            System.exit(1);
        }
    }

    // --------------------------------------------------------------- sections

    private static void checkStatics() {
        section("Rinku static methods");
        check(RINKU, "isInitialized", boolean.class);
        check(RINKU, "scheduleForInit", void.class, RinkuInitListener.class);
        check(RINKU, "createBrowser", Object.class, String.class, boolean.class,
                int.class, int.class);
        check(RINKU, "shutdown", void.class);
        check(RINKU, "getClient", Object.class);
    }

    private static void checkListeners() {
        section("Listener interfaces");
        checkInterface("de.keksuccino.rinku.listeners.RinkuInitListener",
                "onInit", void.class, boolean.class);
        checkInterface("de.keksuccino.rinku.listeners.RinkuCursorChangeListener",
                "onCursorChange", void.class, int.class);
    }

    private static void checkBrowser() {
        section("Browser instance methods");
        check(BROWSER, "getTextureIdentifier", net.minecraft.resources.Identifier.class);
        check(BROWSER, "isTextureReady", boolean.class);
        check(BROWSER, "loadURL", void.class, String.class);
        check(BROWSER, "getURL", String.class);
        check(BROWSER, "resize", void.class, int.class, int.class);
        check(BROWSER, "sendMousePress", void.class, int.class, int.class, int.class);
        check(BROWSER, "sendMouseRelease", void.class, int.class, int.class, int.class);
        check(BROWSER, "sendMouseMove", void.class, int.class, int.class);
        check(BROWSER, "sendMouseWheel", void.class, int.class, int.class,
                double.class, int.class);
        check(BROWSER, "sendKeyPress", void.class, int.class, long.class, int.class);
        check(BROWSER, "sendKeyRelease", void.class, int.class, long.class, int.class);
        check(BROWSER, "sendKeyTyped", void.class, char.class, int.class);
        check(BROWSER, "sendGlfwKeyPress", void.class, int.class, long.class, int.class);
        check(BROWSER, "sendGlfwKeyRelease", void.class, int.class, long.class, int.class);
        check(BROWSER, "sendText", void.class, String.class);
        check(BROWSER, "focusForInput", void.class);
        check(BROWSER, "exitFullscreen", java.util.concurrent.CompletableFuture.class);
        check(BROWSER, "setWallShape", void.class, int.class, int.class);
        check(BROWSER, "getTextureSize", int[].class);
        check(BROWSER, "getTexturePresentation", int[].class);
        check(BROWSER, "executeJavaScript", void.class, String.class, String.class,
                int.class);
        check(BROWSER, "setCursorChangeListener", void.class,
                RinkuCursorChangeListener.class);
    }

    // ---------------------------------------------------------------- checks

    private static void checkClass(String className) {
        section("Class visibility");
        try {
            Class<?> type = Class.forName(className);
            if (Modifier.isPublic(type.getModifiers())) {
                pass(className + " is public");
            } else {
                fail(className, "class is not public, so cross-package invoke fails");
            }
        } catch (ClassNotFoundException e) {
            fail(className, "class not found");
        }
    }

    /**
     * Check one method the way {@code getMethod} would, plus visibility and the
     * exact return type.
     */
    private static void check(String owner, String name, Class<?> returnType,
                              Class<?>... params) {
        String label = owner + "#" + name;
        Class<?> type;
        try {
            type = Class.forName(owner);
        } catch (ClassNotFoundException e) {
            fail(label, "class not found");
            return;
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            fail(label, "class is not public");
            return;
        }
        Method method;
        try {
            method = type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail(label, "not found with signature " + signature(params)
                    + " - BrowserHelper would log a warning and skip the call");
            return;
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            fail(label, "method is not public");
            return;
        }
        if (Modifier.isStatic(method.getModifiers()) != isStaticExpected(owner)) {
            fail(label, "static-ness does not match how it is invoked");
            return;
        }
        if (method.getReturnType() != returnType) {
            fail(label, "returns " + method.getReturnType().getName()
                    + ", contract says " + returnType.getName());
            return;
        }
        pass(label + " " + signature(params) + " -> " + returnType.getSimpleName());
    }

    /** Rinku's methods are static; the browser's are not. */
    private static boolean isStaticExpected(String owner) {
        return RINKU.equals(owner);
    }

    /** Listener methods must exist on an interface, exactly as proxies expect. */
    private static void checkInterface(String className, String methodName,
                                       Class<?> returnType, Class<?>... params) {
        String label = className + "#" + methodName;
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail(label, "class not found");
            return;
        }
        if (!type.isInterface()) {
            fail(label, "is not an interface - a dynamic proxy needs one");
            return;
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            fail(label, "interface is not public");
            return;
        }
        try {
            Method method = type.getMethod(methodName, params);
            if (method.getReturnType() != returnType) {
                fail(label, "returns " + method.getReturnType().getName()
                        + ", contract says " + returnType.getName());
                return;
            }
            pass(label + " " + signature(params) + " -> " + returnType.getSimpleName());
        } catch (NoSuchMethodException e) {
            fail(label, "not found with signature " + signature(params));
        }
    }

    private static String signature(Class<?>[] params) {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(params[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    // ----------------------------------------------------------------- output

    private static void section(String title) {
        System.out.println("-- " + title + " --");
    }

    private static void pass(String message) {
        passed++;
        System.out.println("  PASS  " + message);
    }

    private static void fail(String label, String reason) {
        failed++;
        String line = label + ": " + reason;
        FAILURES.add(line);
        System.out.println("  FAIL  " + line);
    }

    private static void report() {
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (!FAILURES.isEmpty()) {
            System.out.println();
            System.out.println("Failures:");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
        }
    }

    /** Total checks performed; used by the build gate. */
    public static int totalChecks() {
        return passed + failed;
    }
}
