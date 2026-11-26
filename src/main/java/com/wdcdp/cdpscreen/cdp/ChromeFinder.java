package com.wdcdp.cdpscreen.cdp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates a Chromium-based browser that can actually decode H.264.
 *
 * <p>This is the entire point of the project: Rinku's bundled CEF 151 ships
 * without H.264 ({@code H264Decoder} symbol count 0), so Bilibili and Douyin
 * refuse to start a player. Google Chrome 157 has it (count 8). We therefore do
 * not bundle a browser - we borrow the one already installed.</p>
 *
 * <p>Override with {@code -Dwdcdp.chrome=<path>} when the automatic search picks
 * the wrong binary.</p>
 */
public final class ChromeFinder {

    /** System property naming an explicit browser executable. */
    public static final String OVERRIDE_PROPERTY = "wdcdp.chrome";

    private ChromeFinder() {
    }

    /** A browser we are willing to drive. */
    public record Found(Path path, String name) {
        @Override
        public String toString() {
            return name + " (" + path + ")";
        }
    }

    /** Environment variable that also overrides the search. */
    private static final String OVERRIDE_ENV = "WDCDP_CHROME";

    /**
     * Find a usable browser.
     *
     * <p>Chrome is preferred over Edge on purpose: both are Chromium 157 here and
     * both have H.264, but Chrome's headless mode is the one the frame-rate
     * measurements in the design document were taken with.</p>
     */
    public static Optional<Found> find() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(OVERRIDE_ENV);
        }
        if (override != null && !override.isBlank()) {
            Path explicit = Path.of(override);
            if (Files.isRegularFile(explicit)) {
                return Optional.of(new Found(explicit, "Chrome (configured)"));
            }
            // An explicit override that does not exist is a configuration error,
            // not a reason to silently fall back to something else.
            return Optional.empty();
        }

        for (Candidate candidate : CANDIDATES) {
            for (Path root : candidate.roots()) {
                Path executable = root.resolve(candidate.relative());
                if (Files.isRegularFile(executable)) {
                    return Optional.of(new Found(executable, candidate.name()));
                }
            }
        }
        return Optional.empty();
    }

    private record Candidate(String name, List<Path> roots, String relative) {
    }

    private static final String CHROME_RELATIVE =
            "Google\\Chrome\\Application\\chrome.exe";
    private static final String EDGE_RELATIVE =
            "Microsoft\\Edge\\Application\\msedge.exe";

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("Google Chrome", programRoots(), CHROME_RELATIVE),
            new Candidate("Microsoft Edge", programRoots(), EDGE_RELATIVE));

    /** Every place a per-machine or per-user install can land on Windows. */
    private static List<Path> programRoots() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");

        java.util.ArrayList<Path> roots = new java.util.ArrayList<>(4);
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(Path.of(localAppData));
        }
        if (programFiles != null && !programFiles.isBlank()) {
            roots.add(Path.of(programFiles));
        }
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            roots.add(Path.of(programFilesX86));
        }
        return List.copyOf(roots);
    }

    /** Human-readable diagnosis for the "no browser" failure path. */
    public static String describeSearch() {
        StringBuilder out = new StringBuilder("looked for:");
        for (Candidate candidate : CANDIDATES) {
            for (Path root : candidate.roots()) {
                out.append(System.lineSeparator())
                        .append("  ")
                        .append(root.resolve(candidate.relative()));
            }
        }
        return out.toString();
    }
}
