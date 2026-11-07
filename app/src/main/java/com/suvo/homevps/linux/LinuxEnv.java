package com.suvo.homevps.linux;

import android.content.Context;

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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ubuntu Linux environment running under proot (no root needed).
 *
 * Binaries are packed into the APK at build time (see scripts/fetch-linux.sh):
 *   jniLibs/arm64-v8a/libproot.so         proot executable
 *   jniLibs/arm64-v8a/libproot-loader.so  proot loader
 *   jniLibs/arm64-v8a/libtalloc.so        proot dependency
 *   assets/rootfs.tar.gz                  Ubuntu base rootfs (arm64)
 *
 * They live in nativeLibraryDir because Android 10+ refuses to execute files
 * from the app's writable data folder.
 */
public class LinuxEnv {

    public interface Progress {
        void update(String message);
    }

    /** Everything needed to launch one session's shell. */
    public static class ShellSpec {
        public final List<String> command;
        public final Map<String, String> env;
        public final File hostDir;
        public final String defaultCwd;
        public final boolean linux;
        /** Host path of the guest "/" (the rootfs), or null for the Android shell. */
        public final String guestRoot;

        ShellSpec(List<String> command, Map<String, String> env, File hostDir,
                  String defaultCwd, boolean linux, String guestRoot) {
            this.command = command;
            this.env = env;
            this.hostDir = hostDir;
            this.defaultCwd = defaultCwd;
            this.linux = linux;
            this.guestRoot = guestRoot;
        }
    }

    /** Used when the image is not packed inside the APK. */
    private static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                    + "ubuntu-base-24.04.5-base-arm64.tar.gz";

    private static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private final Context ctx;

