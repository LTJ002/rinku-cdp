package com.wdcdp.cdpscreen.audio;

import java.util.Base64;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.wdcdp.cdpscreen.CdpLog;
import com.wdcdp.cdpscreen.cdp.CdpJson;
import com.wdcdp.cdpscreen.cdp.CdpPage;

/**
 * Gets page audio out of Chrome and into the game's audio output.
 *
 * <h2>Why this is not a CDP feature</h2>
 * <p>{@code Page.startScreencast} carries video only - the protocol has no audio
 * channel at all. CEF solved this with {@code CefAudioHandler}, handing the host
 * raw planar float samples because offscreen rendering has no output device. CDP
 * has no equivalent, so the audio has to be captured inside the page.</p>
 *
 * <h2>Why tapping AudioContext alone is not enough</h2>
 * <p>The obvious approach - wrap {@code AudioContext} and tap its graph - captures
 * nothing on a video site. A {@code <video>} element's audio does <b>not</b> flow
 * through the page's AudioContext; it goes straight to the media pipeline. Tapping
 * the context only catches Web Audio, so a Bilibili or YouTube page would stay
 * silent while every counter said the tap was installed. The element has to be
 * routed into the graph with {@code createMediaElementSource} first.</p>
 *
 * <p>That is what {@link #CAPTURE_SCRIPT} does: it finds media elements, routes
 * each one through a gain node into a dedicated AudioContext, and sends the
 * captured samples to the host through a CDP binding. The graph uses the
 * browser's original AudioContext constructor and leaves the page's own Web
 * Audio graphs alone.</p>
 */
public final class PageAudio {

    private static final Logger LOG = Logger.getLogger("wdcdp.audio");

    /**
     * Name of the CDP binding the page calls with base64 PCM.
     *
     * <p>Must not collide with any name the capture script uses for its own state.
     * It did: the script stored its state on {@code window.__wdcdpAudio} while the
     * binding was also {@code __wdcdpAudio}, so {@code Runtime.addBinding} installed
     * a function that the script immediately overwrote with its state object - and
     * every {@code send()} silently found nothing callable. The page looked
     * perfectly set up and delivered zero audio blocks.</p>
     */
    public static final String BINDING = "__wdcdpAudioPush";

    /** Sample rate the capture graph runs at. */
    private static final int SAMPLE_RATE = 48000;

    /** Frames per capture block. 1024 at 48 kHz is ~21 ms. */
    private static final int BLOCK_FRAMES = 1024;

    private PageAudio() {
    }

    /** Receives decoded audio blocks. */
    @FunctionalInterface
    public interface Sink {
        void accept(float[][] planar, int frames, int sampleRate);
    }

