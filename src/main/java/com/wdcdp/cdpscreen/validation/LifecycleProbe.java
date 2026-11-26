package com.wdcdp.cdpscreen.validation;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.wdcdp.cdpscreen.CdpBackend;
import com.wdcdp.cdpscreen.cdp.CdpPage;
import com.wdcdp.cdpscreen.cdp.ChromeProcess;

/**
 * Process-lifecycle probe: does the backend leave anything behind?
 *
 * <p>This exists because a leaked Chrome is not a cosmetic problem. A profile
 * directory can only be opened by one browser at a time, so a single orphaned
 * renderer from a crashed session makes every later launch die with exit code 21
 * before it prints a single line - which reads as "the browser is broken" when
 * the real cause is a process that should have been killed minutes ago.</p>
 *
 * <p>Checks three things:</p>
 * <ol>
 *   <li>start, then shutdown, leaves zero processes naming our profile;</li>
 *   <li>a second start after that shutdown still succeeds (the profile lock was
 *       actually released);</li>
 *   <li>starting while a squatter holds the profile clears the squatter instead
 *       of failing.</li>
 * </ol>
 */
public final class LifecycleProbe {

    private static final Path DATA_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "wdcdp lifecycle " + java.util.UUID.randomUUID());
    private static final Path PROFILE = DATA_DIR.resolve("chrome-profile");

    private LifecycleProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== lifecycle probe (stages 5-6) ===");
        boolean ok = true;

        CdpBackend backend = CdpBackend.get();
        backend.setDataDirectory(DATA_DIR);

        // --- round 1: clean start and stop -----------------------------------
        System.out.println("\n-- round 1: start, create a page, stop --");
        ok &= round(backend, 1);

        // The shutdown is asynchronous in places; give the OS a moment.
        Thread.sleep(1500);
        int leaked = ChromeProcess.killByProfile(PROFILE);
        System.out.println((leaked == 0 ? "  PASS  " : "  FAIL  ")
                + "no browser process left after shutdown (found " + leaked + ")");
        ok &= leaked == 0;

        // --- round 2: can it start again? ------------------------------------
        System.out.println("\n-- round 2: start again on the same profile --");
        ok &= round(backend, 2);
        Thread.sleep(1500);
        int leaked2 = ChromeProcess.killByProfile(PROFILE);
        System.out.println((leaked2 == 0 ? "  PASS  " : "  FAIL  ")
                + "no browser process left after the second shutdown (found " + leaked2 + ")");
        ok &= leaked2 == 0;

        // --- round 3: squatter recovery --------------------------------------
        System.out.println("\n-- round 3: a squatter holds the profile --");
        boolean squatOk = squatterRecovery();
        System.out.println((squatOk ? "  PASS  " : "  FAIL  ")
                + "startup cleared a process still holding the profile");
        ok &= squatOk;

        System.out.println("\n-- round 4: independent profile --");
        boolean isolated = checkProfileIsolation();
        System.out.println((isolated ? "  PASS  " : "  FAIL  ")
                + "cleanup preserved Chrome on a different profile with the same prefix");
        ok &= isolated;

        System.out.println();
        System.out.println(ok ? "=== lifecycle PASSED ===" : "=== lifecycle FAILED ===");
        System.exit(ok ? 0 : 1);
    }

    private static boolean round(CdpBackend backend, int index) throws Exception {
        boolean ready = backend.start().get(40, TimeUnit.SECONDS);
        System.out.println((ready ? "  PASS  " : "  FAIL  ")
                + "round " + index + " started (" + backend.describe() + ")");
        if (!ready) {
            System.out.println("--- chrome log ---");
            System.out.println(backend.chromeLog(20));
            return false;
        }

        CdpPage page = backend.createPage(new com.wdcdp.cdpscreen.render.PageTexture(), 640, 360)
                .get(15, TimeUnit.SECONDS);
        page.setStreaming(true);
        page.navigate(testPage());

        long deadline = System.nanoTime() + 15_000_000_000L;
        while (page.frameWidth() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        boolean frames = page.frameWidth() > 0;
        System.out.println((frames ? "  PASS  " : "  FAIL  ")
                + "round " + index + " decoded a frame (" + page.frameWidth() + "x"
                + page.frameHeight() + ", " + page.framesReceived() + " received)");
        if (!frames) {
            System.out.println("        decode failures: " + page.decodeFailureCount()
                    + " " + page.lastDecodeError());
        }

        page.close();
        backend.forget(page);
        Thread.sleep(400);
        backend.shutdown();
        return frames;
    }

    /**
     * Start a browser, then start the backend without closing the first one, to
     * prove the squatter is cleared rather than tripping over exit code 21.
     */
    private static boolean squatterRecovery() throws Exception {
        // Deliberately start a bare Chrome that we never close through the API.
        com.wdcdp.cdpscreen.cdp.ChromeFinder.Found browser =
                com.wdcdp.cdpscreen.cdp.ChromeFinder.find().orElse(null);
        if (browser == null) {
            System.out.println("  INFO  no browser to squat with");
            return false;
        }

        ProcessBuilder squatter = new ProcessBuilder(
                browser.path().toString(),
                "--remote-debugging-port=0",
                "--remote-allow-origins=*",
                "--user-data-dir=" + PROFILE.toAbsolutePath(),
                "--no-first-run", "--no-default-browser-check",
                "--headless=new", "--mute-audio",
                "about:blank");
        squatter.redirectErrorStream(true);
        squatter.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process squatting = squatter.start();
        Thread.sleep(2500);
        System.out.println("  INFO  squatter pid=" + squatting.pid()
                + " alive=" + squatting.isAlive());

        CdpBackend backend = CdpBackend.get();
        boolean ready = backend.start().get(40, TimeUnit.SECONDS);
        System.out.println((ready ? "  PASS  " : "  FAIL  ")
                + "backend started despite the squatter (" + backend.describe() + ")");
        if (!ready) {
            System.out.println("--- chrome log ---");
            System.out.println(backend.chromeLog(20));
        }
        backend.shutdown();
        Thread.sleep(1200);
        return ready;
    }

    private static boolean checkProfileIsolation() throws Exception {
        var browser = com.wdcdp.cdpscreen.cdp.ChromeFinder.find().orElse(null);
        if (browser == null) return false;
        Path otherProfile = PROFILE.resolveSibling(PROFILE.getFileName() + "-desktop");
        Process other = new ProcessBuilder(browser.path().toString(),
                "--headless=new", "--remote-debugging-port=0", "--no-first-run",
                "--user-data-dir=" + otherProfile, PROFILE.resolve("test.html").toUri().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (other.isAlive() && ChromeProcess.liveEndpoint(otherProfile).isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            if (ChromeProcess.liveEndpoint(otherProfile).isEmpty()
                    || !ChromeProcess.findPidsByProfile(otherProfile).contains(other.pid())) return false;
            return ChromeProcess.killByProfile(PROFILE) == 0 && other.isAlive()
                    && ChromeProcess.liveEndpoint(otherProfile).isPresent();
        } finally {
            other.destroy();
            if (!other.waitFor(3, TimeUnit.SECONDS)) other.destroyForcibly();
            ChromeProcess.killByProfile(otherProfile);
        }
    }

    private static String testPage() throws java.io.IOException {
        String html = "<body style='margin:0;background:#123'>"
                + "<canvas id=c width=640 height=360></canvas><script>"
                + "const x=document.getElementById('c').getContext('2d');let t=0;"
                + "function f(){t+=0.08;x.fillStyle='hsl('+(t*40%360)+',90%,50%)';"
                + "x.fillRect(0,0,640,360);requestAnimationFrame(f)}f();</script></body>";
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "wdcdp-lifecycle-page.html");
        java.nio.file.Files.writeString(file, html);
        return file.toUri().toString();
    }
}
