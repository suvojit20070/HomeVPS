package com.suvo.homevps.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Central settings store for the Home VPS app.
 * All server configuration lives here so the dashboard, the service,
 * and the boot receiver all read the same values.
 */
public class Prefs {

    private static final String FILE = "home_vps_prefs";

    public static final String KEY_PORT = "port";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_DESIRED_RUNNING = "desired_running";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_IDLE_TIMEOUT_MIN = "idle_timeout_min";
    public static final String KEY_MAX_SESSIONS = "max_sessions";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_API_KEY)) {
            sp.edit().putString(KEY_API_KEY, generateKey()).apply();
        }
    }

    public int getPort() {
        return sp.getInt(KEY_PORT, 8090);
    }

    public void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public String getApiKey() {
        return sp.getString(KEY_API_KEY, "");
    }

    public void regenerateApiKey() {
        sp.edit().putString(KEY_API_KEY, generateKey()).apply();
    }

    /** Whether the user wants the server to be running. Used by the boot receiver. */
    public boolean isDesiredRunning() {
        return sp.getBoolean(KEY_DESIRED_RUNNING, false);
    }

    public void setDesiredRunning(boolean running) {
        sp.edit().putBoolean(KEY_DESIRED_RUNNING, running).apply();
    }

    public boolean isAutostart() {
        return sp.getBoolean(KEY_AUTOSTART, true);
    }

    public void setAutostart(boolean autostart) {
        sp.edit().putBoolean(KEY_AUTOSTART, autostart).apply();
    }

    public int getIdleTimeoutMin() {
        return sp.getInt(KEY_IDLE_TIMEOUT_MIN, 30);
    }

    public void setIdleTimeoutMin(int minutes) {
        sp.edit().putInt(KEY_IDLE_TIMEOUT_MIN, minutes).apply();
    }

    public int getMaxSessions() {
        return sp.getInt(KEY_MAX_SESSIONS, 10);
    }

    public void setMaxSessions(int max) {
        sp.edit().putInt(KEY_MAX_SESSIONS, max).apply();
    }

    private static String generateKey() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        String hex = "0123456789abcdef";
        for (byte b : buf) {
            sb.append(hex.charAt((b >> 4) & 0xF));
            sb.append(hex.charAt(b & 0xF));
        }
        return sb.toString();
    }
}
