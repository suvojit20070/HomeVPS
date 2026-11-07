package com.suvo.homevps.session;

import com.suvo.homevps.linux.LinuxEnv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every named session. Enforces the session limit and stops sessions
 * that stay idle longer than the configured timeout.
 */
public class SessionManager {

    public static class LimitException extends Exception {
        public LimitException(String msg) {
            super(msg);
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final LinuxEnv.ShellSpec spec;
    private final int maxSessions;
    private final long idleTimeoutMs;
    private volatile boolean running = true;
    private final Thread reaper;

    public SessionManager(LinuxEnv.ShellSpec spec, int maxSessions, int idleTimeoutMin) {
        this.spec = spec;
        this.maxSessions = maxSessions;
        this.idleTimeoutMs = idleTimeoutMin * 60_000L;

        reaper = new Thread(this::reapLoop, "session-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,40}");
    }

    public synchronized Session create(String name, String cwd) throws IOException, LimitException {
        Session existing = sessions.get(name);
        if (existing != null) {
            if (existing.isAlive()) {
                throw new LimitException("Session '" + name + "' already exists");
            }
            sessions.remove(name);
        }
        int alive = 0;
        for (Session s : sessions.values()) {
            if (s.isAlive()) alive++;
        }
        if (alive >= maxSessions) {
            throw new LimitException("Session limit reached (" + maxSessions + ")");
        }
        Session s = new Session(name, cwd, spec);
        sessions.put(name, s);
        return s;
    }

    public Session get(String name) {
        return sessions.get(name);
    }

    public List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public boolean remove(String name) {
        Session s = sessions.remove(name);
        if (s == null) return false;
        s.stop();
        return true;
    }

    public boolean isLinux() {
        return spec.linux;
    }

    public int aliveCount() {
        int n = 0;
        for (Session s : sessions.values()) {
            if (s.isAlive()) n++;
        }
        return n;
    }

    private void reapLoop() {
        while (running) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return;
            }
            if (idleTimeoutMs <= 0) continue;
            long now = System.currentTimeMillis();
            for (Session s : sessions.values()) {
                if (s.isAlive() && now - s.lastActivity > idleTimeoutMs) {
                    s.stop();
                }
            }
        }
    }

    public void shutdown() {
        running = false;
        reaper.interrupt();
        for (Session s : sessions.values()) {
            s.stop();
        }
        sessions.clear();
    }
}
