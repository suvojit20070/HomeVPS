package com.suvo.homevps.server;

import android.util.Log;

import com.suvo.homevps.session.Session;
import com.suvo.homevps.session.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dependency-free HTTP/1.1 server exposing the session-manager API.
 *
 * Routes (all under Bearer-token auth except GET /, /terminal and /health):
 *   GET    /  or /terminal                 web terminal page
 *   GET    /health
 *   GET    /sessions
 *   POST   /sessions                      {name, cwd}
 *   DELETE /sessions/{name}
 *   POST   /sessions/{name}/exec          {command, timeout, stream}
 *   POST   /sessions/{name}/input         {data}
 *   POST   /sessions/{name}/interrupt     Ctrl+C the running command
 *   GET    /sessions/{name}/output?since=N&tail=M
 *   GET    /sessions/{name}/stream        (WebSocket upgrade)
 *
 * Auth: "Authorization: Bearer <key>", "X-API-Key: <key>", or "?key=<key>"
 * (the query form exists because browsers cannot set headers on WebSockets).
 *
 * exec with "stream": true returns Server-Sent Events (text/event-stream):
 *   event: output / error   data: {"line": "...", "offset": N}
 *   event: ping             data: {}
 *   event: done             data: {"exit_code": N, "timed_out": bool, "duration_ms": N}
 */
public class ApiServer {

    private static final String TAG = "ApiServer";

