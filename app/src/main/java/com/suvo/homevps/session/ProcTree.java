package com.suvo.homevps.session;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the process tree from /proc. Android lets an app see its own
 * processes, which is all we need to find what a session's shell is running.
 */
public final class ProcTree {

    private ProcTree() {
    }

    /** pid -> parent pid for every process we can see. */
    public static Map<Integer, Integer> parents() {
        Map<Integer, Integer> map = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return map;
        for (File f : entries) {
            String name = f.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            int ppid = readPpid(pid);
            if (ppid >= 0) map.put(pid, ppid);
        }
        return map;
    }

    /** Field 4 of /proc/<pid>/stat. The name (field 2) may contain spaces, so parse after ')'. */
    private static int readPpid(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/stat")) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return -1;
            String stat = new String(buf, 0, n, StandardCharsets.US_ASCII);
            int close = stat.lastIndexOf(')');
            if (close < 0) return -1;
            String[] rest = stat.substring(close + 2).split(" ");
            // rest[0] = state, rest[1] = ppid
            return Integer.parseInt(rest[1]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    public static Set<Integer> childrenOf(int parent) {
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : parents().entrySet()) {
            if (e.getValue() == parent) out.add(e.getKey());
        }
        return out;
    }

    /** All descendants of root (not root itself), parents before children. */
    public static List<Integer> descendantsOf(int root) {
        Map<Integer, Integer> parents = parents();
        List<Integer> out = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (Map.Entry<Integer, Integer> e : parents.entrySet()) {
                if (e.getValue() == cur && !out.contains(e.getKey())) {
                    out.add(e.getKey());
                    queue.add(e.getKey());
                }
            }
        }
        return out;
    }

    /**
     * PID of a started Process. Tries the private "pid" field first; if Android blocks
     * that, falls back to the new child that appeared since the 'before' snapshot.
     */
    public static int pidOf(Process p, Set<Integer> before) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(p);
            if (pid > 0) return pid;
        } catch (Exception ignored) {
        }
        Set<Integer> after = childrenOf(android.os.Process.myPid());
        after.removeAll(before);
        if (after.size() == 1) return after.iterator().next();
        int best = -1;
        for (int pid : after) best = Math.max(best, pid);
        return best;
    }
}