    public LinuxEnv(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // ------------------------------------------------------------- paths

    public File rootfs() {
        return new File(ctx.getFilesDir(), "ubuntu");
    }

    private File nativeDir() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    private File proot() {
        return new File(nativeDir(), "libproot.so");
    }

    private File loader() {
        return new File(nativeDir(), "libproot-loader.so");
    }

    private File tmpDir() {
        File f = new File(ctx.getCacheDir(), "proot-tmp");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** True if the APK contains the proot binaries (arm64-v8a builds only). */
    public boolean isBundled() {
        return proot().exists() && loader().exists();
    }

    public boolean isInstalled() {
        return isBundled() && new File(rootfs(), ".installed").exists();
    }

    // ------------------------------------------------------------- shells

    /** Ubuntu if installed, otherwise the Android system shell. */
    public ShellSpec currentSpec() {
        return isInstalled() ? linuxSpec() : systemSpec();
    }

    public ShellSpec systemSpec() {
        List<String> cmd = new ArrayList<>();
        cmd.add("/system/bin/sh");
        Map<String, String> env = new HashMap<>();
        String home = ctx.getFilesDir().getAbsolutePath();
        env.put("HOME", home);
        env.put("TERM", "dumb");
        env.put("PS1", "");
        return new ShellSpec(cmd, env, ctx.getFilesDir(), home, false, null);
    }

    public ShellSpec linuxSpec() {
        List<String> cmd = guestCommand("/bin/bash", "--login");
        return new ShellSpec(cmd, hostEnv(), ctx.getFilesDir(), "/root", true,
                rootfs().getAbsolutePath());
    }

    /**
     * Command line that runs a program inside Ubuntu, e.g. guestCommand("/usr/bin/python3", "x.py").
     * Start it with {@link #hostEnvironment()} as the environment and {@link #hostDir()} as cwd.
     */
    public List<String> guestCommand(String... guestArgs) {
        String root = rootfs().getAbsolutePath();
        List<String> cmd = new ArrayList<>();
        cmd.add(proot().getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(root);
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(root + "/tmp:/dev/shm");
        File sdcard = new File("/storage/emulated/0");
        if (sdcard.isDirectory() && sdcard.canRead()) {
            cmd.add("-b");
            cmd.add("/storage/emulated/0:/sdcard");
        }
        cmd.add("-w");
        cmd.add("/root");
        // Start the guest with a clean environment.
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("USER=root");
        cmd.add("LOGNAME=root");
        cmd.add("PATH=" + GUEST_PATH);
        cmd.add("TERM=dumb");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PYTHONUNBUFFERED=1");
        cmd.add("DEBIAN_FRONTEND=noninteractive");
        Collections.addAll(cmd, guestArgs);
        return cmd;
    }

    public Map<String, String> hostEnvironment() {
        return hostEnv();
    }

    public File hostDir() {
        return ctx.getFilesDir();
    }

    private Map<String, String> hostEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LD_LIBRARY_PATH", nativeDir().getAbsolutePath());
        env.put("PROOT_LOADER", loader().getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmpDir().getAbsolutePath());
        return env;
    }

    // ------------------------------------------------------------- install

    /** Extract the bundled Ubuntu rootfs. Blocking; call from a background thread. */
    public synchronized void install(Progress p) throws IOException {
        if (!isBundled()) {
            throw new IOException("Linux binaries are missing from this APK. "
                    + "Build it with the GitHub Actions workflow (arm64 phones only).");
        }

        File root = rootfs();
        if (root.exists()) {
            p.update("Removing old install...");
            deleteRecursive(root);
        }
        if (!root.mkdirs()) {
            throw new IOException("Cannot create " + root);
        }

        File archive = new File(ctx.getCacheDir(), "rootfs.tar.gz");
        if (!copyFromAssets(archive, p)) {
            download(ROOTFS_URL, archive, p);
        }

        p.update("Extracting Ubuntu (1-2 minutes)...");
        // Run tar under proot so hard links are emulated and ownership is faked.
        List<String> cmd = new ArrayList<>();
        cmd.add(proot().getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("/system/bin/tar");
        cmd.add("-xzf");
        cmd.add(archive.getAbsolutePath());
        cmd.add("-C");
        cmd.add(root.getAbsolutePath());
        String log = run(cmd);

        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        if (!new File(root, "usr/bin/bash").exists()) {
            throw new IOException("Extraction failed:\n" + tail(log, 15));
        }

        p.update("Configuring...");
        configure(root);

        if (!new File(root, ".installed").createNewFile()) {
            throw new IOException("Cannot write install marker");
        }
        p.update("Ubuntu installed");
    }

    /** Copies the bundled image. Returns false if the APK does not contain it. */
    private boolean copyFromAssets(File dest, Progress p) throws IOException {
        InputStream in;
        try {
            in = ctx.getAssets().open("rootfs.tar.gz");
        } catch (IOException e) {
            return false;
        }
        p.update("Copying Ubuntu image...");
        try (InputStream src = in; OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = src.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return true;
    }

    private void download(String url, File dest, Progress p) throws IOException {
        p.update("Downloading Ubuntu image...");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("Download failed (HTTP " + code + ")");
        }
        long total = conn.getContentLengthLong();
        long done = 0;
        int lastPct = -1;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.update("Downloading Ubuntu image... " + pct + "% ("
                                + (done / (1024 * 1024)) + " / " + (total / (1024 * 1024)) + " MB)");
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (total > 0 && done != total) {
            throw new IOException("Download incomplete, check your internet and try again");
        }
    }

    private void configure(File root) throws IOException {
        // DNS: ubuntu-base ships resolv.conf as a symlink to systemd's file, replace it.
        File resolv = new File(root, "etc/resolv.conf");
        Files.deleteIfExists(resolv.toPath());
        write(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");

        write(new File(root, "etc/hosts"),
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");

        // apt tries to drop privileges to the _apt user, which proot cannot do.
        write(new File(root, "etc/apt/apt.conf.d/01-proot"),
                "APT::Sandbox::User \"root\";\n");

        ensureCaBundle();

        //noinspection ResultOfMethodCallIgnored
        new File(root, "root").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "tmp").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "tmp").setWritable(true, false);
    }

    /**
     * ubuntu-base ships without CA certificates, so HTTPS (cloudflared, curl, pip, git)
     * fails. Build the bundle from Android's own trusted certificates if it is missing.
     */
    public void ensureCaBundle() throws IOException {
        File bundle = new File(rootfs(), "etc/ssl/certs/ca-certificates.crt");
        if (bundle.isFile() && bundle.length() > 10_000) return;

        String[] dirs = {"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"};
        java.util.Set<String> seen = new java.util.HashSet<>();
        StringBuilder pem = new StringBuilder();
        for (String d : dirs) {
            File[] files = new File(d).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!seen.add(f.getName())) continue;
                String text;
                try {
                    text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                int b = text.indexOf("-----BEGIN CERTIFICATE-----");
                int e = text.indexOf("-----END CERTIFICATE-----");
                if (b >= 0 && e > b) {
                    pem.append(text, b, e + "-----END CERTIFICATE-----".length()).append('\n');
                }
            }
        }
        if (pem.length() == 0) {
            throw new IOException("No CA certificates found on this phone");
        }
        write(bundle, pem.toString());
    }

    private String run(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().putAll(hostEnv());
        pb.directory(ctx.getFilesDir());
        Process proc = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        try {
            proc.waitFor();
        } catch (InterruptedException ignored) {
        }
        return sb.toString();
    }

    private static void write(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Files.deleteIfExists(f.toPath());
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursive(File f) throws IOException {
        // Never follow symlinks: a link to /system or /sdcard must not be walked.
        if (Files.isSymbolicLink(f.toPath())) {
            Files.deleteIfExists(f.toPath());
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(true);
        Files.deleteIfExists(f.toPath());
    }

    private static String tail(String s, int lines) {
        String[] parts = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, parts.length - lines); i < parts.length; i++) {
            sb.append(parts[i]).append('\n');
        }
        return sb.toString();
    }
}
