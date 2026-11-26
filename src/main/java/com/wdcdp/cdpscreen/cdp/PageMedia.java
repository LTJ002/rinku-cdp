package com.wdcdp.cdpscreen.cdp;

import java.util.HashSet;
import java.util.Set;

/** Controls each wall document's media and offers an exit from native fullscreen. */
final class PageMedia {
    private static final String CADENCE_BINDING = "__wdcdpVideoCadence";
    private static final String READY_BINDING = "__wdcdpMediaReady";
    private static final String FULLSCREEN_BINDING = "__wdcdpNativeFullscreen";
    private static final String FULLSCREEN_WORLD = "wdcdp-fullscreen-coordinator";
    private static final String FULLSCREEN_SCRIPT = """
            (() => {
              if (window !== window.top || window.__wdcdpFullscreenWatching) return;
              window.__wdcdpFullscreenWatching = true;
              let previous = false;
              let retry = 0;
              let attempts = 0;
              function sync() {
                const report = window['FULLSCREEN_BINDING_NAME'];
                // Chrome can run the new-document script before Runtime has
                // exposed the binding in this new isolated execution context.
                if (typeof report !== 'function') {
                  if (!retry && attempts++ < 20) {
                    retry = setTimeout(() => { retry = 0; sync(); }, 25);
                  }
                  return;
                }
                const active = !!document.fullscreenElement;
                if (active === previous) return;
                previous = active;
                report(active ? 'enter' : 'exit');
              }
              document.addEventListener('fullscreenchange', sync, true);
              sync();

              let previousVideo = null;
              let previousFrames = 0;
              let previousTime = 0;
              let idleSamples = 0;
              let timer = 0;

              function sampleVideoCadence() {
                let primary = null;
                let largestArea = 0;
                for (const video of document.querySelectorAll('video')) {
                  if (video.paused || video.ended || video.seeking || video.readyState < 2) continue;
                  const area = video.clientWidth * video.clientHeight;
                  if (area >= 160 * 90 && area > largestArea) {
                    primary = video;
                    largestArea = area;
                  }
                }
                const report = window['CADENCE_BINDING_NAME'];
                if (primary) {
                  idleSamples = 0;
                  const now = performance.now();
                  const frames = primary.getVideoPlaybackQuality().totalVideoFrames;
                  const elapsed = now - previousTime;
                  if (typeof report === 'function') {
                    if (primary !== previousVideo) report('start');
                    else if (elapsed > 750) {
                      const rate = (frames - previousFrames) * 1000 / elapsed;
                      if (rate >= 10) report(String(Math.min(60, Math.round(rate))));
                    }
                  }
                  previousVideo = primary;
                  previousFrames = frames;
                  previousTime = now;
                } else if (++idleSamples >= 4) {
                  if (typeof report === 'function') report('0');
                  previousVideo = null;
                  timer = 0;
                  return;
                } else {
                  previousVideo = null;
                }
                timer = setTimeout(sampleVideoCadence, 1000);
              }

              document.addEventListener('playing', event => {
                if (!(event.target instanceof HTMLVideoElement) || timer) return;
                idleSamples = 0;
                sampleVideoCadence();
              }, true);
            })();
            """.replace("FULLSCREEN_BINDING_NAME", FULLSCREEN_BINDING)
               .replace("CADENCE_BINDING_NAME", CADENCE_BINDING);
    private static final String SCRIPT = """
            (() => {
              if (window.__wdcdpSetMediaPaused) return;
              const resume = new Set();
              let paused = false;

              function pauseElement(media) {
                if (!(media instanceof HTMLMediaElement) || media.paused || media.ended) return;
                resume.add(media);
                media.pause();
              }

              document.addEventListener('play', event => {
                if (paused) pauseElement(event.target);
              }, true);
              window.__wdcdpSetMediaPaused = value => {
                if (paused === value) return;
                paused = value;
                window.__wdcdpMediaPaused = value;
                if (paused) {
                  document.querySelectorAll('video,audio').forEach(pauseElement);
                } else {
                  for (const media of resume) {
                    if (media.isConnected && media.paused && !media.ended) {
                      media.play().catch(() => {});
                    }
                  }
                  resume.clear();
                }
              };
              const ready = window.__wdcdpMediaReady;
              if (typeof ready === 'function') ready('');

              if (window === window.top) {
                // Keep normal video links on this wall without starting a second
                // (audible) player in a background tab. Site click handlers still run.
                document.addEventListener('click', event => {
                  if (!event.isTrusted || event.button !== 0 || event.ctrlKey || event.metaKey
                      || event.shiftKey || event.altKey) return;
                  const link = event.composedPath().find(node => node instanceof HTMLAnchorElement);
                  if (!link || link.target.toLowerCase() !== '_blank' || link.hasAttribute('download')
                      || !/^https?:/.test(link.href)) return;
                  const target = link.target;
                  link.target = '_self';
                  setTimeout(() => { if (link.target === '_self') link.target = target; }, 0);
                }, true);
              }

              if (window === window.top && /(^|[.])douyin[.]com$/.test(location.hostname)) {
                // Only a trusted vertical swipe arms this. Do not restart a video
                // during ordinary buffering, after an explicit pause, or offscreen.
                let gesture = null;
                let timer = 0;
                let revision = new WeakMap();
                const version = v => [v.getAttribute('src'), v.currentSrc, v.srcObject, revision.get(v)];
                function foreground() {
                  let best = null, score = 0;
                  for (const video of document.querySelectorAll('video')) {
                    const r = video.getBoundingClientRect();
                    const width = Math.min(innerWidth, r.right) - Math.max(0, r.left);
                    const height = Math.min(innerHeight, r.bottom) - Math.max(0, r.top);
                    if (width < 128 || height < 100 || height < r.height * 0.65
                        || (video.checkVisibility && !video.checkVisibility({visibilityProperty: true, opacityProperty: true}))) continue;
                    const rank = width * height / (1 + Math.abs(r.top + r.height / 2 - innerHeight / 2) / innerHeight);
                    if (rank > score) { best = video; score = rank; }
                  }
                  return best;
                }
                function cancel(event) {
                  if (event && !event.isTrusted) return;
                  clearTimeout(timer);
                  gesture = null;
                }
                function probe() {
                  const g = gesture;
                  if (!g || paused || performance.now() > g.until) { cancel(); return; }
                  const v = foreground();
                  if (v && (v !== g.previous || version(v).some((value, i) => value !== g.version[i]))) {
                    if (v.currentTime > 0.25 || v.ended || v.error) { cancel(); return; }
                    if (v.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && !v.seeking) {
                      if (!v.paused) {
                        // A genuine buffering episode should recover on its own.
                        // Only retry a ready player whose clock did not move.
                        if (v.readyState < HTMLMediaElement.HAVE_FUTURE_DATA
                            || g.observed !== v || g.position !== v.currentTime) {
                          g.observed = v;
                          g.position = v.currentTime;
                          g.since = performance.now();
                        } else if (performance.now() - g.since >= 900) {
                          cancel();
                          try { v.pause(); v.play().catch(() => {}); } catch (e) {}
                          return;
                        }
                      } else {
                        cancel();
                        // Leave the site-owned source and buffers untouched.
                        try { v.play().catch(() => {}); } catch (e) {}
                        return;
                      }
                    }
                  }
                  timer = setTimeout(probe, 200);
                }
                document.addEventListener('loadstart', event => {
                  const v = event.target;
                  if (v instanceof HTMLVideoElement) revision.set(v, (revision.get(v) || 0) + 1);
                }, true);
                document.addEventListener('pointerdown', cancel, true);
                document.addEventListener('keydown', cancel, true);
                document.addEventListener('wheel', event => {
                  if (!event.isTrusted || event.ctrlKey || event.shiftKey || event.altKey || event.metaKey
                      || Math.abs(event.deltaY) <= Math.abs(event.deltaX) || paused) return;
                  clearTimeout(timer);
                  // Multiple wheel events in one gesture must not replace the pre-swipe baseline.
                  const now = performance.now();
                  if (!gesture || now - gesture.last > 400 || Math.sign(event.deltaY) !== gesture.direction) {
                    const previous = foreground();
                    if (!previous) { cancel(); return; }
                    gesture = {previous, version: version(previous), direction: Math.sign(event.deltaY)};
                  }
                  gesture.last = now;
                  gesture.until = now + 3000;
                  timer = setTimeout(probe, 500);
                }, {capture: true, passive: true});
              }


              // These rules apply in the fullscreen document, including an embedded player.
              try {
                const sheet = new CSSStyleSheet();
                sheet.replaceSync(`
                  :root:is(:fullscreen, :has(:fullscreen)),
                  :root:is(:fullscreen, :has(:fullscreen)) > body:not(:fullscreen) {
                    overflow: hidden !important;
                  }
                  :fullscreen::backdrop { background: black !important; }
                  video:fullscreen {
                    object-fit: fill !important;
                    object-position: center !important;
                    background-color: black !important;
                  }
                  /* Keep Ctrl+wheel's body zoom for the normal page. */
                  :root:is(:fullscreen, :has(:fullscreen)) > body:has(.bpx-player-container) {
                    zoom: 1 !important;
                  }
                  /* Bilibili positions the video inside a separate stage from its
                     controls. Stretch the whole picture across the fullscreen
                     area, including after a resize. */
                  :fullscreen :is(.bpx-player-container, .bpx-player-primary-area,
                                  .bpx-player-video-area, .bpx-player-video-perch),
                  :fullscreen .bpx-player-video-wrap,
                  :fullscreen .bpx-player-video-wrap video,
                  .bpx-player-video-wrap:fullscreen video {
                    box-sizing: border-box !important;
                    width: 100% !important;
                    height: 100% !important;
                    max-width: none !important;
                    max-height: none !important;
                    margin: 0 !important;
                    padding: 0 !important;
                  }
                  :fullscreen .bpx-player-video-wrap,
                  :fullscreen .bpx-player-video-wrap video,
                  .bpx-player-video-wrap:fullscreen video {
                    position: absolute !important;
                    inset: 0 !important;
                    transform: none !important;
                  }
                  :fullscreen .bpx-player-video-wrap video,
                  .bpx-player-video-wrap:fullscreen video {
                    object-fit: fill !important;
                    object-position: center !important;
                    background-color: black !important;
                  }
                `);
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
              } catch (e) { /* Native fullscreen still works without these rules. */ }

              // Chrome propagates an iframe's native fullscreen to its embedder,
              // even across origins. The host's Escape shortcut uses this entry point.
              if (window !== window.top) return;

              let exitPending = false;
              const nativeExit = Document.prototype.exitFullscreen;
              if (location.hostname === 'www.bilibili.com' && typeof nativeExit === 'function') {
                Document.prototype.exitFullscreen = function exitFullscreen() {
                  if (this === document && document.fullscreenElement?.matches('.bpx-player-container')) {
                    // The timed login reminder exits fullscreen before its dialog is shown.
                    // Identify that call chain without relying on asset hashes or line numbers.
                    const oldLimit = Error.stackTraceLimit;
                    let stack;
                    try {
                      Error.stackTraceLimit = 50;
                      stack = new Error().stack;
                    } finally {
                      Error.stackTraceLimit = oldLimit;
                    }
                    if (typeof stack === 'string'
                        && stack.includes('.openQuickLogin (https://s1.hdslb.com/bfs/static/jinkela/video/')
                        && stack.includes('.onTimeUpdate (https://s1.hdslb.com/bfs/static/player/')) {
                      // A rejection keeps the player from committing its normal layout.
                      return Promise.reject(new DOMException('Fullscreen retained during timed reminder', 'AbortError'));
                    }
                  }
                  return nativeExit.call(this);
                };
              }
              window.__wdcdpExitFullscreen = () => {
                if (!document.fullscreenElement || typeof nativeExit !== 'function') return false;
                if (exitPending) return true;
                try {
                  exitPending = true;
                  nativeExit.call(document).catch(() => {}).finally(() => { exitPending = false; });
                  return true;
                } catch (e) {
                  exitPending = false;
                  return false;
                }
              };
            })();
            """;

