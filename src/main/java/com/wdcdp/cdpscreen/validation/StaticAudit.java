package com.wdcdp.cdpscreen.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stage 0C: static audit of the source, for the bug classes that have already
 * happened once.
 *
 * <p>Every entry below is a defect that actually shipped in this project and
 * reached a running game, so the check exists to stop the same class coming back
 * rather than to enforce a style. The list is short on purpose - a check nobody
 * understands gets deleted.</p>
 *
 * <p>Runs on the source tree, needs no Minecraft and no Chrome, and exits
 * non-zero on a finding so it can gate a build.</p>
 */
public final class StaticAudit {

    private static int failures;
    private static final List<String> FINDINGS = new ArrayList<>();

    private StaticAudit() {
    }

    public static void main(String[] args) throws IOException {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of("src/main/java");
        System.out.println("=== static audit (" + root + ") ===");
        System.out.println();

        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }

        checkSharedMutableState(sources);
        checkGpuOffRenderThread(sources);
        checkUnboundedFanOut(sources);
        checkSilentCatches(sources);
        checkResourceOwnership(sources);

        System.out.println();
        System.out.println("=== " + (sources.size()) + " files, "
                + failures + " finding(s) ===");
        for (String finding : FINDINGS) {
            System.out.println("  - " + finding);
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- checks

    /**
     * A binding name that is also used as a state variable.
     *
     * <p>{@code Runtime.addBinding} installs a function on {@code window}; if the
     * page script later assigns its own object to the same name, the function is
     * gone and every call to it silently does nothing. This shipped: audio looked
     * installed and delivered zero blocks.</p>
     */
    private static void checkSharedMutableState(List<Path> sources) {
        section("binding / state name collisions");
        boolean checked = false;
        for (Path source : sources) {
            String name = source.getFileName().toString();
            // Skip this file: it contains the pattern it searches for.
            if (name.equals("StaticAudit.java")) {
                continue;
            }
            String text = read(source);
            if (!text.contains("BINDING =")) {
                continue;
            }
            checked = true;
            // Extract the binding's string literal and the window.<name> the script
            // stores its state on; they must differ.
            String binding = firstQuoted(text, "BINDING =");
            String state = null;
            int at = text.indexOf("window.__wdcdp");
            if (at >= 0) {
                int end = at;
                while (end < text.length()
                        && (Character.isLetterOrDigit(text.charAt(end))
                            || text.charAt(end) == '_' || text.charAt(end) == '.')) {
                    end++;
                }
                state = text.substring(at + "window.".length(), end);
            }
            if (binding != null && state != null && binding.equals(state)) {
                fail(source, "binding name '" + binding
                        + "' is also the script's state name - the binding function "
                        + "would be overwritten and every call would silently do nothing");
            } else if (binding != null) {
                pass(name + ": binding '" + binding + "' is distinct from state '"
                        + state + "'");
            }
        }
        if (!checked) {
            pass("no binding definitions to check");
        }
    }

    /** First double-quoted literal after {@code marker}. */
    private static String firstQuoted(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int open = text.indexOf('"', at);
        if (open < 0) {
            return null;
        }
        int close = text.indexOf('"', open + 1);
        return close < 0 ? null : text.substring(open + 1, close);
    }

    /**
     * GPU calls reachable from a non-render thread.
     *
     * <p>{@code TextureManager.register/release}, {@code createTexture} and
     * {@code glTexSubImage2D} may only run on the render thread. The browser object
     * is created from both the render thread and the client tick, so any of these
     * reached directly from a public entry point is a latent driver crash.</p>
     */
    private static void checkGpuOffRenderThread(List<Path> sources) {
        section("GPU calls guarded by a render-thread check");
        Path texture = null;
        for (Path source : sources) {
            if (source.getFileName().toString().equals("PageTexture.java")) {
                texture = source;
            }
        }
        if (texture == null) {
            fail(null, "PageTexture.java not found");
            return;
        }
        String text = read(texture);
        boolean registerGuarded = text.contains("isOnRenderThread")
                && text.indexOf("isOnRenderThread") < text.indexOf("getTextureManager().register");
        boolean closeGuarded = count(text, "isOnRenderThread") >= 2;
        if (registerGuarded) {
            pass("register() checks the render thread");
        } else {
            fail(texture, "register() touches the TextureManager without a thread check");
        }
        if (closeGuarded) {
            pass("close() checks the render thread");
        } else {
            fail(texture, "close() frees GPU state without a thread check");
        }
    }

    /**
     * One input applied to every instance instead of the one it belongs to.
     *
     * <p>An audio sink that looped over every live browser sent each page's blocks
     * to every open mixer line, so three screens played the same video three times
     * over each other. Fan-out is legitimate for aggregates (counters, diagnostics)
     * and wrong for anything per-object.</p>
     */
    private static void checkUnboundedFanOut(List<Path> sources) {
        section("per-object work inside an all-instances loop");
        boolean found = false;
        for (Path source : sources) {
            String text = read(source);
            if (!text.contains(": LIVE)")) {
                continue;
            }
            // Look for a per-object mutation inside such a loop. Aggregation and
            // diagnostics are fine; sending data to every instance is not.
            for (Pattern bad : List.of(
                    Pattern.compile("for \\(CdpBrowser \\w+ : LIVE\\)\\s*\\{[^}]*packetFloat"),
                    Pattern.compile("for \\(CdpBrowser \\w+ : LIVE\\)\\s*\\{[^}]*mixer\\.start"))) {
                if (bad.matcher(text).find()) {
                    fail(source, "per-page data sent to every browser inside a LIVE loop");
                    found = true;
                }
            }
        }
        if (!found) {
            pass("no per-object fan-out inside LIVE loops");
        }
    }

    /**
     * A catch block that discards its exception with no explanation.
     *
     * <p>Not a style rule: this project lost hours to a failure that was swallowed
     * and reported as "not found", so a silent catch must at least say why it is
     * safe.</p>
     */
    private static void checkSilentCatches(List<Path> sources) {
        section("swallowed exceptions");
        int silent = 0;
        for (Path source : sources) {
            String text = read(source);
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("catch (") && !line.startsWith("} catch (")) {
                    continue;
                }
                if (!line.endsWith("{")) {
                    continue;
                }
                // Look at the next non-blank line.
                for (int j = i + 1; j < Math.min(i + 3, lines.length); j++) {
                    String body = lines[j].trim();
                    if (body.isEmpty()) {
                        continue;
                    }
                    if (body.equals("}")) {
                        silent++;
                        FINDINGS.add(source.getFileName() + ":" + (i + 1)
                                + " catch block is empty");
                        failures++;
                    }
                    break;
                }
            }
        }
        if (silent == 0) {
            pass("every catch explains itself");
        }
    }

    /**
     * A GPU resource created without a matching release on the same path.
     *
     * <p>The texture is the only GPU allocation this mod makes, so it is enough to
     * confirm that {@code createTexture} appears in a class that also has a
     * {@code releaseGpuTexture}-style teardown.</p>
     */
    private static void checkResourceOwnership(List<Path> sources) {
        section("GPU resource teardown");
        for (Path source : sources) {
            String name = source.getFileName().toString();
            // This file mentions createTexture as a string it searches for.
            if (name.equals("StaticAudit.java")) {
                continue;
            }
            String text = read(source);
            if (!text.contains("createTexture")) {
                continue;
            }
            boolean hasTeardown = text.contains("releaseGpuTexture")
                    || text.contains(".close()");
            if (hasTeardown) {
                pass(name + " creates and tears down GPU textures");
            } else {
                fail(source, "creates a GPU texture with no teardown path");
            }
        }
    }

    // ----------------------------------------------------------------- utils

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            total++;
            at = haystack.indexOf(needle, at + 1);
        }
        return total;
    }

    private static void section(String title) {
        System.out.println("-- " + title + " --");
    }

    private static void pass(String message) {
        System.out.println("  PASS  " + message);
    }

    private static void fail(Path where, String message) {
        failures++;
        String line = (where == null ? "" : where.getFileName() + ": ") + message;
        FINDINGS.add(line);
        System.out.println("  FAIL  " + line);
    }
}
