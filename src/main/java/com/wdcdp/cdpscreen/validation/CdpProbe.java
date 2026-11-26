package com.wdcdp.cdpscreen.validation;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.wdcdp.cdpscreen.CdpBackend;
import com.wdcdp.cdpscreen.cdp.CdpPage;
import com.wdcdp.cdpscreen.cdp.ChromeFinder;
import com.wdcdp.cdpscreen.audio.PageAudio;

/**
 * Stage 1: prove the CDP path works with no Minecraft in the loop.
 *
 * <p>Checks, in order: a browser is found, Chrome opens a DevTools port, the
 * WebSocket handshake succeeds, a flattened session attaches, a page navigates,
 * and frames actually arrive and decode to the expected size. If this fails there
 * is no point building a mod jar, because the failure would be inside Minecraft
 * where the only symptom is a black screen.</p>
 *
 * <p>Run with the NeoForge classpath (it needs Gson, LWJGL and the Minecraft jar
 * for {@code NativeImage}).</p>
 */
public final class CdpProbe {

    /**
     * A local animated page.
     *
     * <p>Written to a temp file and loaded over {@code file://} on purpose: Chrome
     * refuses top-level navigation to a {@code data:} URL, so a data-URI test page
     * renders nothing, produces exactly one screencast frame, and looks exactly
     * like a broken backend. That cost an hour once; it is not repeated here.</p>
     */
    private static String writeTestPage() throws java.io.IOException {
        String html = "<body style='margin:0;background:#123'>"
                + "<canvas id=c width=640 height=360></canvas><script>"
                + "const x=document.getElementById('c').getContext('2d');let t=0;"
                + "function f(){t+=0.08;x.fillStyle='hsl('+(t*40%360)+',90%,50%)';"
                + "x.fillRect(0,0,640,360);x.fillStyle='#fff';x.font='64px sans-serif';"
                + "x.fillText('CDP '+Math.round(t*20),40,200);requestAnimationFrame(f)}"
                + "f();</script></body>";
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "wdcdp-probe-page.html");
        java.nio.file.Files.writeString(file, html);
        return file.toUri().toString();
    }

    private CdpProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== CDP probe (stage 1) ===");

        ChromeFinder.Found browser = ChromeFinder.find().orElse(null);
        if (browser == null) {
            System.out.println("FAIL  no browser found");
            System.out.println(ChromeFinder.describeSearch());
            System.exit(1);
        }
        System.out.println("  PASS  browser: " + browser);
        String testUrl = writeTestPage();

        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "wdcdp-probe");
        CdpBackend backend = CdpBackend.get();
        backend.setDataDirectory(dataDir);

        long bootStart = System.nanoTime();
        boolean ready = backend.start().get(40, TimeUnit.SECONDS);
        long bootMillis = (System.nanoTime() - bootStart) / 1_000_000L;
        System.out.println((ready ? "  PASS  " : "  FAIL  ") + "backend started in "
                + bootMillis + " ms (" + backend.describe() + ")");
        if (!ready) {
            System.out.println("--- chrome log tail ---");
            System.out.println(backend.chromeLog(40));
            backend.shutdown();
            System.exit(1);
        }

        CdpPage page = backend.createPage(new com.wdcdp.cdpscreen.render.PageTexture(), 640, 360)
                .get(15, TimeUnit.SECONDS);
        System.out.println("  PASS  page created: target=" + page.targetId()
                + " session=" + page.sessionId());

        page.installMediaControl().get(15, TimeUnit.SECONDS);
        var audioBlocks = new java.util.concurrent.atomic.AtomicLong();
        var audioPrepared = new java.util.concurrent.atomic.AtomicBoolean();
        PageAudio.install(page, () -> audioPrepared.set(true), (planar, count, rate) -> {
            if (audioPrepared.get()) audioBlocks.incrementAndGet();
        });
        page.setStreaming(true);
        page.navigate(testUrl);

        // Wait for a frame that has actually been decoded: framesReceived counts
        // arrivals on the WebSocket, while frameWidth is only set once the decoder
        // has produced an image, so waiting on the size also waits for the decode.
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (page.frameWidth() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        long frames = page.framesReceived();
        if (frames == 0 || page.frameWidth() == 0) {
            System.out.println("  FAIL  no decoded screencast frames arrived");
            System.out.println("        (a data: URL would look exactly like this -"
                    + " Chrome refuses top-level navigation to data:)");
            page.close();
            backend.shutdown();
            System.exit(1);
        }
        System.out.println("  PASS  first frame decoded: " + page.frameWidth() + "x"
                + page.frameHeight() + " after " + frames + " frame(s)");

        // Measure the sustained rate over a fixed window.
        long mark = page.framesReceived();
        long ackMark = page.framesAcked();
        long windowStart = System.nanoTime();
        Thread.sleep(4000);
        long during = page.framesReceived() - mark;
        long acksDuring = page.framesAcked() - ackMark;
        double seconds = (System.nanoTime() - windowStart) / 1_000_000_000.0;
        double fps = during / seconds;
        System.out.printf("  INFO  sustained %.1f fps over %.1fs (%d frames, %d acks)%n",
                fps, seconds, during, acksDuring);
        System.out.println("  INFO  decode failures: " + page.decodeFailureCount()
                + (page.decodeFailureCount() > 0 ? " last=" + page.lastDecodeError() : ""));
        if (fps < 5.0) {
            System.out.println("  FAIL  frame rate too low: " + String.format("%.1f", fps));
        } else {
            System.out.println("  PASS  frame rate " + String.format("%.1f", fps) + " fps");
        }
        boolean healthyFps = fps >= 5.0;

        boolean media = checkMedia(page, audioBlocks);
        System.out.println((media ? "  PASS  " : "  FAIL  ")
                + "pause stops media, audio blocks and capture; resume preserves user-paused media");

        boolean owned = checkPageOwnedAudio(page, audioBlocks);
        System.out.println((owned ? "  PASS  " : "  FAIL  ")
                + "media already routed by the page still reaches the host");

        boolean scrollbar = checkScrollbar(page);
        System.out.println((scrollbar ? "  PASS  " : "  FAIL  ") + "held left button drags the native scrollbar");

        boolean keyboard = checkKeyboard(page);
        System.out.println((keyboard ? "  PASS  " : "  FAIL  ") + "GLFW keys, browser editing and Unicode text");

        boolean tabs = checkNoStrayTabs(page);
        System.out.println((tabs ? "  PASS  " : "  FAIL  ") + "no restored tabs left on the game profile");

        boolean popup = checkNewTabHandling(page, testUrl);
        System.out.println((popup ? "  PASS  " : "  FAIL  ")
                + "a link opening a new window is shown on the screen, not left running");

        boolean fullscreen = checkNativeFullscreen(page);
        System.out.println((fullscreen ? "  PASS  " : "  FAIL  ")
                + "native fullscreen keeps capture geometry and can be exited from the wall");

        // 5x3 framed wall: drawable units 19:11, capture bounded by 1.88 Mpx.
        page.setViewport(1900, 1100, 1802, 1043);
        page.navigate(testUrl);
        long resizeDeadline = System.nanoTime() + 10_000_000_000L;
        boolean resized = false;
        while (System.nanoTime() < resizeDeadline) {
            if (Math.abs(page.frameWidth() - 1802) <= 1 && Math.abs(page.frameHeight() - 1043) <= 1
                    && "1900x1100".equals(value(page, "innerWidth + 'x' + innerHeight"))) {
                resized = true;
                break;
            }
            Thread.sleep(100);
        }
        System.out.println((resized ? "  PASS  " : "  FAIL  ")
                + "viewport change reaches the frame (" + page.frameWidth() + "x"
                + page.frameHeight() + ")");
        long highResFrames = page.framesReceived();
        Thread.sleep(2000);
        long highResDuring = page.framesReceived() - highResFrames;
        System.out.printf("  INFO  higher-resolution capture %.1f fps%n", highResDuring / 2.0);

        // Teardown, then confirm the process table is clean.
        page.close();
        backend.forget(page);
        Thread.sleep(500);
        backend.shutdown();
        Thread.sleep(1000);
        System.out.println("  INFO  backend after shutdown: " + backend.describe());
        boolean cleanedUp = backend.state() == CdpBackend.State.UNINITIALIZED
                && backend.livePageCount() == 0;
        System.out.println((cleanedUp ? "  PASS  " : "  FAIL  ")
                + "shutdown released the pages and the process");

        boolean ok = ready && frames > 0 && page.decodeFailureCount() == 0
                && media && owned && scrollbar && keyboard && tabs && popup && fullscreen
                && resized && highResDuring >= 24 && cleanedUp && healthyFps;
        System.out.println();
        System.out.println(ok ? "=== stage 1 PASSED ===" : "=== stage 1 FAILED ===");
        System.exit(ok ? 0 : 1);
    }

    private static boolean checkKeyboard(CdpPage page) throws Exception {
        value(page, """
                (() => {
                  const form = document.createElement('form');
                  window.submitCount = 0;
                  form.addEventListener('submit', event => { event.preventDefault(); window.submitCount++; });
                  const input = document.createElement('input');
                  input.id = 'keyboardProbe';
                  form.append(input);
                  document.body.append(form);
                  input.focus();
                  return 'ready';
                })()
                """);
        page.keyTyped('A');
        page.keyTyped('中');
        page.insertText("𠮷");
        String field = "document.getElementById('keyboardProbe').value";
        String typed = value(page, field);
        value(page, "document.getElementById('keyboardProbe').setSelectionRange(2,2); 'ready'");
        press(page, 261, 0);
        String deleted = value(page, field);
        press(page, 259, 0);
        String backspaced = value(page, field);
        press(page, 257, 0);
        press(page, 335, 0);
        String submissions = value(page, "String(window.submitCount)");
        System.out.printf("  INFO  keyboard typed=%s, deleted=%s, backspace=%s, submissions=%s%n",
                typed, deleted, backspaced, submissions);
        int ctrl = com.wdcdp.cdpscreen.cdp.CdpKeys.CTRL;
        page.insertText("中文𠮷");
        var control = com.wdcdp.cdpscreen.cdp.CdpKeys.describeGlfw(341, ctrl);
        page.keyDown(control, ctrl);
        press(page, 'A', ctrl);
        page.keyUp(control, 0);
        String selected = value(page, "(() => {const el = document.getElementById('keyboardProbe');"
                + "return el.value.slice(el.selectionStart, el.selectionEnd)})()");
        page.insertText("新文本");
        String replaced = value(page, field);
        return "A中𠮷".equals(typed) && "A中".equals(deleted) && "A".equals(backspaced)
                && "2".equals(submissions) && "A中文𠮷".equals(selected) && "新文本".equals(replaced);
    }

    private static void press(CdpPage page, int glfwKey, int modifiers) {
        var key = com.wdcdp.cdpscreen.cdp.CdpKeys.describeGlfw(glfwKey, modifiers);
        page.keyDown(key, modifiers);
        page.keyUp(key, modifiers);
    }

    /**
     * The backend must not leave restored tabs decoding in the background.
     *
     * <p>A profile saved with an unclean exit restores its old tabs, which decode
     * video with nobody watching it and no audio reaching the game. This asserts
     * the cleanup ran: after the backend has started and a page has been created,
     * the only page targets are the backend's initial blank tab and the page this
     * probe created.</p>
     */
    private static boolean checkNoStrayTabs(CdpPage page) throws Exception {
        var targets = page.browserTargetsForTest().get(10, TimeUnit.SECONDS);
        int pages = 0;
        int blanks = 0;
        for (var element : targets.getAsJsonArray("targetInfos")) {
            if (!element.isJsonObject()) continue;
            var target = element.getAsJsonObject();
            if (!"page".equals(target.get("type").getAsString())) continue;
            pages++;
            String url = target.has("url") ? target.get("url").getAsString() : "";
            if (url.isEmpty() || "about:blank".equals(url)) blanks++;
        }
        System.out.println("  INFO  page targets on this profile: " + pages
                + " (blank: " + blanks + ")");
        // One blank startup tab plus this probe's page; anything beyond that is stray.
        return pages <= 2;
    }

    /**
     * Opening a new window must not leave a second page running offscreen.
     *
     * <p>Clicking a link with {@code target=_blank} makes Chrome open a real tab.
     * The screen navigates to the new address, but unless that tab is closed it
     * stays alive: not displayed, not audible in the game, still decoding. Rapid
     * clicks left several players running at once, which is what this asserts
     * cannot happen.</p>
     */
    private static boolean checkNewTabHandling(CdpPage page, String newUrl) throws Exception {
        int before = countPages(page);
        page.execute("window.open('" + newUrl + "', '_blank'); 'opened'");
        // The watchdog sweeps every three seconds, so wait past one full cycle:
        // asserting sooner measures the poll interval, not the behaviour.
        Thread.sleep(5000);
        int after = countPages(page);
        System.out.println("  INFO  page targets before=" + before + " after opening a window=" + after);
        // Either the tab was closed outright, or it replaced the blank startup tab:
        // the count must not grow and the screen must still be alive.
        if (after > before) return false;
        return page.frameWidth() > 0;
    }

    private static int countPages(CdpPage page) throws Exception {
        var targets = page.browserTargetsForTest().get(10, TimeUnit.SECONDS);
        int count = 0;
        for (var element : targets.getAsJsonArray("targetInfos")) {
            if (!element.isJsonObject()) continue;
            var target = element.getAsJsonObject();
            if (!"page".equals(target.get("type").getAsString())) continue;
            String url = target.has("url") ? target.get("url").getAsString() : "";
            if (url.isEmpty() || "about:blank".equals(url)) continue;
            count++;
        }
        return count;
    }

    private static final String NATIVE_FULLSCREEN = "document.fullscreenElement?.id === 'box'";
    private static final String NO_OVERLAY = "!document.querySelector('[data-wdcdp-fullscreen-exit]')";

    /** Exercise native state, transformed ancestors, and both exit paths without screenshots. */
    private static boolean checkNativeFullscreen(CdpPage page) throws Exception {
        var original = page.currentViewport();
        String normalOverflow = value(page, "getComputedStyle(document.documentElement).overflow");
        value(page, """
                (() => {
                  const wrapper = document.createElement('div');
                  wrapper.id = 'fsWrapper';
                  wrapper.style.cssText = 'transform:translateZ(0);position:relative;height:240px';
                  const box = document.createElement('div');
                  box.id = 'box';
                  box.style.cssText = 'display:block;width:320px;height:200px;background:#123';
                  box.innerHTML = `<div class="bpx-player-container" style="height:80%">
                    <div class="bpx-player-primary-area" style="height:90%">
                      <div class="bpx-player-video-area" style="position:relative;height:80%;overflow:hidden">
                        <div class="bpx-player-video-perch" style="position:relative;height:90%">
                          <div class="bpx-player-video-wrap" style="position:absolute;top:12px;height:80%">
                            <video style="width:80%;margin-top:7px;object-fit:contain"></video>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>`;
                  box.addEventListener('click', () => {
                    if (!document.fullscreenElement) box.requestFullscreen().catch(() => {});
                  });
                  const pageExit = document.createElement('button');
                  pageExit.id = 'pageExit';
                  pageExit.textContent = 'Player exit';
                  pageExit.style.cssText = 'position:absolute;right:0;bottom:0;z-index:1';
                  pageExit.addEventListener('click', event => {
                    event.stopPropagation();
                    if (document.fullscreenElement) document.exitFullscreen();
                  });
                  box.append(pageExit);
                  wrapper.append(box);
                  document.body.append(wrapper);
                  return 'ready';
                })()
                """);
        String position = "document.getElementById('box')";
        value(page, position + ".scrollIntoView({block:'center'}); 'ready'");
        clickElementCenter(page, position);
        boolean entered = waitForTrue(page, NATIVE_FULLSCREEN);
        boolean stateCorrect = entered && "true".equals(value(page, NO_OVERLAY)) && "true".equals(value(page, """
                (() => {
                  const element = document.fullscreenElement;
                  const r = element.getBoundingClientRect();
                  return element.parentElement.id === 'fsWrapper'
                    && element.matches(':fullscreen')
                    && Math.abs(r.left) < 2 && Math.abs(r.top) < 2
                    && Math.abs(r.width - innerWidth) < 2 && Math.abs(r.height - innerHeight) < 2
                    && getComputedStyle(document.documentElement).overflow === 'hidden';
                })()
                """));
        boolean stableGeometry = entered && waitForCaptureGeometry(page, true);
        if (!stableGeometry) return false;
        var beforeGrowth = page.currentViewport();
        page.setViewport(1920, 823, 1280, 549);
        boolean manualBudget = waitForTrue(page, "innerWidth === 1920 && innerHeight === 1080")
                && waitForCaptureGeometry(page, true)
                && page.currentViewport().captureWidth() > beforeGrowth.captureWidth()
                && page.currentViewport().captureWidth() * page.currentViewport().captureHeight() <= 1280 * 549;
        page.setViewport(1920, 823, 1920, 823, 1920);
        boolean grewInFullscreen = manualBudget && waitForTrue(page, NATIVE_FULLSCREEN + " && innerWidth === 1920 && innerHeight === 1080")
                && waitForCaptureGeometry(page, true)
                && page.currentViewport().captureWidth() == 1920
                && page.currentViewport().captureHeight() == 1080
                && "true".equals(value(page, """
                (() => {
                  const video = document.querySelector('#box video');
                  const r = video.getBoundingClientRect(), s = getComputedStyle(video);
                  return r.left === 0 && r.top === 0 && r.width === innerWidth && r.height === innerHeight
                    && s.objectFit === 'fill';
                })()
                """));
        String playerExit = "document.getElementById('pageExit')";
        value(page, """
                (() => {
                  const video = document.querySelector('#box video');
                  video.addEventListener('mousemove', () => { window.__probeMouseOver = true; });
                  video.addEventListener('mouseleave', () => { window.__probeMouseLeft = true; });
                  return 'ready';
                })()
                """);
        page.mouseMoved(400, 300, 0);
        boolean mouseReachedVideo = waitForTrue(page, "window.__probeMouseOver === true");
        page.mouseMoved(-1, -1, 0);
        boolean hoverCleared = mouseReachedVideo && waitForTrue(page, "window.__probeMouseLeft === true");
        clickElementCenter(page, playerExit);
        String normal = "!document.fullscreenElement && " + NO_OVERLAY;
        boolean clickExited = waitForTrue(page, normal) && waitForCaptureGeometry(page, false)
                && "1920x823:contain".equals(value(page, "innerWidth + 'x' + innerHeight"
                + " + ':' + getComputedStyle(document.querySelector('#box video')).objectFit"));
        if (!clickExited) return false;
        page.setViewport(original.width(), original.height(), original.captureWidth(), original.captureHeight());
        if (!waitForTrue(page, "innerWidth === " + original.width() + " && innerHeight === " + original.height())
                || !waitForCaptureGeometry(page, false)) return false;
        value(page, position + ".scrollIntoView({block:'center'}); 'ready'");
        clickElementCenter(page, position);
        if (!waitForTrue(page, NATIVE_FULLSCREEN)) return false;
        boolean escapeConsumed = page.exitFullscreen().get(5, TimeUnit.SECONDS);
        boolean escapeExited = waitForTrue(page, normal) && waitForCaptureGeometry(page, false);
        boolean restored = normalOverflow.equals(value(page, "getComputedStyle(document.documentElement).overflow"))
                && original.equals(page.currentViewport())
                && "fsWrapper".equals(value(page, position + ".parentElement.id"));
        System.out.println("  INFO  fullscreen layout=" + stateCorrect + ", geometry=" + stableGeometry
                + ", fullscreen resize=" + grewInFullscreen + ", hover cleared=" + hoverCleared
                + ", player exit=" + clickExited + ", host exit=" + escapeExited + ", restored=" + restored);
        return stateCorrect && stableGeometry && grewInFullscreen && hoverCleared
                && escapeConsumed && escapeExited && restored;
    }

    private static boolean waitForCaptureGeometry(CdpPage page, boolean fullscreen) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var view = page.currentViewport();
            if (view != null && view.fullscreen() == fullscreen && page.isStreaming()
                    && Math.abs(page.frameWidth() - view.captureWidth()) <= 3
                    && Math.abs(page.frameHeight() - view.captureHeight()) <= 3) {
                String geometry = "innerWidth === " + view.width() + " && innerHeight === " + view.height();
                if (fullscreen) geometry += " && !!document.fullscreenElement"
                        + " && innerWidth <= outerWidth && innerHeight <= outerHeight";
                if ("true".equals(value(page, geometry))) return true;
            }
            Thread.sleep(60);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static boolean waitForTrue(CdpPage page, String expression) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            if ("true".equals(value(page, expression))) return true;
            Thread.sleep(60);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void clickElementCenter(CdpPage page, String element) throws Exception {
        String[] point = value(page, "(() => { const r = (" + element + ").getBoundingClientRect();"
                + "return Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2); })()").split(",");
        int x = Integer.parseInt(point[0]);
        int y = Integer.parseInt(point[1]);
        page.mouseMoved(x, y, 0);
        page.mousePressed(x, y, 0, 1, 0);
        page.mouseReleased(x, y, 0, 1, 0);
    }

    /**
     * A page that already routed its own media element must still be audible.
     *
     * <p>{@code createMediaElementSource} throws when the element already has a
     * source node - which is what any player with its own analyser does. The tap
     * used to give up silently there, so such a site was completely silent in-game
     * with a zero counter and nothing to explain it. This builds that exact
     * situation and asserts audio still arrives.</p>
     */
    private static boolean checkPageOwnedAudio(CdpPage page,
                                               java.util.concurrent.atomic.AtomicLong blocks) throws Exception {
        long before = blocks.get();
        value(page, """
                (() => {
                  const media = document.createElement('audio');
                  media.id = 'ownedByPage';
                  // Route it ourselves first, the way a site's visualiser would.
                  const ctx = new AudioContext();
                  ctx.createMediaElementSource(media).connect(ctx.destination);
                  window.__ownedCtx = ctx;
                  const wav = new Uint8Array(44 + 160000);
                  const view = new DataView(wav.buffer);
                  function text(offset, value) {
                    for (let i = 0; i < value.length; i++) wav[offset + i] = value.charCodeAt(i);
                  }
                  text(0, 'RIFF'); view.setUint32(4, wav.length - 8, true);
                  text(8, 'WAVE'); text(12, 'fmt '); view.setUint32(16, 16, true);
                  view.setUint16(20, 1, true); view.setUint16(22, 1, true);
                  view.setUint32(24, 8000, true); view.setUint32(28, 16000, true);
                  view.setUint16(32, 2, true); view.setUint16(34, 16, true);
                  text(36, 'data'); view.setUint32(40, wav.length - 44, true);
                  for (let i = 0; i < 80000; i++) view.setInt16(44 + i * 2, Math.sin(i * 0.35) * 4000, true);
                  media.src = URL.createObjectURL(new Blob([wav], { type: 'audio/wav' }));
                  document.body.appendChild(media);
                  media.play();
                  return 'started';
                })()
                """);
        Thread.sleep(1500);
        long after = blocks.get();
        String tapped = value(page, "String(window.__wdcdpAudio?.tapped)");
        String untapped = value(page, "String(window.__wdcdpAudio?.untapped)");
        System.out.println("  INFO  page-owned media: blocks " + before + " -> " + after
                + ", tapped=" + tapped + ", untapped=" + untapped);
        value(page, "(function(){ try { window.__ownedCtx && window.__ownedCtx.close(); } catch(e){} "
                + "var m = document.getElementById('ownedByPage'); if (m) { m.pause(); m.remove(); } return 1 })()");
        return after > before;
    }

    private static boolean checkScrollbar(CdpPage page) throws Exception {
        page.execute("""
                (() => {
                  const style = document.createElement('style');
                  style.textContent = '#scroller::-webkit-scrollbar{width:20px}'
                    + '#scroller::-webkit-scrollbar-thumb{background:#345}';
                  document.head.append(style);
                  const scroll = document.createElement('div');
                  scroll.id = 'scroller';
                  scroll.style.cssText = 'position:fixed;left:40px;top:40px;width:200px;height:200px;overflow-y:scroll';
                  const content = document.createElement('div');
                  content.style.height = '1200px';
                  scroll.append(content);
                  document.body.append(scroll);
                })();
                """);
        String clientWidth = value(page, "String(document.getElementById('scroller').clientWidth)");
        value(page, "window.dragEvents = []; for(const type of ['mousedown','mousemove','mouseup']) "
                + "document.addEventListener(type, e => dragEvents.push([type,e.button,e.buttons,e.target.id]),true); 'ok'");
        page.mouseMoved(230, 55, 0);
        Thread.sleep(40);
        page.mousePressed(230, 55, 0, 1, 0);
        Thread.sleep(100);
        page.mouseMoved(230, 85, 0);
        Thread.sleep(40);
        page.mouseMoved(230, 115, 0);
        Thread.sleep(40);
        page.mouseMoved(230, 155, 0);
        Thread.sleep(250);
        String dragged = value(page, "String(document.getElementById('scroller').scrollTop)");
        page.mouseReleased(230, 155, 0, 1, 0);
        page.mouseMoved(230, 190, 0);
        Thread.sleep(150);
        String released = value(page, "String(document.getElementById('scroller').scrollTop)");
        System.out.println("  INFO  scrollbar client width=" + clientWidth + " dragged=" + dragged
                + " released=" + released + " events=" + value(page, "JSON.stringify(dragEvents)"));
        return !dragged.isEmpty() && Double.parseDouble(dragged) > 150 && dragged.equals(released);
    }

    private static boolean checkMedia(CdpPage page, java.util.concurrent.atomic.AtomicLong audioBlocks) throws Exception {
        page.execute("""
                (() => {
                  const wav = new Uint8Array(44 + 160000);
                  const view = new DataView(wav.buffer);
                  function text(offset, value) {
                    for (let i = 0; i < value.length; i++) wav[offset + i] = value.charCodeAt(i);
                  }
                  text(0, 'RIFF'); view.setUint32(4, wav.length - 8, true);
                  text(8, 'WAVE'); text(12, 'fmt '); view.setUint32(16, 16, true);
                  view.setUint16(20, 1, true); view.setUint16(22, 1, true);
                  view.setUint32(24, 8000, true); view.setUint32(28, 16000, true);
                  view.setUint16(32, 2, true); view.setUint16(34, 16, true);
                  text(36, 'data'); view.setUint32(40, wav.length - 44, true);
                  for (let i = 0; i < 80000; i++) view.setInt16(44 + i * 2, Math.sin(i * 0.35) * 256, true);
                  const url = URL.createObjectURL(new Blob([wav], { type: 'audio/wav' }));
                  for (const id of ['playing', 'manual']) {
                    const media = new Audio(url);
                    media.id = id;
                    document.body.appendChild(media);
                    if (id === 'playing') media.play();
                  }
                })();
                """);
        Thread.sleep(700);
        String advancing = value(page, "String(!playing.paused && playing.currentTime > 0)");
        boolean hadAudio = audioBlocks.get() > 0;
        System.out.println("  INFO  audio processor="
                + value(page, "String(window.__wdcdpAudio?.processor)") + " blocks=" + audioBlocks.get());
        page.setMediaPaused(true);
        Thread.sleep(250);
        String paused = value(page, "String(playing.paused && manual.paused)");
        String timestamp = value(page, "String(playing.currentTime)");
        long frames = page.framesReceived();
        long blocks = audioBlocks.get();
        Thread.sleep(400);
        boolean stopped = timestamp.equals(value(page, "String(playing.currentTime)"))
                && frames == page.framesReceived() && blocks == audioBlocks.get();
        page.setMediaPaused(false);
        Thread.sleep(700);
        String resumed = value(page, "String(!playing.paused && playing.currentTime > " + timestamp
                + " && manual.paused)");
        return hadAudio && "true".equals(advancing) && "true".equals(paused) && stopped
                && "true".equals(resumed) && page.framesReceived() > frames && audioBlocks.get() > blocks;
    }

    private static String value(CdpPage page, String expression) throws Exception {
        return page.evaluateForValue(expression).get(5, TimeUnit.SECONDS);
    }
}

