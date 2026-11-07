package com.suvo.homevps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.suvo.homevps.linux.LinuxEnv;
import com.suvo.homevps.util.Prefs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    private Prefs prefs;
    private LinuxEnv linux;
    private TextView statusText, endpointText, keyText, ipText, linuxStatusText;
    private Button toggleButton, copyKeyButton, regenKeyButton, batteryButton, installLinuxButton;
    private volatile boolean installing = false;
    private Switch autostartSwitch;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        linux = new LinuxEnv(this);

        statusText = findViewById(R.id.statusText);
        endpointText = findViewById(R.id.endpointText);
        keyText = findViewById(R.id.keyText);
        ipText = findViewById(R.id.ipText);
        toggleButton = findViewById(R.id.toggleButton);
        copyKeyButton = findViewById(R.id.copyKeyButton);
        regenKeyButton = findViewById(R.id.regenKeyButton);
        batteryButton = findViewById(R.id.batteryButton);
        autostartSwitch = findViewById(R.id.autostartSwitch);
        linuxStatusText = findViewById(R.id.linuxStatusText);
        installLinuxButton = findViewById(R.id.installLinuxButton);

        installLinuxButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installLinux();
            }
        });

        autostartSwitch.setChecked(prefs.isAutostart());
        autostartSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setAutostart(isChecked);
            }
        });

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (VpsService.RUNNING) {
                    VpsService.stopServer(MainActivity.this);
                } else {
                    ensureNotificationPermission();
                    VpsService.startServer(MainActivity.this);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshState();
                    }
                }, 600);
            }
        });

        copyKeyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("api_key", prefs.getApiKey()));
                    Toast.makeText(MainActivity.this, "API key copied", Toast.LENGTH_SHORT).show();
                }
            }
        });

        regenKeyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.regenerateApiKey();
                refreshState();
                Toast.makeText(MainActivity.this,
                        "New key generated. Restart the server to apply.",
                        Toast.LENGTH_LONG).show();
            }
        });

        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBatteryExemption();
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

    private void refreshState() {
        boolean running = VpsService.RUNNING;
        int port = prefs.getPort();
        String ip = getLocalIpAddress();

        statusText.setText(running ? "Server: RUNNING" : "Server: STOPPED");
        toggleButton.setText(running ? "Stop server" : "Start server");
        endpointText.setText("http://" + ip + ":" + port);
        ipText.setText("Local IP: " + ip + "   Port: " + port);
        keyText.setText(prefs.getApiKey());

        if (!installing) {
            if (!linux.isBundled()) {
                linuxStatusText.setText("Ubuntu: not available in this build (arm64 APK from GitHub Actions needed)");
                installLinuxButton.setEnabled(false);
            } else if (linux.isInstalled()) {
                linuxStatusText.setText(running
                        ? "Ubuntu: installed, sessions run in Ubuntu"
                        : "Ubuntu: installed");
                installLinuxButton.setText("Reinstall Ubuntu");
                installLinuxButton.setEnabled(true);
            } else {
                linuxStatusText.setText("Ubuntu: not installed (sessions use the Android shell)");
                installLinuxButton.setText("Install Ubuntu");
                installLinuxButton.setEnabled(true);
            }
        }

        if (isIgnoringBatteryOptimizations()) {
            batteryButton.setText("Battery: unrestricted ✓");
            batteryButton.setEnabled(false);
        } else {
            batteryButton.setText("Allow unrestricted battery");
            batteryButton.setEnabled(true);
        }
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    linux.install(new LinuxEnv.Progress() {
                        @Override
                        public void update(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    linuxStatusText.setText(message);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installing = false;
                        if (err != null) {
                            linuxStatusText.setText("Install failed: " + err);
                            installLinuxButton.setEnabled(true);
                            return;
                        }
                        Toast.makeText(MainActivity.this, "Ubuntu installed", Toast.LENGTH_SHORT).show();
                        if (wasRunning) {
                            VpsService.startServer(MainActivity.this);
                        }
                        refreshState();
                    }
                });
            }
        }, "linux-install").start();
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

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