    private final CdpClient cdp;
    private final String sessionId;
    private final java.util.function.Consumer<Boolean> fullscreenChanged;
    private final java.util.function.IntConsumer cadenceChanged;
    private final Set<Integer> contexts = new HashSet<>();
    private boolean paused;

    PageMedia(CdpClient cdp, String sessionId, java.util.function.Consumer<Boolean> fullscreenChanged,
              java.util.function.IntConsumer cadenceChanged) {
        this.cdp = cdp;
        this.sessionId = sessionId;
        this.fullscreenChanged = fullscreenChanged;
        this.cadenceChanged = cadenceChanged;
    }

    java.util.concurrent.CompletableFuture<Void> install() {
        return cdp.call(sessionId, "Runtime.addBinding", CdpJson.obj("name", READY_BINDING))
                .thenCompose(ignored -> cdp.call(sessionId, "Page.addScriptToEvaluateOnNewDocument",
                        CdpJson.obj("source", SCRIPT, "runImmediately", true)))
                .thenCompose(ignored -> cdp.call(sessionId, "Runtime.addBinding",
                        CdpJson.obj("name", FULLSCREEN_BINDING, "executionContextName", FULLSCREEN_WORLD)))
                .thenCompose(ignored -> cdp.call(sessionId, "Runtime.addBinding",
                        CdpJson.obj("name", CADENCE_BINDING, "executionContextName", FULLSCREEN_WORLD)))
                .thenCompose(ignored -> cdp.call(sessionId, "Page.addScriptToEvaluateOnNewDocument",
                        CdpJson.obj("source", FULLSCREEN_SCRIPT, "worldName", FULLSCREEN_WORLD, "runImmediately", true)))
                .thenAccept(ignored -> {});
    }

