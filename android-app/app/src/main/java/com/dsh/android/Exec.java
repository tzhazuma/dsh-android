package com.dsh.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thin wrapper around fork/exec that captures combined stdout+stderr. */
public final class Exec {
    public static final class Result {
        public int rc = -1;
        public String out = "";
        public String err = "";
        public String exception = null;

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("rc=").append(rc);
            if (exception != null) sb.append(" EXCEPTION=").append(exception);
            sb.append('\n');
            if (!out.isEmpty()) sb.append("stdout:\n").append(out).append('\n');
            if (!err.isEmpty()) sb.append("stderr:\n").append(err).append('\n');
            return sb.toString();
        }
    }

    public interface EnvCustomizer {
        void customize(Map<String, String> env);
    }

    public static Result run(List<String> cmd, File cwd, EnvCustomizer env, long timeoutMs) {
        Result r = new Result();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd);
        pb.redirectErrorStream(false);
        if (env != null) env.customize(pb.environment());
        try {
            Process p = pb.start();
            // Drain both streams on separate threads: a full pipe buffer would
            // otherwise deadlock the child before it ever exits.
            StreamDrainer out = new StreamDrainer(p.getInputStream());
            StreamDrainer err = new StreamDrainer(p.getErrorStream());
            out.start();
            err.start();
            boolean done = waitFor(p, timeoutMs);
            out.join(5000);
            err.join(5000);
            r.rc = done ? p.exitValue() : -999;
            r.out = out.text();
            r.err = err.text();
            if (!done) {
                p.destroy();
                r.err += "\n[TIMEOUT after " + timeoutMs + "ms]";
            }
        } catch (Throwable t) {
            r.exception = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    private static boolean waitFor(Process p, long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            p.waitFor();
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    private static final class StreamDrainer extends Thread {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();

        StreamDrainer(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (sb) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String text() {
            synchronized (sb) {
                return sb.toString();
            }
        }
    }

    public static List<String> cmd(String... parts) {
        List<String> l = new ArrayList<>();
        for (String p : parts) l.add(p);
        return l;
    }

    /** List a directory into a single log-friendly string. */
    public static String listing(File dir) {
        StringBuilder sb = new StringBuilder();
        File[] fs = dir.listFiles();
        if (fs == null) return "(cannot list " + dir + ")";
        for (File f : fs) {
            sb.append(f.getName())
              .append(' ').append(f.length())
              .append(f.canExecute() ? " x" : " -")
              .append('\n');
        }
        return sb.toString();
    }

    public static Context noop() {
        return null;
    }

    private Exec() {}
}
