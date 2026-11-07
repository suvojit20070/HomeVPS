package com.suvo.homevps.tunnel;

import android.content.Context;
import android.util.Log;

import com.suvo.homevps.linux.LinuxEnv;
import com.suvo.homevps.session.ProcTree;
import com.suvo.homevps.util.Prefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the API reachable from the internet with a Cloudflare Tunnel.
 *
 * cloudflared runs inside Ubuntu (proot) and connects out to Cloudflare, so it works
 * on mobile data and behind CGNAT with no port forwarding.
 *
 * Two modes:
 *   - Quick tunnel (no account): a random https://xxxx.trycloudflare.com link that
 *     changes every time the tunnel restarts.
 *   - Token (Cloudflare account): a fixed hostname you set up in the Cloudflare
 *     dashboard; paste its tunnel token in the app.
 *
 * State is published in static fields so the dashboard can show it.
 */
public class TunnelManager {

    public enum State { OFF, PREPARING, STARTING, ONLINE, ERROR }

    public static volatile State STATE = State.OFF;
    public static volatile String PUBLIC_URL = null;
    public static volatile String MESSAGE = "";

    private static final String TAG = "TunnelManager";
    private static final String DOWNLOAD_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64";
    private static final String GUEST_BINARY = "/usr/local/bin/cloudflared";
    private static final Pattern QUICK_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    private final Context ctx;
    private final LinuxEnv linux;
    private final Prefs prefs;
    private final int port;
    private final Runnable onChange;

    private volatile boolean wanted = false;
    private volatile Thread worker;
    private volatile Process proc;
    private volatile int procPid = -1;

    public TunnelManager(Context context, int port, Runnable onChange) {
        this.ctx = context.getApplicationContext();
        this.linux = new LinuxEnv(ctx);
        this.prefs = new Prefs(ctx);
        this.port = port;
        this.onChange = onChange;
    }

    public synchronized void start() {
        wanted = true;
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::runLoop, "cloudflared");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        wanted = false;
        killProcess();
        Thread w = worker;
        if (w != null) w.interrupt();
        worker = null;
        set(State.OFF, null, "");
    }

    public boolean isWanted() {
        return wanted;
    }

    // ------------------------------------------------------------- loop

    private void runLoop() {
        try {
            if (!linux.isInstalled()) {
                set(State.ERROR, null, "Install Ubuntu first");
                return;
            }
            File binary = new File(linux.rootfs(), GUEST_BINARY.substring(1));
            if (!binary.isFile() || binary.length() < 1_000_000) {
                download(binary);
            }
            //noinspection ResultOfMethodCallIgnored
            binary.setExecutable(true, false);
            linux.ensureCaBundle();
        } catch (Exception e) {
            if (wanted) set(State.ERROR, null, "Setup failed: " + e.getMessage());
            return;
        }

        long delay = 3000;
        // A stop() followed by start() creates a new worker; the old one must quit.
        while (wanted && worker == Thread.currentThread()) {
            long started = System.currentTimeMillis();
            String lastLines = runOnce();
            if (!wanted || worker != Thread.currentThread()) break;

            if (System.currentTimeMillis() - started > 60_000) delay = 3000;
            set(State.ERROR, null, "Tunnel stopped, retrying in " + (delay / 1000) + "s"
                    + (lastLines.isEmpty() ? "" : "\n" + lastLines));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                break;
            }
            delay = Math.min(delay * 2, 60_000);
        }
    }

    /** Runs cloudflared until it exits. Returns its last log lines. */
    private String runOnce() {
        String token = prefs.getTunnelToken().trim();
        boolean tokenMode = !token.isEmpty();
        set(State.STARTING, null, "Connecting to Cloudflare...");

        List<String> cmd;
        if (tokenMode) {
            cmd = linux.guestCommand(GUEST_BINARY, "tunnel", "--no-autoupdate",
                    "--protocol", "http2", "run", "--token", token);
        } else {
            cmd = linux.guestCommand(GUEST_BINARY, "tunnel", "--no-autoupdate",
                    "--protocol", "http2", "--url", "http://127.0.0.1:" + port);
        }

        ArrayDeque<String> tail = new ArrayDeque<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().putAll(linux.hostEnvironment());
            pb.directory(linux.hostDir());

            Set<Integer> before = ProcTree.childrenOf(android.os.Process.myPid());
            Process p = pb.start();
            proc = p;
            procPid = ProcTree.pidOf(p, before);

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    tail.addLast(line);
                    while (tail.size() > 3) tail.removeFirst();
                    Log.d(TAG, line);

                    if (!tokenMode) {
                        Matcher m = QUICK_URL.matcher(line);
                        if (m.find() && !m.group().contains("api.trycloudflare.com")) {
                            set(State.ONLINE, m.group(), "Online");
                        }
                    } else if (line.contains("Registered tunnel connection")) {
                        String host = prefs.getTunnelHostname().trim();
                        String url = host.isEmpty() ? null
                                : (host.startsWith("http") ? host : "https://" + host);
                        set(State.ONLINE, url, url == null
                                ? "Online. Add your hostname in the app to see the link."
                                : "Online");
                    }
                }
            }
            p.waitFor();
        } catch (IOException e) {
            tail.addLast(e.getMessage());
        } catch (InterruptedException ignored) {
        } finally {
            killProcess();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : tail) sb.append(l.trim()).append('\n');
        return sb.toString().trim();
    }

    private void killProcess() {
        Process p = proc;
        int pid = procPid;
        proc = null;
        procPid = -1;
        if (p == null) return;
        if (pid > 0) {
            List<Integer> kids = new ArrayList<>(ProcTree.descendantsOf(pid));
            Collections.reverse(kids);
            for (int k : kids) android.os.Process.sendSignal(k, 9);
        }
        p.destroy();
    }

    private void download(File dest) throws IOException {
        set(State.PREPARING, null, "Downloading cloudflared...");
        File parent = dest.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(dest.getPath() + ".part");

        HttpURLConnection conn = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("download failed (HTTP " + code + ")");
        }
        long total = conn.getContentLengthLong();
        long done = 0;
        int lastPct = -1;
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (!wanted) throw new IOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        set(State.PREPARING, null, "Downloading cloudflared... " + pct + "%");
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (total > 0 && done != total) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("download incomplete");
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("cannot save " + dest);
        }
    }

    private void set(State state, String url, String message) {
        STATE = state;
        PUBLIC_URL = url;
        MESSAGE = message == null ? "" : message;
        if (onChange != null) {
            try {
                onChange.run();
            } catch (Exception ignored) {
            }
        }
    }
}