    synchronized void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        contexts.forEach(this::apply);
    }

    synchronized void contextDestroyed(int contextId) {
        contexts.remove(contextId);
    }

    synchronized void contextsCleared() {
        contexts.clear();
    }

    boolean onBinding(String name, int contextId, String payload) {
        if (CADENCE_BINDING.equals(name)) {
            if ("start".equals(payload)) {
                cadenceChanged.accept(-1);
                return true;
            }
            try {
                int cadence = Integer.parseInt(payload);
                if (cadence >= 0 && cadence <= 60) cadenceChanged.accept(cadence);
            } catch (NumberFormatException ignored) {
                // Ignore unrecognized reports.
            }
            return true;
        }
        if (FULLSCREEN_BINDING.equals(name)) {
            if ("enter".equals(payload) || "exit".equals(payload)) {
                fullscreenChanged.accept("enter".equals(payload));
            }
            return true;
        }
        if (!READY_BINDING.equals(name)) return false;
        synchronized (this) {
            if (contextId > 0 && contexts.add(contextId) && paused) apply(contextId);
        }
        return true;
    }

    private void apply(int contextId) {
        cdp.fire(sessionId, "Runtime.evaluate", CdpJson.obj(
                "expression", "window.__wdcdpSetMediaPaused?.(" + paused + ")",
                "contextId", contextId));
    }
}
