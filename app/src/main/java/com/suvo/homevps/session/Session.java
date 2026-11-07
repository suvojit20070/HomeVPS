package com.suvo.homevps.session;

import com.suvo.homevps.linux.LinuxEnv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One named shell session, modeled on a Termux/tmux session.
 *
 * A single long-lived shell process is kept alive. Commands are written to its
 * stdin; two reader threads read stdout and stderr line by line and fan every
 * line out to:
 *   - a ring buffer (so a reconnecting client can replay missed lines)
 *   - all attached listeners (live SSE / WebSocket viewers)
 *   - the capture sink of the currently running exec() call
 *
 * Exit codes are recovered with a marker: after each command we write
 * `echo __END_<token>_$?`, and when the stdout reader sees that marker line it
 * knows the command finished and with which code.
 *
 * The shell is either Ubuntu bash under proot (once Linux is installed) or the
 * Android system shell as a fallback; see {@link LinuxEnv#currentSpec()}.
 * Sessions use pipes, not a PTY, which keeps output clean for agents
 * (separate stderr, reliable exit codes).
 */
public class Session {

    public interface LineSink {
        void line(String text, boolean stderr);
    }

    public interface Listener {
        void onLine(String text, long offset, boolean stderr);

        void onClosed(int exitCode);
    }

    public static class Entry {
        public final long offset;
        public final String text;
        public final boolean stderr;

        Entry(long offset, String text, boolean stderr) {
            this.offset = offset;
            this.text = text;
            this.stderr = stderr;
        }
    }

    public static class ExecResult {
        public final String output;
        public final int exitCode;
        public final boolean timedOut;
        public final boolean stopped;
        public final long durationMs;

        ExecResult(String output, int exitCode, boolean timedOut, boolean stopped, long durationMs) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stopped = stopped;
            this.durationMs = durationMs;
        }
    }

    private static final String MARK = "__END_";
    private static final int BUFFER_MAX_LINES = 5000;
    private static final long BUFFER_MAX_CHARS = 1024 * 1024;

    public final String name;
    public final String cwd;
    public final long createdAt = System.currentTimeMillis();
    public volatile long lastActivity = System.currentTimeMillis();

    private final Process proc;
    private final OutputStream stdin;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong offsetCounter = new AtomicLong(0);

    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private long ringChars = 0;
    private final Object ringLock = new Object();

    // exec() coordination. Only one command runs at a time per session.
    private final Object execLock = new Object();
    private final Object finishLock = new Object();
    private volatile String pendingToken = null;
    private volatile LineSink captureSink = null;
    private volatile StringBuilder captureBuf = null;
    private volatile boolean finishedFlag = false;
    private volatile int finishedCode = -1;

    private volatile boolean alive = true;
    private volatile boolean busy = false;
    private volatile int lastExitCode = 0;
    private final AtomicInteger readersLeft = new AtomicInteger(2);

    public Session(String name, String cwd, LinuxEnv.ShellSpec spec) throws IOException {
        this.name = name;
        this.cwd = (cwd == null || cwd.isEmpty()) ? spec.defaultCwd : cwd;

        ProcessBuilder pb = new ProcessBuilder(spec.command);
        pb.redirectErrorStream(false);
        pb.environment().putAll(spec.env);
        pb.directory(spec.hostDir);
        this.proc = pb.start();
        this.stdin = proc.getOutputStream();

        startReader(proc.getInputStream(), false);
        startReader(proc.getErrorStream(), true);

        // mkdir/cd run inside the shell, so the path is the guest (Ubuntu) path.
        String q = shellQuote(this.cwd);
        writeRaw("mkdir -p " + q + " 2>/dev/null; cd " + q + " 2>/dev/null\n");
    }

    private void startReader(InputStream stream, boolean isErr) {
        Thread t = new Thread(() -> readLoop(stream, isErr),
                "session-" + name + (isErr ? "-err" : "-out"));
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(InputStream stream, boolean isErr) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                handleLine(line, isErr);
            }
        } catch (IOException ignored) {
            // stream closed
        } finally {
            if (readersLeft.decrementAndGet() == 0) {
                onShellExit();
            }
        }
    }

    private void onShellExit() {
        alive = false;
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            code = -1;
        }
        lastExitCode = code;
        synchronized (finishLock) {
            finishedCode = code;
            finishedFlag = true;
            finishLock.notifyAll();
        }
        for (Listener l : listeners) {
            try {
                l.onClosed(code);
            } catch (Exception ignored) {
            }
        }
    }

    private void handleLine(String line, boolean isErr) {
        if (!isErr) {
            String token = pendingToken;
            if (token != null) {
                String prefix = MARK + token + "_";
                int idx = line.indexOf(prefix);
                if (idx >= 0) {
                    // Output that did not end with a newline is glued in front of the marker.
                    if (idx > 0) {
                        emit(line.substring(0, idx), false);
                    }
                    int code = 0;
                    try {
                        code = Integer.parseInt(line.substring(idx + prefix.length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    lastExitCode = code;
                    synchronized (finishLock) {
                        finishedCode = code;
                        finishedFlag = true;
                        finishLock.notifyAll();
                    }
                    return;
                }
            }
            // Marker of an older command that timed out earlier: hide it.
            if (line.startsWith(MARK) && line.matches("__END_[0-9a-f]+_-?\\d+")) {
                busy = false;
                return;
            }
        }
        emit(line, isErr);
    }

    private void emit(String line, boolean isErr) {
        long off = offsetCounter.getAndIncrement();
        pushRing(new Entry(off, line, isErr));

        for (Listener l : listeners) {
            try {
                l.onLine(line, off, isErr);
            } catch (Exception ignored) {
            }
        }

        LineSink sink = captureSink;
        if (sink != null) {
            try {
                sink.line(line, isErr);
            } catch (Exception ignored) {
            }
        }
        StringBuilder buf = captureBuf;
        if (buf != null) {
            synchronized (buf) {
                buf.append(line).append('\n');
            }
        }
    }

    private void pushRing(Entry e) {
        synchronized (ringLock) {
            ring.addLast(e);
            ringChars += e.text.length();
            while (ring.size() > BUFFER_MAX_LINES || ringChars > BUFFER_MAX_CHARS) {
                Entry old = ring.removeFirst();
                ringChars -= old.text.length();
            }
        }
    }

    /**
     * Run a command and block until it finishes or the timeout elapses.
     * If streamSink is not null, every output line is also pushed to it as it arrives.
     * On timeout the command keeps running; read the rest via outputSince().
     */
    public ExecResult exec(String command, long timeoutMs, LineSink streamSink) {
        synchronized (execLock) {
            long start = System.currentTimeMillis();
            lastActivity = start;

            if (!alive) {
                return new ExecResult("", lastExitCode, false, true, 0);
            }

            String trimmed = command.trim();
            boolean isExit = trimmed.equals("exit") || trimmed.equals("logout")
                    || trimmed.startsWith("exit ") || trimmed.startsWith("logout ");

            String token = Long.toHexString(System.nanoTime())
                    + Integer.toHexString((int) (Math.random() * 0xFFFF));
            StringBuilder buf = new StringBuilder();

            synchronized (finishLock) {
                finishedFlag = false;
                finishedCode = -1;
            }
            captureSink = streamSink;
            captureBuf = buf;
            pendingToken = isExit ? null : token;
            busy = true;

            try {
                writeRaw(command.endsWith("\n") ? command : command + "\n");
                if (!isExit) {
                    // printf with a leading newline-safe marker so it is always detected.
                    writeRaw("echo \"" + MARK + token + "_$?\"\n");
                }
            } catch (IOException e) {
                clearCapture();
                return new ExecResult(buf.toString(), lastExitCode, false, !alive,
                        System.currentTimeMillis() - start);
            }

            long deadline = start + (timeoutMs <= 0 ? 60_000 : timeoutMs);
            boolean timedOut = false;
            synchronized (finishLock) {
                while (!finishedFlag) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) {
                        timedOut = true;
                        break;
                    }
                    try {
                        finishLock.wait(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            // Give the stderr reader a moment to deliver lines that raced the marker.
            if (!timedOut) {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ignored) {
                }
            }

            int code = finishedCode;
            String out;
            synchronized (buf) {
                out = buf.toString();
            }
            if (!timedOut) {
                clearCapture();
            } else {
                // Command still running: stop capturing for this caller,
                // but keep pendingToken so a later marker is still swallowed.
                captureSink = null;
                captureBuf = null;
            }
            lastActivity = System.currentTimeMillis();
            long dur = lastActivity - start;

            if (isExit) {
                return new ExecResult(out, code, false, true, dur);
            }
            return new ExecResult(out, timedOut ? -1 : code, timedOut, !alive, dur);
        }
    }

    private void clearCapture() {
        captureSink = null;
        captureBuf = null;
        pendingToken = null;
        busy = false;
    }

    /** Send raw input to the shell's stdin (live terminal / answering prompts). */
    public void sendInput(String data) throws IOException {
        lastActivity = System.currentTimeMillis();
        writeRaw(data);
    }

    private synchronized void writeRaw(String s) throws IOException {
        stdin.write(s.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int listenerCount() {
        return listeners.size();
    }

    /** Buffered lines whose offset is >= since. */
    public List<Entry> outputSince(long since) {
        List<Entry> result = new ArrayList<>();
        synchronized (ringLock) {
            for (Entry e : ring) {
                if (e.offset >= since) result.add(e);
            }
        }
        return result;
    }

    /** Last n buffered lines. */
    public List<Entry> tail(int n) {
        List<Entry> all = outputSince(0);
        if (all.size() <= n) return all;
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    public long nextOffset() {
        return offsetCounter.get();
    }

    public boolean isAlive() {
        return alive;
    }

    public boolean isBusy() {
        return busy && pendingToken != null;
    }

    public int getLastExitCode() {
        return lastExitCode;
    }

    /** Force-stop the session and every process it started. */
    public void stop() {
        if (!alive) return;
        alive = false;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        proc.destroy();
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            proc.destroyForcibly();
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