    /**
     * Install the capture script and the binding on a page.
     *
     * <p>Must run before navigation, so the script is present for the page's first
     * script - a player that starts on load would otherwise play before the graph
     * is patched and stay silent for its whole life.</p>
     *
     * <p><b>Audio is handled on its own thread.</b> The binding fires on the
     * DevTools WebSocket thread, and delivering a block ends in
     * {@code SourceDataLine.write}, which blocks until the device has room. Doing
     * that inline stalls the same thread that receives screencast frames, so a slow
     * audio device starves the video: the picture freezes and the sound stutters,
     * together, which is exactly the symptom this had. CEF never showed it because
     * its audio arrived on a separate callback thread. A single-threaded executor
     * restores that separation, and being single-threaded keeps the blocks in
     * order.</p>
     */
    /**
     * The thread audio blocks are delivered on.
     *
     * <p>One shared thread, not one per page: it is created once and lives for the
     * process, so repeatedly creating and destroying screens cannot accumulate
     * threads. Single-threaded because the mixer writes to a device in real time and
     * out-of-order blocks are audible gaps.</p>
     */
    /**
     * The thread audio blocks are delivered on.
     *
     * <p>One shared thread, not one per page: it is created once and lives for the
     * process, so repeatedly creating and destroying screens cannot accumulate
     * threads. Single-threaded because the mixer writes to a device in real time and
     * out-of-order blocks are audible gaps.</p>
     *
     * <p>The queue is bounded and discards the oldest block when full. Audio is
     * only useful in order and in real time: if the device falls behind, playing the
     * backlog later is worse than dropping it, and an unbounded queue would grow
     * until the process ran out of memory.</p>
     */
    private static final java.util.concurrent.ExecutorService AUDIO_THREAD =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(32),
                    runnable -> {
                        Thread thread = new Thread(runnable, "wdcdp-audio");
                        thread.setDaemon(true);
                        // Above the decoder pool: audio must not wait behind JPEG
                        // decoding, and a late block is an audible gap.
                        thread.setPriority(Thread.NORM_PRIORITY + 1);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    private static final java.util.Queue<Runnable> AUDIO_CONTROLS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void control(Runnable command) {
        AUDIO_CONTROLS.add(command);
        AUDIO_THREAD.execute(PageAudio::drainControls);
    }

    private static void drainControls() {
        Runnable command;
        while ((command = AUDIO_CONTROLS.poll()) != null) {
            try {
                command.run();
            } catch (RuntimeException e) {
                CdpLog.warn("Audio control failed", e);
            }
        }
    }

    public static java.util.concurrent.CompletableFuture<Void> install(CdpPage page, Runnable prepareOutput, Sink sink) {
        var prepared = new java.util.concurrent.atomic.AtomicBoolean();
        // The rate the page's AudioContext actually runs at. Chrome is free to
        // ignore the requested rate and use the device's, and playing a 44.1 kHz
        // stream through a line opened at 48 kHz is audible as crackle and hiss -
        // so the reported rate is used rather than the requested one.
        var actualRate = new java.util.concurrent.atomic.AtomicInteger(SAMPLE_RATE);
        return page.addBinding(BINDING, payload -> {
            if (payload == null || payload.isEmpty()) return;
            if ("stop".equals(payload)) {
                page.invalidateAudio();
                return;
            }
            if (payload.startsWith("rate:")) {
                try {
                    int reported = Integer.parseInt(payload.substring(5).trim());
                    if (reported >= 8000 && reported <= 192000) actualRate.set(reported);
                } catch (NumberFormatException ignored) {
                    // Keep the requested rate.
                }
                return;
            }
            if (payload.startsWith("clock:")) {
                // Evidence for one common failure: a silent output makes Chromium
                // advance the context clock from a coarse timer, so the page and the
                // captured PCM both run slower than real time.
                try {
                    double ratio = Double.parseDouble(payload.substring(6).trim());
                    CdpLog.info(String.format(java.util.Locale.ROOT,
                            "Page audio clock: %.3fx real time%s", ratio,
                            ratio < 0.9 ? " (slower than real time)" : ""));
                } catch (NumberFormatException ignored) {
                    // A malformed report is not worth failing a page over.
                }
                return;
            }
            if ("ready".equals(payload)) {
                if (!page.isClosed() && prepared.compareAndSet(false, true)) {
                    control(() -> {
                        if (!page.isClosed()) prepareOutput.run();
                    });
                }
                return;
            }
            long epoch = page.audioEpoch();
            if (!page.acceptsAudio()) return;
            int rate = actualRate.get();
            long queuedAt = System.nanoTime();
            AUDIO_THREAD.execute(() -> {
                drainControls();
                // Do not replay a late burst after a hitch or navigation.
                if (page.audioEpoch() != epoch || !page.acceptsAudio()
                        || System.nanoTime() - queuedAt > 200_000_000L) return;
                long count = blocksReceived.incrementAndGet();
                try {
                    float[][] planar = decode(payload, 2);
                    if (planar != null && planar.length > 0 && planar[0].length > 0) {
                        if (count == 1) {
                            CdpLog.info("First audio block: " + planar.length
                                    + " channel(s), " + planar[0].length + " frames at "
                                    + actualRate.get() + " Hz");
                        }
                        if (page.audioEpoch() == epoch && page.acceptsAudio()) {
                            sink.accept(planar, planar[0].length, rate);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.fine(() -> "Dropped an audio block: " + e);
                }
            });
        }).thenCompose(ignored -> page.addInitScript(CAPTURE_SCRIPT));
    }

    /** Blocks delivered by the page; zero means the tap never produced audio. */
    private static final java.util.concurrent.atomic.AtomicLong blocksReceived =
            new java.util.concurrent.atomic.AtomicLong();

    public static long blocksReceived() {
        return blocksReceived.get();
    }

    /**
     * Ask the page how its audio graph looks.
     *
     * <p>Silence has three causes that look identical from outside: the page plays
     * nothing, the media element was never routed, or the context is suspended.
     * One probe distinguishes them.</p>
     */
    public static void reportState(CdpPage page) {
        if (page == null) {
            return;
        }
        page.evaluateForValue(STATE_PROBE).thenAccept(value ->
                CdpLog.info("Page audio state: " + value
                        + " (blocks received: " + blocksReceived.get() + ")")
        ).exceptionally(error -> null);
    }

    private static final String STATE_PROBE =
            "(()=>{try{var n=window.__wdcdpAudio;"
                    + "if(!n)return 'no-tap-object';"
                    + "return JSON.stringify({"
                    + "ctx:n.ctx?n.ctx.state:null,"
                    + "rate:n.ctx?n.ctx.sampleRate:null,"
                    + "tapped:n.tapped||0,processor:n.processor||null,worklet:n.workletStatus,"
                    + "keepAlive:!!n.keepAlive,"
                    + "untapped:n.untapped||0,"
                    + "videos:document.querySelectorAll('video').length,"
                    + "playing:(function(){var v=document.querySelector('video');"
                    + "return v?(!v.paused&&v.currentTime>0):null})(),"
                    + "muted:(function(){var v=document.querySelector('video');"
                    + "return v?v.muted:null})()})}catch(e){return 'err:'+e}})()";

    /**
     * Base64 of interleaved float32 to planar float arrays.
     *
     * <p>The page sends interleaved because that is what one typed array gives; the
     * mixer wants planar because CEF's handler produced planar and
     * {@code BrowserAudio} already expects that shape.</p>
     */
    private static float[][] decode(String base64) {
        return decode(base64, 2);
    }

    /**
     * Decode interleaved floats to planar arrays at a known channel count.
     *
     * <p>The count is passed in rather than inferred. Inferring it from whether the
     * float count is even is wrong the moment a payload is short by one sample: a
     * stereo block would be read as mono and every value would land on the wrong
     * channel, which is heard as harsh noise rather than as a dropped block.</p>
     */
    private static float[][] decode(String base64, int channels) {
        byte[] raw = Base64.getDecoder().decode(base64);
        int usable = raw.length - (raw.length % (4 * channels));
        if (usable <= 0) {
            return null;
        }
        int frames = usable / (4 * channels);
        float[][] planar = new float[channels][frames];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                planar[channel][frame] = buffer.getFloat();
            }
        }
        return planar;
    }

    /**
     * The capture script.
     *
     * <p>Three things have to be true for a page to be audible in-game, and this
     * script establishes all three:</p>
     * <ol>
     *   <li>a running {@code AudioContext} - created here if the page has none,
     *       and resumed explicitly because a context with no user gesture starts
     *       suspended;</li>
     *   <li>every media element routed into that context with
     *       {@code createMediaElementSource}, since a {@code <video>} does not go
     *       through Web Audio on its own;</li>
     *   <li>a capture processor on the routed signal, forwarding float samples to
     *       the host binding.</li>
     * </ol>
     */
    private static final String CAPTURE_SCRIPT = """
            (function(){
              if (window.__wdcdpAudio) return;
              var RATE = %d, BLOCK = %d, NAME = '%s';
              var state = { ctx: null, mix: null, output: null, processor: null,
                            workletStatus: 'unavailable', sources: new Set(),
                            tapped: 0, routes: new WeakMap(), playing: new Set() };
              window.__wdcdpAudio = state;

              function canSend() {
                if (window.__wdcdpMediaPaused) return false;
                pruneSources();
                return state.playing.size > 0;
              }

              function send(buffer) {
                var binding = window[NAME];
                if (typeof binding !== 'function') return;
                var bytes = new Uint8Array(buffer);
                var encoded;
                if (typeof bytes.toBase64 === 'function') {
                  encoded = bytes.toBase64();
                } else {
                  var binary = '';
                  for (var offset = 0; offset < bytes.length; offset += 8192) {
                    binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
                  }
                  encoded = btoa(binary);
                }
                binding(encoded);
              }

              function useFallback(ctx) {
                var proc = ctx.createScriptProcessor(BLOCK, 2, 2);
                proc.onaudioprocess = function (event) {
                  // A worklet may have taken over since; one path sends at a time.
                  if (state.processor !== 'script') return;
                  if (!canSend()) return;
                  var left = event.inputBuffer.getChannelData(0);
                  var right = event.inputBuffer.getChannelData(1);
                  var samples = new Float32Array(BLOCK * 2);
                  for (var i = 0; i < BLOCK; i++) {
                    samples[2 * i] = left[i];
                    samples[2 * i + 1] = right[i];
                  }
                  send(samples.buffer);
                };
                state.mix.connect(proc);
                proc.connect(state.output);
                state.scriptProc = proc;
                state.processor = 'script';
              }

              function useWorklet(ctx) {
                var node = new AudioWorkletNode(ctx, 'wdcdp-capture', {
                  channelCount: 2, channelCountMode: 'explicit', outputChannelCount: [1],
                  processorOptions: {block: BLOCK}
                });
                node.port.onmessage = function (event) {
                  if (state.processor !== 'worklet') return;
                  if (canSend()) send(event.data);
                };
                node.connect(state.output);
                state.mix.connect(node);
                state.processor = 'worklet';
              }

              function setOutput(entry, output) {
                if (entry.active === output) return;
                // Track the output that is actually connected, not just the raw source.
                if (entry.active) {
                  try { entry.active.disconnect(state.mix); } catch (e) {}
                  entry.active = null;
                }
                if (output) {
                  output.connect(state.mix);
                  entry.active = output;
                }
              }

              function stopElement(media) {
                var entry = state.routes.get(media);
                if (!entry) return;
                setOutput(entry, null);
                state.playing.delete(media);
                if (state.sources.delete(entry) && state.sources.size === 0) {
                  var binding = window[NAME];
                  if (typeof binding === 'function') binding('stop');
                }
              }

              function pruneSources() {
                for (var entry of state.sources) {
                  var media = entry.media;
                  if (!media.isConnected || media.paused || media.ended || media.seeking) stopElement(media);
                }
              }

              function prepareWorklet(ctx) {
                if (!ctx.audioWorklet) return;
                var source = `                  class Capture extends AudioWorkletProcessor {
                    constructor(options) {
                      super();
                      this.size = options.processorOptions.block * 2;
                      this.samples = new Float32Array(this.size);
                      this.offset = 0;
                    }
                    process(inputs, outputs) {
                      const [left, right = left] = inputs[0] ?? [];
                      const frames = outputs[0][0].length;
                      for (let i = 0; i < frames; i++) {
                        this.samples[this.offset++] = left?.[i] ?? 0;
                        this.samples[this.offset++] = right?.[i] ?? 0;
                        if (this.offset === this.size) {
                          this.port.postMessage(this.samples.buffer, [this.samples.buffer]);
                          this.samples = new Float32Array(this.size);
                          this.offset = 0;
                        }
                      }
                      return true;
                    }
                  }
                  registerProcessor('wdcdp-capture', Capture);
                `;
                var moduleUrl;
                try {
                  moduleUrl = URL.createObjectURL(new Blob([source], {type: 'text/javascript'}));
                  state.workletStatus = 'loading';
                  ctx.audioWorklet.addModule(moduleUrl).then(function () {
                    state.workletStatus = 'ready';
                  }).catch(function () {
                    state.workletStatus = 'load-failed';
                  }).finally(function () { URL.revokeObjectURL(moduleUrl); });
                } catch (e) {
                  state.workletStatus = 'load-failed';
                  if (moduleUrl) URL.revokeObjectURL(moduleUrl);
                }
              }

              function connectProcessor(ctx) {
                if (state.processor) return;
                // Prefer the worklet: it runs on the audio thread, so a busy page
                // (video decode, site scripts) cannot starve it. The script
                // processor pins capture to the main thread, and every missed
                // callback is an audible crackle.
                if (state.workletStatus === 'ready') {
                  try {
                    useWorklet(ctx);
                    return;
                  } catch (e) {
                    state.workletStatus = 'node-failed';
                  }
                }
                // Don't delay the first audio packet or switch processors while
                // they may both have pending blocks. Prefer the prewarmed worklet.
                useFallback(ctx);
              }

              function ensureContext() {
                if (state.ctx) return state.ctx;
                var Constructor = window.AudioContext || window.webkitAudioContext;
                if (!Constructor) return null;
                try {
                  var ctx = new Constructor({sampleRate: RATE});
                  state.mix = ctx.createGain();
                  // Open over ~60 ms instead of jumping to full level. A page that
                  // starts playing while the graph is still being built otherwise
                  // hands the mixer a block that begins mid-waveform at full gain,
                  // which is heard as a click/thump on every page load.
                  try {
                    state.mix.gain.setValueAtTime(0, ctx.currentTime);
                    state.mix.gain.linearRampToValueAtTime(1, ctx.currentTime + 0.06);
                  } catch (e) { state.mix.gain.value = 1; }
                  state.output = ctx.createGain();
                  state.output.gain.value = 0;
                  state.output.connect(ctx.destination);
                  state.ctx = ctx;
                  // Keep the output device awake with an inaudible tone.
                  //
                  // The page's own audio is muted above, so the graph renders
                  // silence and Chromium suspends the suspended-silent sink and
                  // advances currentTime from a coarse timer instead. Measured in
                  // this build: the clock then ran at 0.65x real time, and because
                  // a tapped <video> follows that clock the picture played at 0.65x
                  // (25 fps content shown at ~15 fps) while the PCM reached the game
                  // slower than the mixer consumes it, which is the constant
                  // "device buffer emptied" rebuffering. A -74 dBFS tone is
                  // inaudible and keeps the real device clock in charge.
                  try {
                    var keepAlive = ctx.createOscillator();
                    keepAlive.frequency.value = 100;
                    var keepGain = ctx.createGain();
                    keepGain.gain.value = 0.0002;
                    keepAlive.connect(keepGain);
                    keepGain.connect(ctx.destination);
                    keepAlive.start();
                    state.keepAlive = keepAlive;
                  } catch (e) { /* The clock is still usable, just slower. */ }
                  var binding = window[NAME];
                  // Report the rate the context really runs at before announcing
                  // readiness: Chrome may ignore the requested rate, and the output
                  // line has to be opened at the real one or playback crackles.
                  if (typeof binding === 'function') {
                    try { binding('rate:' + ctx.sampleRate); } catch (e) {}
                    try { binding('ready'); } catch (e) {}
                  }
                  prepareWorklet(ctx);
                  // One-shot proof that the context clock tracks real time.
                  //
                  // Chromium only suspends a silent sink after 30 s of exactly zero
                  // frames, so an early probe would always read 1.0 and prove nothing.
                  // Sampling after that window is what distinguishes a real device
                  // clock (~1.0) from the coarse timer (~0.65).
                  setTimeout(function () {
                    try {
                      var t0 = ctx.currentTime, w0 = performance.now();
                      setTimeout(function () {
                        try {
                          var seconds = (performance.now() - w0) / 1000;
                          var report = window[NAME];
                          if (seconds > 0.5 && typeof report === 'function') {
                            report('clock:' + ((ctx.currentTime - t0) / seconds));
                          }
                        } catch (e) {}
                      }, 5000);
                    } catch (e) {}
                  }, 30000);
                  return ctx;
                } catch (e) { return null; }
              }

              // Bilibili attaches its effects graph after playback has started.
              // A media element can only have one native Web Audio source.
              if (location.hostname === 'www.bilibili.com' && typeof AudioContext === 'function') {
                installBilibiliEffectsBridge();
              }

              function installBilibiliEffectsBridge() {
                var prototype = AudioContext.prototype;
                var createSource = prototype.createMediaElementSource;
                var connect = AudioNode.prototype.connect;
                var disconnect = AudioNode.prototype.disconnect;
                var graphs = new WeakMap();

                // Route only the effects graph's output to the game's PCM tap.
                AudioNode.prototype.connect = function (destination, ...args) {
                  var graph = graphs.get(this.context);
                  if (!graph || destination !== this.context.destination) {
                    return connect.call(this, destination, ...args);
                  }
                  connect.call(this, graph.output, ...args);
                  if (!graph.senders.has(this)) graph.senders.set(this, new Set());
                  graph.senders.get(this).add(args[0] ?? 0);
                  graph.selectOutput();
                  return destination;
                };
                AudioNode.prototype.disconnect = function (...args) {
                  var graph = graphs.get(this.context);
                  if (!graph) return disconnect.apply(this, args);
                  var toDestination = args.length && args[0] === this.context.destination;
                  if (toDestination) args[0] = graph.output;
                  var result = disconnect.apply(this, args);
                  if (toDestination && args.length > 1) {
                    var outputs = graph.senders.get(this);
                    outputs?.delete(args[1]);
                    if (outputs?.size === 0) graph.senders.delete(this);
                  } else if (toDestination || args.length === 0) {
                    graph.senders.delete(this);
                  } else if (typeof args[0] === 'number') {
                    var outputs = graph.senders.get(this);
                    outputs?.delete(args[0]);
                    if (outputs?.size === 0) graph.senders.delete(this);
                  }
                  graph.selectOutput();
                  return result;
                };

                prototype.createMediaElementSource = function (media) {
                  var entry = state.routes.get(media);
                  if (!entry || this === state.ctx || !media.closest('.bpx-player-video-wrap')) {
                    return createSource.call(this, media);
                  }
                  var graph = graphs.get(this);
                  if (graph && graph.media !== media) return createSource.call(this, media);
                  if (graph) return graph.input;
                  // Feed the actual native source into the effects graph.
                  // On Bilibili, captureStream() could expose a silent track
                  // once this element had already been routed through Web Audio.
                  var bridge = state.ctx.createMediaStreamDestination();
                  var input = this.createMediaStreamSource(bridge.stream);
                  var raw = entry.source;
                  connect.call(raw, bridge);
                  Object.defineProperty(input, 'mediaElement', {value: media});
                  var output = this.createMediaStreamDestination();
                  var processed = state.ctx.createMediaStreamSource(output.stream);
                  var context = this;
                  var senders = new Map();
                  function selectOutput() {
                    entry.output = context.state === 'running' && senders.size > 0 ? processed : raw;
                    if (state.sources.has(entry)) {
                      if (!media.isConnected || media.paused || media.ended) stopElement(media);
                      else setOutput(entry, entry.output);
                    }
                    if (context.state === 'closed') {
                      disconnect.call(raw, bridge);
                      for (var track of bridge.stream.getTracks()) track.stop();
                      for (var track of output.stream.getTracks()) track.stop();
                      senders.clear();
                      graphs.delete(context);
                      context.removeEventListener('statechange', selectOutput);
                    }
                  }
                  graphs.set(context, {media, input, output, senders, selectOutput});
                  context.addEventListener('statechange', selectOutput);
                  return input;
                };
              }

              function tapElement(media) {
                if (!(media instanceof HTMLMediaElement) || !media.isConnected
                    || media.paused || media.ended || media.seeking) return;
                var ctx = ensureContext();
                if (!ctx) return;
                pruneSources();
                var entry = state.routes.get(media);
                if (!entry) {
                  var source;
                  try { source = ctx.createMediaElementSource(media); }
                  catch (e) { source = tapByStream(ctx, media); }
                  if (!source) {
                    state.untapped = (state.untapped || 0) + 1;
                    return;
                  }
                  entry = {media, source, output: source, active: null};
                  state.routes.set(media, entry);
                  state.tapped++;
                }
                connectProcessor(ctx);
                setOutput(entry, entry.output);
                state.sources.add(entry);
                state.playing.add(media);
                if (ctx.state === 'suspended') ctx.resume().catch(function () {});
              }

              function tapByStream(ctx, media) {
                if (typeof media.captureStream !== 'function') return null;
                try {
                  var stream = media.captureStream();
                  if (!stream.getAudioTracks().length) return null;
                  return ctx.createMediaStreamSource(stream);
                } catch (e) { return null; }
              }

              function scan() {
                var elements = document.querySelectorAll('video,audio');
                if (elements.length !== 0) ensureContext();
                elements.forEach(tapElement);
              }

              // A paused media element gives module loading a head start without
              // delaying playback or changing the element's output yet.
              document.addEventListener('loadedmetadata', function (event) {
                if (event.target instanceof HTMLMediaElement) ensureContext();
              }, true);
              document.addEventListener('play', function (event) { tapElement(event.target); }, true);
              document.addEventListener('playing', function (event) { tapElement(event.target); }, true);
              document.addEventListener('seeked', function (event) { tapElement(event.target); }, true);
              for (var event of ['pause', 'ended', 'emptied', 'seeking']) {
                document.addEventListener(event, function (e) { stopElement(e.target); }, true);
              }
              document.addEventListener('DOMContentLoaded', scan, { once: true });
              window.addEventListener('load', scan, { once: true });
              scan();
              // Warm the worklet module while the page is idle, so the first
              // playback - which sites often start immediately after a navigation -
              // finds it ready instead of pinning the main-thread processor for the
              // whole session. This is what kept the fallback alive on pages that
              // autoplay, and the fallback is what drops samples under load.
              setTimeout(function () {
                try {
                  var ctx = ensureContext();
                  if (!ctx) return;
                  // Route nothing yet; connecting a processor needs a real tap, but
                  // loading the module does not.
                  if (state.workletStatus === 'unavailable') prepareWorklet(ctx);
                } catch (e) {}
              }, 0);
            })();
            """.formatted(SAMPLE_RATE, BLOCK_FRAMES, BINDING);

    /** Payload decoder for the {@code Runtime.bindingCalled} event. */
    public static String payloadOf(JsonObject params) {
        return CdpJson.string(params, "payload", null);
    }
}
