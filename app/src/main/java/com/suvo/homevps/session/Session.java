package com.suvo.homevps.session;

import com.suvo.homevps.linux.LinuxEnv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
 *
 * Ctrl+C ({@link #interrupt()}) first sends SIGINT to the running program. If the
 * command is still running 1.5 s later (a shell loop, for example), the shell is
 * restarted in the same directory; shell variables are reset in that case.
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
    private static final long INTERRUPT_GRACE_MS = 1500;
    private static final int EXIT_INTERRUPTED = 130;

    public final String name;
    public final String cwd;
    public final long createdAt = System.currentTimeMillis();
    public volatile long lastActivity = System.currentTimeMillis();

    private final LinuxEnv.ShellSpec spec;

    // The current shell process. Replaced when Ctrl+C has to restart the shell.
    private volatile Process proc;
    private volatile OutputStream stdin;
    private volatile int rootPid = -1;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final Object spawnLock = new Object();
    private final Object writeLock = new Object();

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
    private volatile boolean finishedFlag = true;
    private volatile int finishedCode = -1;

    private volatile boolean alive = true;
    private volatile boolean busy = false;
    private volatile int lastExitCode = 0;

    public Session(String name, String cwd, LinuxEnv.ShellSpec spec) throws IOException {
        this.name = name;
        this.cwd = (cwd == null || cwd.isEmpty()) ? spec.defaultCwd : cwd;
        this.spec = spec;
        spawn(this.cwd);
    }

    // ------------------------------------------------------------- process

    /** Start a shell in the given directory. Must be called with no live shell. */
    private void spawn(String dir) throws IOException {
        synchronized (spawnLock) {
            final int gen = generation.incrementAndGet();

            ProcessBuilder pb = new ProcessBuilder(spec.command);
            pb.redirectErrorStream(false);
            pb.environment().putAll(spec.env);
            pb.directory(spec.hostDir);

            Set<Integer> before = ProcTree.childrenOf(android.os.Process.myPid());
            Process p = pb.start();
            this.proc = p;
            this.rootPid = ProcTree.pidOf(p, before);
            this.stdin = p.getOutputStream();

            AtomicInteger readersLeft = new AtomicInteger(2);
            startReader(p.getInputStream(), false, gen, readersLeft, p);
            startReader(p.getErrorStream(), true, gen, readersLeft, p);

            // mkdir/cd run inside the shell, so the path is the guest (Ubuntu) path.
            String q = shellQuote(dir);
            writeRaw("mkdir -p " + q + " 2>/dev/null; cd " + q + " 2>/dev/null\n");
        }
    }

    private void startReader(InputStream stream, boolean isErr, int gen,
                             AtomicInteger readersLeft, Process p) {
        Thread t = new Thread(() -> readLoop(stream, isErr, gen, readersLeft, p),
                "session-" + name + (isErr ? "-err" : "-out") + "-" + gen);
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(InputStream stream, boolean isErr, int gen,
                          AtomicInteger readersLeft, Process p) {
        try (InputStreamReader r = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            StringBuilder pending = new StringBuilder();
            int n;
            while ((n = r.read(buf)) != -1) {
                // Ignore late output from a shell that was replaced by Ctrl+C.
                if (gen != generation.get()) continue;
                pending.append(buf, 0, n);

                int nl;
                while ((nl = pending.indexOf("\n")) >= 0) {
                    int end = (nl > 0 && pending.charAt(nl - 1) == '\r') ? nl - 1 : nl;
                    handleLine(pending.substring(0, end), isErr);
                    pending.delete(0, nl + 1);
                }

                // A line without a newline that stays unfinished is usually a prompt
                // ("Continue? [Y/n] "): show it instead of waiting for a newline forever.
                if (pending.length() > 0 && !r.ready()) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ignored) {
                    }
                    if (!r.ready()) {
                        handleLine(pending.toString(), isErr);
                        pending.setLength(0);
                    }
                }
            }
            if (pending.length() > 0 && gen == generation.get()) {
                handleLine(pending.toString(), isErr);
            }
        } catch (IOException ignored) {
            // stream closed
        } finally {
            if (readersLeft.decrementAndGet() == 0 && gen == generation.get()) {
                onShellExit(p);
            }
        }
    }

    private void onShellExit(Process p) {
        alive = false;
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            code = -1;
        }
        lastExitCode = code;
        finish(code);
        for (Listener l : listeners) {
            try {
                l.onClosed(code);
            } catch (Exception ignored) {
            }
        }
    }

    private void finish(int code) {
        synchronized (finishLock) {
            finishedCode = code;
            finishedFlag = true;
            finishLock.notifyAll();
        }
    }

    // ------------------------------------------------------------- output

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
                    finish(code);
                    return;
                }
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

    // ------------------------------------------------------------- exec

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
                if (isExit) {
                    writeRaw(command.endsWith("\n") ? command : command + "\n");
                } else {
                    // Wrap in { } and put the marker on the closing line: the shell parses the
                    // whole block before running it, so a command that reads stdin (read, a
                    // [Y/n] prompt) gets the user's input instead of swallowing the marker.
                    String body = command.endsWith("\n") ? command : command + "\n";
                    writeRaw("{ " + body + "}; echo \"" + MARK + token + "_$?\"\n");
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

    // ------------------------------------------------------------- Ctrl+C

    /**
     * Ctrl+C. Sends SIGINT to what the shell is running. If the command has not
     * finished after a short grace period (shell loops, programs ignoring SIGINT),
     * the shell is restarted in its current directory.
     *
     * @return number of processes signalled, or -1 if the shell was restarted
     */
    public int interrupt() {
        lastActivity = System.currentTimeMillis();
        if (!alive) return 0;

        List<Integer> targets = runningPids();
        for (int pid : targets) {
            android.os.Process.sendSignal(pid, 2); // SIGINT
        }

        if (!isBusy()) return targets.size();

        // Wait for the running exec() to finish on its own.
        long deadline = System.currentTimeMillis() + INTERRUPT_GRACE_MS;
        synchronized (finishLock) {
            while (!finishedFlag) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                try {
                    finishLock.wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (finishedFlag) return targets.size();
        }

        restartShell();
        return -1;
    }

    /** Processes the shell is running (children of the shell and their descendants). */
    private List<Integer> runningPids() {
        int shellPid = shellPid();
        if (shellPid <= 0) return Collections.emptyList();
        return ProcTree.descendantsOf(shellPid);
    }

    /** Android sh: our direct child. Ubuntu: our child is proot, bash is proot's first child. */
    private int shellPid() {
        int root = rootPid;
        if (root <= 0) return -1;
        if (!spec.linux) return root;
        Set<Integer> kids = ProcTree.childrenOf(root);
        return kids.isEmpty() ? -1 : Collections.min(kids);
    }

    /** Replace the shell with a fresh one in the same directory; ends the pending exec with 130. */
    private void restartShell() {
        String dir = currentDir();
        Process old = proc;
        int oldRoot = rootPid;

        synchronized (spawnLock) {
            // Bump the generation first so the old readers stay quiet while the shell dies.
            generation.incrementAndGet();
        }
        killTree(oldRoot, old);

        emit("^C - command did not stop, shell restarted in " + dir
                + " (shell variables were reset)", true);
        lastExitCode = EXIT_INTERRUPTED;
        finish(EXIT_INTERRUPTED);

        try {
            spawn(dir);
        } catch (IOException e) {
            alive = false;
            for (Listener l : listeners) {
                try {
                    l.onClosed(-1);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** The shell's working directory, read from /proc, mapped to the guest path. */
    private String currentDir() {
        int pid = shellPid();
        if (pid <= 0) return cwd;
        try {
            String host = new File("/proc/" + pid + "/cwd").getCanonicalPath();
            if (spec.guestRoot != null) {
                String root = new File(spec.guestRoot).getCanonicalPath();
                if (host.equals(root)) return "/";
                if (host.startsWith(root + "/")) return host.substring(root.length());
                return cwd;
            }
            return host;
        } catch (IOException | SecurityException e) {
            return cwd;
        }
    }

    private void killTree(int root, Process p) {
        if (root > 0) {
            List<Integer> all = new ArrayList<>(ProcTree.descendantsOf(root));
            Collections.reverse(all); // children first
            for (int pid : all) {
                android.os.Process.sendSignal(pid, 9); // SIGKILL
            }
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------- io

    /** Send raw input to the shell's stdin (live terminal / answering prompts). */
    public void sendInput(String data) throws IOException {
        lastActivity = System.currentTimeMillis();
        writeRaw(data);
    }

    private void writeRaw(String s) throws IOException {
        synchronized (writeLock) {
            OutputStream out = stdin;
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
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
        killTree(rootPid, proc);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
