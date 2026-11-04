package com.pocketvr.app;

import android.content.pm.PackageManager;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Thin helper around Shizuku: tells whether it is running / allowed, and runs shell commands with shell rights
 * (this is what lets another app be started on our own virtual display, and be touched there).
 * Commands go through one long-lived shell, one after another.
 */
public final class NvShizuku {
    private NvShizuku() { }

    public static volatile String lastLog = "";          // the last few lines the shell printed (errors show up here)
    private static boolean inited = false;
    private static Process proc;
    private static OutputStream in;
    private static final ExecutorService ex = Executors.newSingleThreadExecutor();
    private static final StringBuilder log = new StringBuilder();

    public static synchronized void init() {
        if (inited) return;
        inited = true;
        try {
            Shizuku.addBinderDeadListener(() -> closeShell());
        } catch (Throwable ignored) { }
    }

    public static boolean alive() { try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; } }

    public static boolean granted() {
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; } catch (Throwable t) { return false; }
    }

    public static boolean ready() { return alive() && granted(); }

    /** shows Shizuku's own "allow this app?" question */
    public static void request() {
        try { if (alive() && !granted()) Shizuku.requestPermission(4711); } catch (Throwable ignored) { }
    }

    public static String statusText() {
        if (!alive()) return "ไม่ทำงาน (เปิดแอป Shizuku แล้วกดเริ่ม)";
        if (!granted()) return "รอสิทธิ์ (กดเพื่อขอ)";
        return "พร้อมใช้";
    }

    private static void closeShell() {
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) { }
        proc = null; in = null;
    }

    private static void pump(final java.io.InputStream s) {
        Thread t = new Thread(() -> {
            try {
                byte[] b = new byte[512];
                int n;
                while ((n = s.read(b)) > 0) {
                    synchronized (log) {
                        log.append(new String(b, 0, n));
                        if (log.length() > 700) log.delete(0, log.length() - 700);
                        lastLog = log.toString();
                    }
                }
            } catch (Throwable ignored) { }
        });
        t.setDaemon(true);
        t.start();
    }

    private static boolean ensureShell() {
        if (proc != null && in != null) {
            try { proc.exitValue(); closeShell(); } catch (IllegalThreadStateException running) { return true; } catch (Throwable ignored) { closeShell(); }
        }
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            proc = (Process) m.invoke(null, new String[]{"sh"}, null, null);
            in = proc.getOutputStream();
            pump(proc.getInputStream());
            pump(proc.getErrorStream());
            return true;
        } catch (Throwable t) {
            synchronized (log) { log.append("shell: ").append(t).append('\n'); lastLog = log.toString(); }
            closeShell();
            return false;
        }
    }

    /** run a shell command (as the shell user) without waiting for it; commands run in the order they were given */
    public static void run(final String cmd) {
        if (!ready()) return;
        ex.execute(() -> {
            try {
                if (!ensureShell()) return;
                in.write((cmd + " 2>&1\n").getBytes("UTF-8"));
                in.flush();
            } catch (Throwable t) {
                synchronized (log) { log.append("run: ").append(t).append('\n'); lastLog = log.toString(); }
                closeShell();
            }
        });
    }

    // ---- fast touch / key injection: a small helper program (NvInputServer) running as the shell user, fed through a pipe ----
    private static Process inProc;
    private static OutputStream inOut;
    private static volatile boolean inReady = false;
    private static final ExecutorService inEx = Executors.newSingleThreadExecutor();

    public static volatile String inputNote = "";
    public static boolean inputReady() { return inReady && inProc != null; }
    public static String inputInfo() { return inputReady() ? "สัมผัสเร็ว ✓" : "สัมผัสช้า" + (inputNote.isEmpty() ? "" : " (" + inputNote + ")"); }

    /** start it again (after an error, or when it was never started) */
    public static void restartInput(String apk) {
        try { if (inProc != null) inProc.destroy(); } catch (Throwable ignored) { }
        inProc = null; inReady = false; inputNote = "";
        startInput(apk);
    }

    private static Process spawn(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    private static void addLog(String t) {
        synchronized (log) { log.append(t).append('\n'); if (log.length() > 700) log.delete(0, log.length() - 700); lastLog = log.toString(); }
    }

    /** start the helper (once). apk = this app's APK path. It says PVR-READY when it works; until then taps go through the slower "input" command. */
    public static void startInput(final String apk) {
        if (!ready()) return;
        inEx.execute(() -> {
            try {
                if (inProc != null) { try { inProc.exitValue(); inProc = null; inReady = false; } catch (IllegalThreadStateException running) { return; } }
                final Process pr = spawn(new String[]{"sh", "-c", "CLASSPATH='" + apk + "' exec app_process /system/bin com.pocketvr.app.NvInputServer"});
                inProc = pr; inOut = pr.getOutputStream(); inReady = false;
                Thread o = new Thread(() -> {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
                        String l;
                        while ((l = r.readLine()) != null) {
                            if (l.contains("PVR-READY")) { inReady = true; inputNote = ""; }
                            else { addLog(l); if (l.contains("PVR-ERR")) inputNote = l.replace("PVR-ERR ", ""); }
                        }
                    } catch (Throwable ignored) { }
                    inReady = false;
                    if (inputNote.isEmpty()) inputNote = "ตัวช่วยหยุดทำงาน";
                });
                o.setDaemon(true); o.start();
                Thread e = new Thread(() -> {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getErrorStream()));
                        String l;
                        while ((l = r.readLine()) != null) { addLog(l); if (inputNote.isEmpty() && l.length() > 3) inputNote = l.length() > 60 ? l.substring(0, 60) : l; }
                    } catch (Throwable ignored) { }
                });
                e.setDaemon(true); e.start();
            } catch (Throwable t) { addLog("input: " + t); inputNote = String.valueOf(t); inProc = null; inReady = false; }
        });
    }

    /** one line for the helper: "d|m|u x y display" or "k keycode display" */
    public static void sendInput(final String line) {
        inEx.execute(() -> {
            try {
                if (inOut == null) return;
                inOut.write((line + "\n").getBytes("UTF-8"));
                inOut.flush();
            } catch (Throwable t) { inReady = false; }
        });
    }

    public static void clearLog() { synchronized (log) { log.setLength(0); lastLog = ""; } }
}