    private final int port;
    private final String apiKey;
    private final SessionManager sessions;
    private final String terminalHtml;
    private final long startedAt = System.currentTimeMillis();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public ApiServer(int port, String apiKey, SessionManager sessions, String terminalHtml) {
        this.port = port;
        this.apiKey = apiKey;
        this.sessions = sessions;
        this.terminalHtml = terminalHtml;
    }

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        pool = Executors.newCachedThreadPool();
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "api-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Listening on 0.0.0.0:" + port);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = serverSocket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept error", e);
            }
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---------------------------------------------------------------- request

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String rawPath = parts[1];

            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) {
                    headers.put(h.substring(0, c).trim().toLowerCase(Locale.US),
                            h.substring(c + 1).trim());
                }
            }

            String path = rawPath;
            String query = "";
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                path = rawPath.substring(0, q);
                query = rawPath.substring(q + 1);
            }

            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = 0;
                try {
                    len = Integer.parseInt(cl.trim());
                } catch (NumberFormatException ignored) {
                }
                body = readBody(in, len);
            }

            route(client, in, out, method, path, query, headers, body);
        } catch (Exception e) {
            Log.w(TAG, "handler error", e);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(Socket client, InputStream in, OutputStream out,
                       String method, String path, String query,
                       Map<String, String> headers, byte[] body) throws Exception {

        if (method.equals("OPTIONS")) {
            // CORS preflight, so the terminal page also works when opened from another origin.
            String resp = "HTTP/1.1 204 No Content\r\n"
                    + CORS_HEADERS
                    + "Access-Control-Max-Age: 86400\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
            close(client);
            return;
        }

        if (method.equals("GET") && (path.equals("/") || path.equals("/terminal"))) {
            sendHtml(out, terminalHtml);
            close(client);
            return;
        }

        if (path.equals("/health")) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("service", "home-vps");
            o.put("uptime_ms", System.currentTimeMillis() - startedAt);
            o.put("sessions", sessions.aliveCount());
            o.put("shell", sessions.isLinux() ? "ubuntu" : "android-sh");
            sendJson(out, 200, o);
            close(client);
            return;
        }

        // Auth for everything else.
        if (!authorized(headers, parseQuery(query))) {
            sendJson(out, 401, err("unauthorized"));
            close(client);
            return;
        }

        String[] seg = splitPath(path);

        if (seg.length == 1 && seg[0].equals("sessions")) {
            if (method.equals("GET")) {
                handleList(out);
                close(client);
                return;
            }
            if (method.equals("POST")) {
                handleCreate(out, body);
                close(client);
                return;
            }
        }

        if (seg.length >= 2 && seg[0].equals("sessions")) {
            String name = seg[1];
            Session s = sessions.get(name);

            if (seg.length == 2 && method.equals("DELETE")) {
                boolean ok = sessions.remove(name);
                sendJson(out, ok ? 200 : 404, ok ? okObj("stopped") : err("not found"));
                close(client);
                return;
            }

            if (seg.length == 3 && seg[2].equals("exec") && method.equals("POST")) {
                handleExec(client, out, s, name, body);
                return; // connection managed inside (SSE may keep it open)
            }

            if (seg.length == 3 && seg[2].equals("input") && method.equals("POST")) {
                handleInput(out, s, body);
                close(client);
                return;
            }

            if (seg.length == 3 && seg[2].equals("interrupt") && method.equals("POST")) {
                if (s == null || !s.isAlive()) {
                    sendJson(out, 404, err("session not found or stopped"));
                } else {
                    JSONObject o = new JSONObject();
                    o.put("status", "interrupted");
                    o.put("signalled", s.interrupt());
                    sendJson(out, 200, o);
                }
                close(client);
                return;
            }

            if (seg.length == 3 && seg[2].equals("output") && method.equals("GET")) {
                handleOutput(out, s, query);
                close(client);
                return;
            }

            if (seg.length == 3 && seg[2].equals("stream") && method.equals("GET")) {
                handleWebSocket(client, in, out, s, headers);
                return;
            }
        }

        sendJson(out, 404, err("not found"));
        close(client);
    }

    // ---------------------------------------------------------------- routes

    private void handleList(OutputStream out) throws Exception {
        JSONArray arr = new JSONArray();
        for (Session s : sessions.list()) {
            JSONObject o = new JSONObject();
            o.put("name", s.name);
            o.put("cwd", s.cwd);
            o.put("status", s.isAlive() ? (s.isBusy() ? "busy" : "running") : "stopped");
            o.put("created_at", s.createdAt);
            o.put("last_activity", s.lastActivity);
            o.put("last_exit_code", s.getLastExitCode());
            o.put("viewers", s.listenerCount());
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("sessions", arr);
        sendJson(out, 200, root);
    }

    private void handleCreate(OutputStream out, byte[] body) throws Exception {
        JSONObject in = parse(body);
        String name = in.optString("name", "").trim();
        String cwd = in.optString("cwd", "").trim();
        if (!SessionManager.isValidName(name)) {
            sendJson(out, 400, err("invalid name (use A-Z a-z 0-9 _ . - , max 40)"));
            return;
        }
        try {
            Session s = sessions.create(name, cwd);
            JSONObject o = new JSONObject();
            o.put("name", s.name);
            o.put("cwd", s.cwd);
            o.put("status", "running");
            sendJson(out, 201, o);
        } catch (SessionManager.LimitException e) {
            sendJson(out, 409, err(e.getMessage()));
        } catch (IOException e) {
            sendJson(out, 500, err("failed to start shell: " + e.getMessage()));
        }
    }

    private void handleInput(OutputStream out, Session s, byte[] body) throws Exception {
        if (s == null || !s.isAlive()) {
            sendJson(out, 404, err("session not found or stopped"));
            return;
        }
        JSONObject in = parse(body);
        String data = in.optString("data", "");
        try {
            s.sendInput(data);
            sendJson(out, 200, okObj("sent"));
        } catch (IOException e) {
            sendJson(out, 500, err("write failed: " + e.getMessage()));
        }
    }

    private void handleOutput(OutputStream out, Session s, String query) throws Exception {
        if (s == null) {
            sendJson(out, 404, err("session not found"));
            return;
        }
        Map<String, String> qp = parseQuery(query);
        long since = parseLong(qp.get("since"), 0);
        int tail = (int) parseLong(qp.get("tail"), -1);

        List<Session.Entry> entries = tail > 0 ? s.tail(tail) : s.outputSince(since);
        JSONArray arr = new JSONArray();
        for (Session.Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("offset", e.offset);
            o.put("line", e.text);
            o.put("stderr", e.stderr);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("lines", arr);
        root.put("next_offset", s.nextOffset());
        root.put("alive", s.isAlive());
        sendJson(out, 200, root);
    }

    private void handleExec(Socket client, OutputStream out, Session s, String name, byte[] body)
            throws Exception {
        if (s == null || !s.isAlive()) {
            sendJson(out, 404, err("session not found or stopped"));
            close(client);
            return;
        }
        JSONObject in = parse(body);
        final String command = in.optString("command", "");
        final long timeout = in.optLong("timeout", 60_000);
        boolean stream = in.optBoolean("stream", false);

        if (!stream) {
            Session.ExecResult r = s.exec(command, timeout, null);
            JSONObject o = new JSONObject();
            o.put("output", r.output);
            o.put("exit_code", r.exitCode);
            o.put("timed_out", r.timedOut);
            o.put("stopped", r.stopped);
            o.put("duration_ms", r.durationMs);
            sendJson(out, 200, o);
            close(client);
            return;
        }

        // SSE streaming.
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + CORS_HEADERS
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "X-Accel-Buffering: no\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();

        final Object writeLock = new Object();
        final AtomicBoolean open = new AtomicBoolean(true);

        // Keep-alive pings so Cloudflare / proxies don't drop a long command.
        Thread pinger = new Thread(() -> {
            while (open.get()) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (writeLock) {
                    if (!open.get()) return;
                    try {
                        sseEvent(out, "ping", "{}");
                    } catch (IOException e) {
                        open.set(false);
                        return;
                    }
                }
            }
        }, "sse-ping");
        pinger.setDaemon(true);
        pinger.start();

        Session.LineSink sink = (text, stderr) -> {
            if (!open.get()) return;
            synchronized (writeLock) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("line", text);
                    sseEvent(out, stderr ? "error" : "output", o.toString());
                } catch (Exception e) {
                    open.set(false);
                }
            }
        };

        Session.ExecResult r = s.exec(command, timeout, sink);
        open.set(false);
        pinger.interrupt();

        synchronized (writeLock) {
            try {
                JSONObject done = new JSONObject();
                done.put("exit_code", r.exitCode);
                done.put("timed_out", r.timedOut);
                done.put("stopped", r.stopped);
                done.put("duration_ms", r.durationMs);
                sseEvent(out, "done", done.toString());
            } catch (Exception ignored) {
            }
        }
        close(client);
    }

    private void handleWebSocket(Socket client, InputStream in, OutputStream out,
                                 final Session s, Map<String, String> headers) {
        String key = headers.get("sec-websocket-key");
        if (s == null || key == null) {
            try {
                sendJson(out, 400, err("session not found or missing ws key"));
            } catch (Exception ignored) {
            }
            close(client);
            return;
        }
        final WebSocket ws = new WebSocket(in, out);
        try {
            WebSocket.writeHandshake(out, key);
        } catch (Exception e) {
            close(client);
            return;
        }

        // Replay the recent buffer, then stream live.
        final Object wsLock = new Object();
        Session.Listener listener = new Session.Listener() {
            @Override
            public void onLine(String text, long offset, boolean stderr) {
                sendLine(text, stderr, offset);
            }

            @Override
            public void onClosed(int exitCode) {
                synchronized (wsLock) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "closed");
                        o.put("exit_code", exitCode);
                        ws.sendText(o.toString());
                    } catch (Exception ignored) {
                    }
                }
                ws.close();
            }

            private void sendLine(String text, boolean stderr, long offset) {
                synchronized (wsLock) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", stderr ? "error" : "output");
                        o.put("line", text);
                        o.put("offset", offset);
                        ws.sendText(o.toString());
                    } catch (Exception ignored) {
                    }
                }
            }
        };

        try {
            for (Session.Entry e : s.tail(200)) {
                JSONObject o = new JSONObject();
                o.put("type", e.stderr ? "error" : "output");
                o.put("line", e.text);
                o.put("offset", e.offset);
                synchronized (wsLock) {
                    ws.sendText(o.toString());
                }
            }
        } catch (Exception ignored) {
        }

        s.addListener(listener);
        try {
            WebSocket.Frame frame;
            while ((frame = ws.readFrame()) != null) {
                if (frame.opcode == WebSocket.OP_CLOSE) break;
                if (frame.opcode == WebSocket.OP_PING) {
                    ws.sendFrame(WebSocket.OP_PONG, frame.payload);
                    continue;
                }
                if (frame.opcode == WebSocket.OP_TEXT) {
                    handleWsMessage(s, frame.text());
                }
            }
        } catch (IOException ignored) {
        } finally {
            s.removeListener(listener);
            ws.close();
            close(client);
        }
    }

    private void handleWsMessage(Session s, String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "input");
            if (type.equals("input")) {
                s.sendInput(msg.optString("data", ""));
            } else if (type.equals("signal")) {
                String name = msg.optString("name", "");
                if (name.equals("SIGINT")) s.interrupt();
                else if (name.equals("EOF")) s.sendInput("\u0004");
            }
            // "resize" is a no-op until the Phase 2 PTY lands.
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean authorized(Map<String, String> headers, Map<String, String> query) {
        String q = query.get("key");
        if (q != null && constantEquals(q.trim(), apiKey)) return true;
        String auth = headers.get("authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return constantEquals(auth.substring(7).trim(), apiKey);
        }
        String alt = headers.get("x-api-key");
        return alt != null && constantEquals(alt.trim(), apiKey);
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String[] splitPath(String path) {
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return new String[0];
        String[] raw = p.split("/");
        for (int i = 0; i < raw.length; i++) {
            try {
                raw[i] = URLDecoder.decode(raw[i], "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
        }
        return m;
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static JSONObject parse(byte[] body) {
        try {
            if (body == null || body.length == 0) return new JSONObject();
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject okObj(String status) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void sseEvent(OutputStream out, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append('\n');
        sb.append("data: ").append(data).append("\n\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendJson(OutputStream out, int status, JSONObject body) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + statusText(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + CORS_HEADERS
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static final String CORS_HEADERS =
            "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key\r\n"
                    + "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n";

    private static void sendHtml(OutputStream out, String html) throws IOException {
        byte[] payload = (html == null ? "<h1>Terminal page missing</h1>" : html)
                .getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static void close(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        int prev = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (prev == '\r') {
                buf.write('\r');
            }
            if (c != '\r') {
                buf.write(c);
            }
            prev = c;
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return buf;
    }
}
