package com.wdcdp.cdpscreen.cdp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One DevTools WebSocket, multiplexed across every page by {@code sessionId}.
 *
 * <p><b>Why flatten.</b> Without {@code Target.attachToTarget(flatten:true)} each
 * page needs its own WebSocket, so N screens means N connections and N reader
 * loops. With flatten, all pages share this one socket and are addressed by the
 * {@code sessionId} field. The rule that matters: <b>every page-level command
 * must carry a sessionId</b>, otherwise it is delivered to the browser target and
 * silently does nothing.</p>
 *
 * <p><b>Why the frame fast path.</b> A 1920x1080 JPEG at q80 is about 96 KB,
 * which becomes ~128 KB of base64 inside a JSON envelope. Parsing that with Gson
 * allocates a full tree per frame at 20 fps per screen. The scanner below pulls
 * the base64 payload straight out of the raw text and only parses the small
 * remainder, which is what keeps three screens affordable.</p>
 */
public final class CdpClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("wdcdp.cdp");

    /** Receives unsolicited CDP events. */
    @FunctionalInterface
    public interface EventSink {
        void accept(String sessionId, String method, JsonObject params);

        /** Compatibility overload for callers that supply decoded frame bytes. */
        default void accept(String sessionId, String method, JsonObject params,
                            byte[] payload) {
            accept(sessionId, method, params);
        }
    }

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectHandlers = new CopyOnWriteArrayList<>();

    private volatile WebSocket socket;
    private volatile boolean closed;
    private CompletableFuture<WebSocket> sendChain;

    private CdpClient(HttpClient http) {
        this.http = http;
    }

    /** Connect to a {@code ws://} DevTools endpoint. */
    public static CompletableFuture<CdpClient> connect(URI uri, Duration connectTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        CdpClient client = new CdpClient(http);
        return http.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(uri, new Reader(client))
                .thenApply(webSocket -> {
                    client.attach(webSocket);
                    return client;
                })
                .whenComplete((result, error) -> {
                    if (error != null) {
                        http.shutdownNow();
                    }
                });
    }

    private synchronized void attach(WebSocket webSocket) {
        this.socket = webSocket;
        this.sendChain = CompletableFuture.completedFuture(webSocket);
    }

    public void onEvent(EventSink sink) {
        sinks.add(sink);
    }

    public void onDisconnect(Runnable handler) {
        disconnectHandlers.add(handler);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Send a command and wait for its result.
     *
     * @param sessionId page session, or null for a browser-level command
     */
    public CompletableFuture<JsonObject> call(String sessionId, String method, JsonObject params) {
        if (closed) {
            return CompletableFuture.failedFuture(new CdpException(method + ": connection is closed"));
        }
        int id = ids.incrementAndGet();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        pending.put(id, result);
        result.orTimeout(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .whenComplete((value, error) -> pending.remove(id));
        write(id, sessionId, method, params);
        return result;
    }

    /**
     * Send a command without waiting for the result.
     *
     * <p>Used for the high-frequency calls (input, screencast acks) where a reply
     * has no information we act on and allocating a future per event would just
     * be garbage.</p>
     */
    public void fire(String sessionId, String method, JsonObject params) {
        if (!closed) {
            write(ids.incrementAndGet(), sessionId, method, params);
        }
    }

    private void write(int id, String sessionId, String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        if (sessionId != null) {
            message.addProperty("sessionId", sessionId);
        }
        send(message.toString());
    }

    private synchronized void send(String text) {
        if (closed || sendChain == null) {
            return;
        }
        sendChain = sendChain
                .thenCompose(webSocket -> webSocket.sendText(text, true))
                .exceptionally(error -> {
                    if (!closed) {
                        LOG.log(Level.FINE, "CDP send failed", error);
                    }
                    return socket;
                });
    }

    // ------------------------------------------------------- frame fast path

    static final String FRAME_EVENT = "Page.screencastFrame";
    private static final String FRAME_METHOD = "\"method\":\"" + FRAME_EVENT + "\"";
    private static final String DATA_KEY = "\"data\":\"";
    private static final String SESSION_NUMBER_KEY = "\"sessionId\":";
    private static final String SESSION_TEXT_KEY = "\"sessionId\":\"";

    /** How far into the message the event name can sit. */
    private static final int HEAD = 160;

    private void dispatch(String message) {
        // Try the compact Chrome frame envelope before parsing the JSON tree.
        // Unrecognized layouts fall back to the regular event dispatcher.
        int frameMethod = message.length() > HEAD ? message.lastIndexOf(FRAME_METHOD, HEAD) : -1;
        if (frameMethod >= 0 && dispatchFrame(message, frameMethod + FRAME_METHOD.length())) {
            return;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warning("Unreadable CDP message (" + message.length() + " chars)");
            return;
        }

        if (root.has("id")) {
            CompletableFuture<JsonObject> waiting = pending.remove(root.get("id").getAsInt());
            if (waiting == null) {
                return;
            }
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                waiting.completeExceptionally(
                        new CdpException(CdpJson.string(error, "message", "unknown error")));
            } else {
                waiting.complete(CdpJson.child(root, "result"));
            }
            return;
        }

        if (!root.has("method")) {
            return;
        }
        dispatchEvent(CdpJson.string(root, "sessionId", null), root.get("method").getAsString(),
                CdpJson.child(root, "params"));
    }

    private void dispatchEvent(String sessionId, String method, JsonObject params) {
        for (EventSink sink : sinks) {
            try {
                sink.accept(sessionId, method, params);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "CDP event handler for " + method + " failed", e);
            }
        }
    }

    /**
     * Pull a screencast frame out of the raw text.
     *
     * @return true when the message was a frame and has been dispatched
     */
    private boolean dispatchFrame(String message, int start) {
        int at = message.indexOf(DATA_KEY, start);
        if (at < 0) {
            return false;
        }
        int from = at + DATA_KEY.length();
        // base64 never contains a quote, so the next quote ends the payload.
        int end = message.indexOf('"', from);
        if (end < 0) {
            return false;
        }

        // Two distinct sessionIds appear here: the page's (a string) and the
        // frame's ack token (a number). If their order differs, let Gson parse it.
        String session = quotedValue(message, SESSION_TEXT_KEY, end);
        int ackToken = numberValue(message, SESSION_NUMBER_KEY, end);
        if (session == null || ackToken < 0) {
            return false;
        }

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", ackToken);
        params.addProperty("data", message.substring(from, end));
        dispatchEvent(session, FRAME_EVENT, params);
        return true;
    }

    private static String quotedValue(String message, String key, int start) {
        int at = message.indexOf(key, start);
        if (at < 0) {
            return null;
        }
        int from = at + key.length();
        int end = message.indexOf('"', from);
        return end < 0 ? null : message.substring(from, end);
    }

    private static int numberValue(String message, String key, int start) {
        for (int at = message.indexOf(key, start); at >= 0; at = message.indexOf(key, at + 1)) {
            int from = at + key.length();
            int end = from;
            while (end < message.length() && Character.isDigit(message.charAt(end))) {
                end++;
            }
            if (end > from && end - from < 10) {
                return Integer.parseInt(message, from, end, 10);
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ lifecycle

    private void terminate(Throwable cause) {
        if (closed) {
            return;
        }
        closed = true;
        CdpException failure = new CdpException("DevTools connection lost", cause);
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        for (Runnable handler : disconnectHandlers) {
            try {
                handler.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Disconnect handler failed", e);
            }
        }
        http.shutdownNow();
    }

    @Override
    public void close() {
        WebSocket webSocket = socket;
        closed = true;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException e) {
                webSocket.abort();
            }
        }
        pending.values().forEach(future ->
                future.completeExceptionally(new CdpException("DevTools connection closed")));
        pending.clear();
        http.shutdownNow();
    }

    private static final class Reader implements WebSocket.Listener {
        private final CdpClient owner;
        private final StringBuilder buffer = new StringBuilder(1 << 16);

        private Reader(CdpClient owner) {
            this.owner = owner;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                // Keep the buffer from pinning the largest frame ever seen.
                if (buffer.capacity() > (1 << 22)) {
                    buffer.trimToSize();
                }
                try {
                    owner.dispatch(message);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Failed to handle a CDP message", e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            owner.terminate(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            owner.terminate(error);
        }
    }
}
