package com.suvo.homevps;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.suvo.homevps.linux.LinuxEnv;
import com.suvo.homevps.tunnel.TunnelManager;
import com.suvo.homevps.util.Prefs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int BLUE = Color.parseColor("#4CC2FF");
    private static final int DIM = Color.parseColor("#8A8F98");
    private static final int TEXT = Color.parseColor("#FFFFFF");

    private Prefs prefs;
    private LinuxEnv linux;

    private TextView statusText, ipText, keyText, linuxStatusText, guideProgress;
    private TextView tunnelStatusText, publicUrlText, advancedToggle;
    private Button toggleButton, terminalButton, copyKeyButton, regenKeyButton, batteryButton;
    private Button installLinuxButton, copyDetailsButton, copyUrlButton, openPublicButton, saveTunnelButton;
    private Switch autostartSwitch, tunnelSwitch;
    private EditText tokenEdit, hostnameEdit;
    private LinearLayout guideList, publicUrlButtons, advancedBox;

    private volatile boolean installing = false;
    private boolean updatingSwitch = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        linux = new LinuxEnv(this);

        statusText = findViewById(R.id.statusText);
        ipText = findViewById(R.id.ipText);
        keyText = findViewById(R.id.keyText);
        linuxStatusText = findViewById(R.id.linuxStatusText);
        guideProgress = findViewById(R.id.guideProgress);
        tunnelStatusText = findViewById(R.id.tunnelStatusText);
        publicUrlText = findViewById(R.id.publicUrlText);
        advancedToggle = findViewById(R.id.advancedToggle);
        toggleButton = findViewById(R.id.toggleButton);
        terminalButton = findViewById(R.id.terminalButton);
        copyKeyButton = findViewById(R.id.copyKeyButton);
        regenKeyButton = findViewById(R.id.regenKeyButton);
        batteryButton = findViewById(R.id.batteryButton);
        installLinuxButton = findViewById(R.id.installLinuxButton);
        copyDetailsButton = findViewById(R.id.copyDetailsButton);
        copyUrlButton = findViewById(R.id.copyUrlButton);
        openPublicButton = findViewById(R.id.openPublicButton);
        saveTunnelButton = findViewById(R.id.saveTunnelButton);
        autostartSwitch = findViewById(R.id.autostartSwitch);
        tunnelSwitch = findViewById(R.id.tunnelSwitch);
        tokenEdit = findViewById(R.id.tokenEdit);
        hostnameEdit = findViewById(R.id.hostnameEdit);
        guideList = findViewById(R.id.guideList);
        publicUrlButtons = findViewById(R.id.publicUrlButtons);
        advancedBox = findViewById(R.id.advancedBox);

        tokenEdit.setText(prefs.getTunnelToken());
        hostnameEdit.setText(prefs.getTunnelHostname());
        autostartSwitch.setChecked(prefs.isAutostart());

        toggleButton.setOnClickListener(v -> toggleServer());
        terminalButton.setOnClickListener(v -> openTerminal(localBase()));
        installLinuxButton.setOnClickListener(v -> installLinux());
        batteryButton.setOnClickListener(v -> requestBatteryExemption());
        copyDetailsButton.setOnClickListener(v -> copyConnectionDetails());
        copyKeyButton.setOnClickListener(v -> copy("API key", prefs.getApiKey(), "API key copied"));
        regenKeyButton.setOnClickListener(v -> {
            prefs.regenerateApiKey();
            prefs.setAgentDetailsCopied(false);
            if (VpsService.RUNNING) restartServer();
            refreshState();
            Toast.makeText(this, "New key created. Update it in your agent.", Toast.LENGTH_LONG).show();
        });
        autostartSwitch.setOnCheckedChangeListener((b, checked) -> prefs.setAutostart(checked));

        tunnelSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (updatingSwitch) return;
            setInternetAccess(checked);
        });
        copyUrlButton.setOnClickListener(v -> {
            String url = TunnelManager.PUBLIC_URL;
            if (url != null) copy("Public link", url, "Link copied");
        });
        openPublicButton.setOnClickListener(v -> {
            String url = TunnelManager.PUBLIC_URL;
            if (url != null) openTerminal(url);
        });
        advancedToggle.setOnClickListener(v -> {
            boolean show = advancedBox.getVisibility() != View.VISIBLE;
            advancedBox.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText(show ? "Fixed address (optional) ▴" : "Fixed address (optional) ▾");
        });
        saveTunnelButton.setOnClickListener(v -> {
            prefs.setTunnelToken(tokenEdit.getText().toString().trim());
            prefs.setTunnelHostname(hostnameEdit.getText().toString().trim());
            if (prefs.isTunnelEnabled()) {
                VpsService.syncTunnel(this);
                Toast.makeText(this, "Saved, reconnecting", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Saved. Turn on Internet access to use it.", Toast.LENGTH_SHORT).show();
            }
        });

        ensureNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------- state

    private void refreshState() {
        boolean running = VpsService.RUNNING;

        statusText.setText(running ? "Server: RUNNING" : "Server: STOPPED");
        statusText.setTextColor(running ? GREEN : BLUE);
        toggleButton.setText(running ? "Stop server" : "Start server");
        ipText.setText("Local address: " + localBase());
        terminalButton.setEnabled(running);
        keyText.setText(prefs.getApiKey());

        // Ubuntu
        if (!installing) {
            if (!linux.isBundled()) {
                linuxStatusText.setText("Not available in this build. Use the APK from GitHub Actions on an arm64 phone.");
                installLinuxButton.setEnabled(false);
            } else if (linux.isInstalled()) {
                linuxStatusText.setText("Installed. Sessions run Ubuntu (apt-get, python3, node, git...).");
                installLinuxButton.setText("Reinstall Ubuntu");
                installLinuxButton.setEnabled(true);
            } else {
                linuxStatusText.setText("Not installed. Sessions use the basic Android shell until you install it.");
                installLinuxButton.setText("Install Ubuntu");
                installLinuxButton.setEnabled(true);
            }
        }

        // Battery
        boolean battery = isIgnoringBatteryOptimizations();
        batteryButton.setText(battery ? "Battery: unrestricted ✓" : "Allow unrestricted battery");
        batteryButton.setEnabled(!battery);

        // Internet access
        updatingSwitch = true;
        tunnelSwitch.setChecked(prefs.isTunnelEnabled());
        updatingSwitch = false;
        refreshTunnel(running);

        buildGuide(running, battery);
    }

    private void refreshTunnel(boolean running) {
        String url = TunnelManager.PUBLIC_URL;
        boolean online = TunnelManager.STATE == TunnelManager.State.ONLINE;
        String status;
        if (!prefs.isTunnelEnabled()) {
            status = "Off. The server is only reachable on the same WiFi.";
        } else if (!running) {
            status = "Waiting for the server to start";
        } else {
            switch (TunnelManager.STATE) {
                case PREPARING:
                case STARTING:
                    status = TunnelManager.MESSAGE;
                    break;
                case ONLINE:
                    status = "Online ✓";
                    if (url == null) status = TunnelManager.MESSAGE;
                    break;
                case ERROR:
                    status = TunnelManager.MESSAGE;
                    break;
                default:
                    status = "Starting...";
            }
        }
        tunnelStatusText.setText(status);
        tunnelStatusText.setTextColor(online ? GREEN : Color.parseColor("#C7CBD1"));

        boolean showUrl = online && url != null && prefs.isTunnelEnabled();
        publicUrlText.setVisibility(showUrl ? View.VISIBLE : View.GONE);
        publicUrlButtons.setVisibility(showUrl ? View.VISIBLE : View.GONE);
        if (showUrl) publicUrlText.setText(url);
    }

    // ------------------------------------------------------------- guide

    private void buildGuide(boolean running, boolean battery) {
        boolean ubuntu = linux.isInstalled();
        boolean internet = prefs.isTunnelEnabled()
                && TunnelManager.STATE == TunnelManager.State.ONLINE;
        boolean agent = prefs.isAgentDetailsCopied();

        boolean[] done = {ubuntu, battery, running, internet, agent};
        int doneCount = 0;
        int next = -1;
        for (int i = 0; i < done.length; i++) {
            if (done[i]) doneCount++;
            else if (next < 0) next = i;
        }
        guideProgress.setText(doneCount == done.length
                ? "All set ✓ Your phone is a VPS."
                : doneCount + " of " + done.length + " done");

        guideList.removeAllViews();
        addStep(0, done[0], next == 0, "Install Ubuntu",
                installing ? linuxStatusText.getText().toString()
                        : "Real Linux with apt-get. About 30 MB, takes 1-2 minutes.",
                installing ? "Installing..." : "Install", !installing && linux.isBundled(),
                v -> installLinux());
        addStep(1, done[1], next == 1, "Allow unrestricted battery",
                "Stops Android from pausing the server when the screen is off.",
                "Allow", true, v -> requestBatteryExemption());
        addStep(2, done[2], next == 2, "Start the server",
                "Runs in the background and starts again after a reboot.",
                "Start", true, v -> {
                    ensureNotificationPermission();
                    VpsService.startServer(this);
                });
        addStep(3, done[3], next == 3, "Turn on internet access",
                prefs.isTunnelEnabled() && !internet && running
                        ? TunnelManager.MESSAGE
                        : "Gives you a public https link that works from anywhere.",
                "Turn on", ubuntu, v -> setInternetAccess(true));
        addStep(4, done[4], next == 4, "Connect your agent",
                "Copy the link, key and tool list, then paste them into your agent.",
                "Copy", true, v -> copyConnectionDetails());
    }

    private void addStep(int index, boolean done, boolean current, String title, String detail,
                         String action, boolean actionEnabled, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(6), 0, dp(6));
        row.setLayoutParams(rowLp);

        TextView badge = new TextView(this);
        badge.setText(done ? "✓" : String.valueOf(index + 1));
        badge.setTextColor(done ? GREEN : (current ? BLUE : DIM));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        badgeLp.setMargins(dp(4), 0, dp(8), 0);
        row.addView(badge, badgeLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMargins(0, 0, dp(6), 0);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(done ? DIM : TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, dp(2), 0, dp(2));
        texts.addView(t, tLp);

        if (!done && detail != null && !detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(detail);
            d.setTextColor(DIM);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.setMargins(0, 0, 0, dp(2));
            texts.addView(d, dLp);
        }
        row.addView(texts, textsLp);

        if (!done) {
            Button b = new Button(this);
            b.setText(action);
            b.setAllCaps(false);
            b.setEnabled(actionEnabled);
            b.setOnClickListener(onClick);
            if (current) {
                b.setBackgroundTintList(ColorStateList.valueOf(BLUE));
                b.setTextColor(Color.parseColor("#0E0F12"));
            }
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.setMargins(dp(4), dp(2), dp(4), dp(2));
            row.addView(b, bLp);
        }
        guideList.addView(row);
    }

    // ------------------------------------------------------------- actions

    private void toggleServer() {
        if (VpsService.RUNNING) {
            VpsService.stopServer(this);
        } else {
            ensureNotificationPermission();
            VpsService.startServer(this);
        }
        handler.postDelayed(this::refreshState, 600);
    }

    private void restartServer() {
        VpsService.stopServer(this);
        handler.postDelayed(() -> VpsService.startServer(this), 800);
    }

    private void setInternetAccess(boolean on) {
        if (on && !linux.isInstalled()) {
            Toast.makeText(this, "Install Ubuntu first (step 1)", Toast.LENGTH_LONG).show();
            updatingSwitch = true;
            tunnelSwitch.setChecked(false);
            updatingSwitch = false;
            return;
        }
        prefs.setTunnelEnabled(on);
        ensureNotificationPermission();
        // Starts the server too if it is not running.
        if (on || VpsService.RUNNING) {
            VpsService.syncTunnel(this);
        }
        refreshState();
    }

    private void openTerminal(String base) {
        if (!VpsService.RUNNING) {
            Toast.makeText(this, "Start the server first", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = base + "/?key=" + Uri.encode(prefs.getApiKey());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyConnectionDetails() {
        String publicUrl = TunnelManager.STATE == TunnelManager.State.ONLINE ? TunnelManager.PUBLIC_URL : null;
        String base = publicUrl != null ? publicUrl : localBase();
        String key = prefs.getApiKey();

        StringBuilder sb = new StringBuilder();
        sb.append("Home VPS - a Linux (Ubuntu, root) machine you control through an HTTP API.\n\n");
        sb.append("Base URL: ").append(base).append('\n');
        if (publicUrl == null) {
            sb.append("(local network only - turn on Internet access in the app for a public link)\n");
        }
        sb.append("API key: ").append(key).append('\n');
        sb.append("Send header: Authorization: Bearer ").append(key).append("\n\n");
        sb.append("Endpoints (JSON bodies):\n");
        sb.append("POST   /sessions                    {\"name\":\"main\",\"cwd\":\"/root\"}  start a shell session\n");
        sb.append("GET    /sessions                    list sessions\n");
        sb.append("POST   /sessions/{name}/exec        {\"command\":\"ls -la\",\"timeout\":60000}  -> {output, exit_code, timed_out}\n");
        sb.append("POST   /sessions/{name}/exec        add \"stream\":true for live output (Server-Sent Events)\n");
        sb.append("POST   /sessions/{name}/input       {\"data\":\"y\\n\"}  answer a prompt\n");
        sb.append("POST   /sessions/{name}/interrupt   Ctrl+C\n");
        sb.append("GET    /sessions/{name}/output?since=N   read buffered output\n");
        sb.append("DELETE /sessions/{name}             stop the session\n\n");
        sb.append("Tool definitions (OpenAI format): ").append(base).append("/tools.json\n");
        sb.append("Web terminal: ").append(base).append("/?key=").append(key).append("\n\n");
        sb.append("Tips: use apt-get install -y; sessions keep cwd and variables; ")
                .append("long commands keep running after a timeout, read them with /output.\n");

        copy("Home VPS connection", sb.toString(), "Connection details copied");
        prefs.setAgentDetailsCopied(true);
        refreshState();
    }

    private void installLinux() {
        if (installing) return;
        installing = true;
        installLinuxButton.setEnabled(false);
        final boolean wasRunning = VpsService.RUNNING;
        if (wasRunning) {
            // Sessions must not use the rootfs while it is being replaced.
            VpsService.stopServer(this);
        }
        new Thread(() -> {
            String error = null;
            try {
                linux.install(message -> runOnUiThread(() -> {
                    linuxStatusText.setText(message);
                    refreshState();
                }));
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                installing = false;
                if (err != null) {
                    linuxStatusText.setText("Install failed: " + err);
                    installLinuxButton.setEnabled(true);
                    refreshState();
                    return;
                }
                Toast.makeText(this, "Ubuntu installed", Toast.LENGTH_SHORT).show();
                if (wasRunning) VpsService.startServer(this);
                refreshState();
            });
        }, "linux-install").start();
        refreshState();
    }

    // ------------------------------------------------------------- helpers

    private void copy(String label, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    private String localBase() {
        return "http://" + getLocalIpAddress() + ":" + prefs.getPort();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressWarnings("BatteryLife")
    private void requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** Prefers the WiFi / hotspot / ethernet IP; skips the mobile-data CLAT address (192.0.0.x). */
    private String getLocalIpAddress() {
        String fallback = null;
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                String name = intf.getName();
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("192.0.0.")) continue;
                    if (name.startsWith("wlan") || name.startsWith("ap")
                            || name.startsWith("eth") || name.startsWith("swlan")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }
}
