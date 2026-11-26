package com.wdcdp.cdpscreen.cdp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * One Chrome process and the DevTools endpoint it opened.
 *
 * <p>Chrome is started with {@code --remote-debugging-port=0} so the OS picks the
 * port; Chrome then writes the real port and the browser's WebSocket path into
 * {@code <profile>/DevToolsActivePort}. Reading that file is the only supported
 * way to discover the endpoint - scraping stderr is unreliable across versions.</p>
 *
 * <p>Every process is started in its own throwaway profile directory. Sharing one
 * profile would make a second instance attach to the first instead of starting,
 * and would let a stale singleton lock block startup after a crash.</p>
 */
public final class ChromeProcess implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("wdcdp.chrome");
    private static final String PORT_FILE = "DevToolsActivePort";

    private final Process process;
    private final URI endpoint;
    private final Path profile;
    private final Path logFile;

    /**
     * Every browser this JVM currently owns.
     *
     * <p>{@link #killByProfile} skips these. Without the guard, closing an old
     * instance could kill the browser that replaced it: the two can overlap on the
     * same profile, and the symptom is a browser that dies for no visible reason
     * with exit code 21.</p>
     */
    private static final java.util.Set<Long> LIVE_PIDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether this instance runs headless. */
    private final boolean headless;

    /**
     * Start a normal, visible Chrome on the same profile, for signing in.
     *
     * <p>Pages that require an account (Bilibili, YouTube, Twitch) show a login
     * dialog over the video, and hiding that dialog is only cosmetic - the page is
     * still logged out, so the dialog comes back on the site's own timer. The only
     * real fix is to sign in once, which needs a window the user can actually type
     * into. The profile is shared, so the cookies written here are the ones the
     * in-game browser then uses.</p>
     *
     * <p>The in-game browser must be closed first: a profile directory can only be
     * opened by one Chrome at a time.</p>
     *
     * @return the window process, or null when the backend has no profile yet
     */
    public static Process startSignInWindow(ChromeFinder.Found browser, Path profile, String url)
            throws IOException {
        Files.createDirectories(profile);
        // Clear anything still holding the profile, or the window will attach to it
        // invisibly instead of opening.
        killByProfile(profile);
        awaitProfileFree(profile, 5_000L);

        List<String> command = new ArrayList<>();
        command.add(browser.path().toString());
        command.add("--user-data-dir=" + profile.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--window-size=1100,800");
        command.add("--window-position=120,80");
        // Not muted: a sign-in page may need sound (captcha), and this is a normal
        // window the user is watching.
        command.add("--new-window");
        command.add(url == null || url.isBlank() ? "https://www.bilibili.com" : url);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private ChromeProcess(Process process, URI endpoint, Path profile, Path logFile,
                          boolean headless) {
        this.process = process;
        this.endpoint = endpoint;
        this.profile = profile;
        this.logFile = logFile;
        this.headless = headless;
    }

    /** Whether this instance runs headless. */
    public boolean isHeadless() {
        return headless;
    }

    public URI endpoint() {
        return endpoint;
    }

    public Path profile() {
        return profile;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Start a browser and wait for its DevTools endpoint.
     *
     * @param profileDir   throwaway profile directory (created if missing)
     * @param logDir       where to put {@code chrome.log}
     * @param headless     true for {@code --headless=new}; false renders offscreen
     * @param width        initial window width
     * @param height       initial window height
     * @param startupTimeoutMillis how long to wait for DevToolsActivePort
     */
    public static ChromeProcess start(ChromeFinder.Found browser, Path profileDir, Path logDir,
                                      boolean headless, int width, int height,
                                      long startupTimeoutMillis) throws IOException {
        Files.createDirectories(profileDir);

        // A previous session that crashed leaves its Chrome children behind, and a
        // profile directory can only be opened by one browser at a time: the next
        // launch then dies with exit code 21 before it ever prints anything. Clear
        // the squatters first rather than surfacing a bare exit code to the user.
        //
        // This runs strictly before our own browser is spawned, so there is no
        // process of ours to spare yet.
        int squatters = killByProfile(profileDir);
        if (squatters > 0) {
            LOG.warning("Cleared " + squatters + " browser process(es) still holding "
                    + profileDir.getFileName() + " from an earlier session");
            // destroyForcibly only *requests* termination. Chrome holds the
            // profile's singleton lock until the process is really gone, so the new
            // instance still fails with exit code 21 if it starts too early. Wait
            // for the processes to disappear instead of guessing a delay.
            awaitProfileFree(profileDir, 10_000L);
        }

        sweep(profileDir);

        Path portFile = profileDir.resolve(PORT_FILE);
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not clear " + portFile, e);
        }

        Files.createDirectories(logDir);
        Path logFile = logDir.resolve("chrome.log");

        ProcessBuilder builder = new ProcessBuilder(
                command(browser, profileDir, headless, width, height));
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();
        LIVE_PIDS.add(process.pid());
        URI endpoint;
        try {
            endpoint = awaitEndpoint(process, portFile, startupTimeoutMillis);
        } catch (IOException | RuntimeException e) {
            // Only tear down what we started; a blanket profile sweep here would
            // also kill the browser this method just launched if a second call
            // raced with it.
            LIVE_PIDS.remove(process.pid());
            killTree(process);
            throw e;
        }

        LOG.info(browser.name() + " is up on " + endpoint);
        return new ChromeProcess(process, endpoint, profileDir, logFile, headless);
    }

    /** Cache directories that are safe to drop between runs. */
    private static final List<String> DISPOSABLE = List.of(
            "Cache", "Code Cache", "GPUCache", "GrShaderCache", "ShaderCache", "DawnCache",
            "DawnWebGPUCache", "DawnGraphiteCache", "GPUPersistentCache", "Service Worker",
            "Shared Dictionary", "optimization_guide_model_store", "Crashpad",
            "component_crx_cache", "extensions_crx_cache", "BrowserMetrics");

    static void sweep(Path profile) {
        long freed = 0L;
        List<Path> victims = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(profile, 3)) {
            walk.filter(Files::isDirectory)
                    .filter(path -> DISPOSABLE.contains(path.getFileName().toString()))
                    .forEach(victims::add);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not list the profile", e);
        }
        for (Path victim : victims) {
            freed += delete(victim);
        }
        if (freed >= 1L << 20) {
            LOG.info("Cleared " + (freed >> 20) + " MB of browser caches");
        }
    }

    private static long delete(Path root) {
        long[] freed = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    try {
                        Files.delete(file);
                        freed[0] += attributes.size();
                    } catch (IOException ignored) {
                        // A file still mapped by a dying process; skip it.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException e) {
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException ignored) {
                        // Non-empty because something refused to delete.
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not clear " + root, e);
        }
        return freed[0];
    }

    private static List<String> command(ChromeFinder.Found browser, Path profile,
                                        boolean headless, int width, int height) {
        List<String> command = new ArrayList<>();
        command.add(browser.path().toString());

        // Discover the port instead of hard-coding it: two game instances must not
        // fight over 9222.
        command.add("--remote-debugging-port=0");
        // Required since Chrome 111 for the WebSocket handshake to be accepted
        // from a non-browser client (which is exactly what we are).
        command.add("--remote-allow-origins=*");
        command.add("--user-data-dir=" + profile.toAbsolutePath());

        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--no-service-autorun");
        command.add("--noerrdialogs");
        command.add("--disable-breakpad");
        command.add("--disable-component-update");
        command.add("--disable-default-apps");
        command.add("--disable-hang-monitor");
        command.add("--disable-prompt-on-repost");
        command.add("--disable-sync");
        command.add("--disable-client-side-phishing-detection");
        command.add("--disable-background-networking");
        command.add("--metrics-recording-only");
        command.add("--hide-crash-restore-bubble");
        command.add("--password-store=basic");
        command.add("--use-mock-keychain");

        // Audio is captured from the page's own Web Audio graph (see PageAudio), so
        // Chrome must not be muted: --mute-audio silences the page before the graph
        // ever produces samples, and the capture then receives pure silence.
        //
        // The browser still must not open the system's audio device - that would
        // play the page through the speakers outside the game and duck the game's
        // own audio. Headless Chrome has no output device to open, which is why the
        // stream stays silent on the system side while the graph still runs.
        //
        // (An earlier revision passed --mute-audio here and every page was silent,
        // which looked exactly like a broken capture script.)

        // Without this, a page that calls play() on load is blocked by Chrome's
        // autoplay policy: the video element ends up paused with readyState=4, so
        // the screen shows a perfect still frame of the first frame and looks
        // frozen. There is no user to click the page - a wall in a world is the
        // only "gesture" there will ever be.
        command.add("--autoplay-policy=no-user-gesture-required");

        // Headless Chrome runs an audio service that decodes but has no output
        // device. Keeping it in-process keeps the page's Web Audio graph advancing
        // so the capture script receives samples.
        //
        // Note: --disable-features may only appear ONCE on the command line. Chrome
        // takes the last occurrence and silently drops the rest, so a second flag
        // later in this list would erase this one. All disabled features are
        // therefore collected into a single argument below.
        List<String> disabledFeatures = new ArrayList<>();
        disabledFeatures.add("AudioServiceOutOfProcess");

        // A page in a background tab throttles timers and rAF down to ~1 fps,
        // which would make a video on a screen look frozen. All three flags are
        // needed: they cover timers, occlusion and renderer priority separately.
        command.add("--disable-background-timer-throttling");
        command.add("--disable-backgrounding-occluded-windows");
        command.add("--disable-renderer-backgrounding");

        disabledFeatures.add("CalculateNativeWinOcclusion");
        disabledFeatures.add("Translate");
        disabledFeatures.add("MediaRouter");
        disabledFeatures.add("OptimizationHints");
        disabledFeatures.add("AcceptCHFrame");
        disabledFeatures.add("InterestFeedContentSuggestions");
        disabledFeatures.add("OptimizationGuideModelDownloading");
        disabledFeatures.add("OptimizationTargetPrediction");
        disabledFeatures.add("OptimizationHintsFetching");

        command.add("--disable-gpu-shader-disk-cache");
        command.add("--disk-cache-size=" + (64L << 20));
        command.add("--media-cache-size=" + (32L << 20));
        command.add("--window-size=" + width + "," + height);

        if (headless) {
            command.add("--headless=new");
            // Native fullscreen takes the virtual monitor's bounds, not the
            // CDP-emulated screen size. The headless default is only 800x600.
            command.add("--screen-info={1920x1080}");
        } else {
            // Offscreen rather than headless: a real window keeps the compositor
            // on the normal path (headless can differ in what it composites), and
            // parking it far off the desktop keeps it out of the user's way.
            command.add("--window-position=-32000,-32000");
        }

        // --- GPU assignment ---------------------------------------------------
        //
        // The machine has a discrete GPU and an integrated one, and the game takes
        // the discrete one. Chrome compositing and JPEG-encoding every page frame on
        // the same GPU makes the two compete: the game's frame time suffers and so
        // does the page.
        //
        // Chrome is therefore pointed at the low-power adapter, which on Windows is
        // the integrated GPU. The page is a 2D composite of a decoded video, so the
        // integrated GPU handles it comfortably while the discrete GPU stays free
        // for the game. Chrome's GPU process is also what encodes the JPEG, so the
        // encode leaves the CPU entirely.
        command.add("--force_low_power_gpu");
        command.add("--enable-gpu-rasterization");
        command.add("--enable-zero-copy");

        // One --disable-features for everything collected above.
        command.add("--disable-features=" + String.join(",", disabledFeatures));

        command.add("about:blank");
        return command;
    }

    private static URI awaitEndpoint(Process process, Path portFile, long timeoutMillis)
            throws IOException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("The browser exited immediately with code "
                        + process.exitValue());
            }
            if (Files.isRegularFile(portFile)) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
                } catch (IOException stillBeingWritten) {
                    lines = List.of();
                }
                if (lines.size() >= 2 && !lines.get(0).isBlank() && !lines.get(1).isBlank()) {
                    try {
                        int port = Integer.parseInt(lines.get(0).trim());
                        return URI.create("ws://127.0.0.1:" + port + lines.get(1).trim());
                    } catch (NumberFormatException e) {
                        // Half-written file; try again on the next pass.
                        LOG.log(Level.FINE, "Ignoring a partially written " + PORT_FILE, e);
                    }
                }
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the browser to start");
            }
        }
        throw new IOException("The browser did not open a DevTools port within "
                + (timeoutMillis / 1000) + "s");
    }

    /** Tail of the browser's own log, for diagnosis when startup fails. */
    public String recentLog(int maxLines) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return "";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            return String.join(System.lineSeparator(),
                    lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Stop the browser and everything it spawned.
     *
     * <p>Chrome's own children (renderers, GPU, utility) do not always die with
     * the parent, and an orphaned renderer keeps a few hundred MB and a profile
     * lock. They are collected first, then killed after the parent so that a
     * gracefully exiting browser does not look like a leak.</p>
    /**
     * Stop the browser and everything it spawned.
     *
     * <p><b>Why this does not use {@code process.descendants()}.</b> That was the
     * first implementation and it leaked: Chrome's renderer and utility processes
     * are children of the browser process, but on Windows they are re-parented to
     * the OS the moment the parent dies, so a snapshot taken after
     * {@code destroy()} finds nothing. Worse, the early return below used to bail
     * out entirely when the parent was already dead - which is exactly the crash
     * case where the orphans exist. Eleven leaked Chrome processes from one
     * crashed session were enough to make every later launch fail with exit code
     * 21 (profile already in use).</p>
     *
     * <p>Instead, every Chrome process whose command line names our profile
     * directory is killed. That is deterministic: the profile path is unique to
     * this instance, so no other Chrome on the machine can match, and it still
     * works when the parent has already exited.</p>
     */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        LIVE_PIDS.remove(process.pid());
        // Always sweep, even when the parent was already dead: the orphans are the
        // whole reason this exists.
        int killed = killByProfile(profile);
        if (killed > 0) {
            LOG.info("Stopped " + killed + " browser process(es) for " + profile.getFileName());
        }
    }

    /**
     * Kill stale Chrome processes explicitly using this profile. On Windows the JVM
     * cannot read command lines for some Chromium children, so use CIM to find them.
     *
     * @param exclude a process to leave alone, typically the one we just started
     * @return how many processes received a termination request
     */
    public static int killByProfile(Path profile, ProcessHandle exclude) {
        List<Long> victims = findPidsByProfile(profile);
        int killed = 0;
        for (long pid : victims) {
            if (pid == ProcessHandle.current().pid()) {
                continue;
            }
            if (exclude != null && pid == exclude.pid()) {
                continue;
            }
            // Never kill a browser this JVM is still using. A shutdown of an old
            // instance and the startup of its replacement can overlap on the same
            // profile, and sweeping blindly would kill the new one.
            if (LIVE_PIDS.contains(pid)) {
                continue;
            }
            ProcessHandle.of(pid).ifPresent(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            killed++;
        }
        return killed;
    }

    /** Find Chrome processes whose effective user-data directory is this profile. */
    public static List<Long> findPidsByProfile(Path profile) {
        Path expectedProfile = profile.toAbsolutePath().normalize();
        List<Long> found = new ArrayList<>();

        // Ask for pid and command line, separated by a character that cannot appear
        // in a Windows path, and let PowerShell do the quoting.
        String script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
                + "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" "
                + "| Where-Object { $_.CommandLine } "
                + "| ForEach-Object { [string]$_.ProcessId + '|' + $_.CommandLine }";

        // Pass the script Base64-encoded. Passing it as a plain -Command argument
        // loses the inner quotes: PowerShell strips them before parsing, the
        // ForEach-Object body then reads as a bare pipeline expression, and the
        // whole query fails with a parser error while the Java side sees only
        // "no output" and reports zero processes.
        String encoded = java.util.Base64.getEncoder().encodeToString(
                script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));

        // Bare "powershell.exe", not an absolute path. Resolving through
        // C:\Windows\System32 fails with "CreateProcess error=5, access denied" from
        // this JVM (a 64-bit process looking at a System32 path that Windows
        // redirects), while the bare name resolves through PATH and works. Measured
        // both ways on the target machine; the absolute path is the one that fails.
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded);
        // Merge stderr into the same pipe. Left separate, PowerShell's startup
        // CLIXML progress records fill the unread error pipe and the query blocks
        // forever while this thread waits to read its stdout.
        builder.redirectErrorStream(true);
        try {
            Process query = builder.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(query.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    // Keep only rows shaped "<pid>|<command line>"; this drops
                    // CLIXML noise and any progress records in the same stream.
                    if (line.indexOf('|') <= 0) {
                        continue;
                    }
                    text.append(line).append('\n');
                }
                output = text.toString();
            }
            if (!query.waitFor(15, TimeUnit.SECONDS)) {
                query.destroyForcibly();
                LOG.warning("Timed out while looking for processes holding the profile");
                return found;
            }
            for (String line : output.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String commandLine = line.substring(bar + 1);
                if (!usesProfile(commandLine, expectedProfile)) {
                    continue;
                }
                try {
                    found.add(Long.parseLong(line.substring(0, bar).trim()));
                } catch (NumberFormatException ignored) {
                    // A header or a malformed row; skip it.
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not enumerate processes holding the profile", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found;
    }

    /** Kill everything on this profile, including any live browser. */
    public static int killByProfile(Path profile) {
        return killByProfile(profile, null);
    }

    static boolean usesProfile(String commandLine, Path profile) {
        final String option = "--user-data-dir=";
        String actual = null;
        int index = 0;
        boolean first = true;
        while (index < commandLine.length()) {
            if (Character.isWhitespace(commandLine.charAt(index))) {
                index++;
                continue;
            }
            StringBuilder arg = new StringBuilder();
            boolean quoted = false;
            while (index < commandLine.length()) {
                int slashes = 0;
                while (index < commandLine.length() && commandLine.charAt(index) == '\\') {
                    slashes++;
                    index++;
                }
                if (index < commandLine.length() && commandLine.charAt(index) == '"') {
                    arg.repeat('\\', slashes / 2);
                    if ((slashes & 1) == 0) quoted = !quoted;
                    else arg.append('"');
                    index++;
                    continue;
                }
                arg.repeat('\\', slashes);
                if (index == commandLine.length()) break;
                char c = commandLine.charAt(index);
                if (!quoted && Character.isWhitespace(c)) break;
                arg.append(c);
                index++;
            }
            if (quoted) return false;
            if (first) {
                first = false;
                continue;
            }
            String value = arg.toString();
            if (value.equals("--")) break;
            if (value.startsWith(option)) actual = value.substring(option.length());
        }
        if (actual == null) return false;
        try {
            Path path = Path.of(actual);
            return path.isAbsolute() && profile.equals(path.normalize());
        } catch (java.nio.file.InvalidPathException ignored) {
            return false;
        }
    }

    /** How many live processes currently name this profile on their command line. */
    public static int countByProfile(Path profile) {
        return findPidsByProfile(profile).size();
    }

    /**
     * Wait until no process names this profile any more.
     *
     * @return true if the profile became free within the timeout
     */
    public static boolean awaitProfileFree(Path profile, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (countByProfile(profile) == 0) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LOG.warning("Profile " + profile.getFileName() + " still has "
                + countByProfile(profile) + " process(es) after " + timeoutMillis + "ms");
        return false;
    }

    private static void killTree(Process process) {
        process.destroyForcibly();
    }

    /** Probe a DevTools port, used to detect a still-running instance. */
    public static boolean portIsOpen(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Read an endpoint from an existing profile, if one is still live. */
    public static Optional<URI> liveEndpoint(Path profile) {
        Path portFile = profile.resolve(PORT_FILE);
        if (!Files.isRegularFile(portFile)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
            if (lines.size() < 2 || lines.get(0).isBlank() || lines.get(1).isBlank()) {
                return Optional.empty();
            }
            int port = Integer.parseInt(lines.get(0).trim());
            if (!portIsOpen(port)) {
                return Optional.empty();
            }
            return Optional.of(URI.create("ws://127.0.0.1:" + port + lines.get(1).trim()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
