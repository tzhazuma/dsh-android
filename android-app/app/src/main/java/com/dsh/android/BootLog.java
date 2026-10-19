package com.dsh.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide boot status shared between {@link DshService} (which owns the
 * Node process) and {@link MainActivity} (which only displays it).
 *
 * The activity may be destroyed and recreated while the service keeps running,
 * so the activity must be able to re-read everything that happened before it
 * existed. A tiny ring buffer is enough; the durable copy is still dsh.log.
 */
public final class BootLog {
    private static final int MAX = 400;
    private static final List<String> LINES = new ArrayList<>();
    private static volatile String url;
    private static volatile boolean done;
    private static volatile int generation;

    public static void init(android.content.Context ctx) {
        LogFile.init(ctx);
    }

    public static synchronized void say(String s) {
        LINES.add(s);
        while (LINES.size() > MAX) LINES.remove(0);
        generation++;
        LogFile.log("DshAndroid", s);
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static int generation() {
        return generation;
    }

    public static void setUrl(String u) {
        url = u;
        generation++;
    }

    public static String url() {
        return url;
    }

    public static void setDone(boolean d) {
        done = d;
        generation++;
    }

    public static boolean done() {
        return done;
    }

    private BootLog() {}
}
