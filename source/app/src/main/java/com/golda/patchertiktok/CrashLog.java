package com.golda.patchertiktok;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps TikTok's Java crashes for the bug report: every uncaught exception passes through
 * Thread.dispatchUncaughtException before any handler (TikTok installs its own), so the stack is
 * written to cache/patcher-crash.log there and the crash then goes on as usual. Native crashes
 * and freezes do not pass this way; the report says so.
 */
final class CrashLog {
    static final String FILE = "patcher-crash.log";
    private static final long LIMIT = 128 * 1024;
    private static volatile File file;

    private CrashLog() { }

    static void install(Context context) {
        file = new File(context.getCacheDir(), FILE);
        Hooks.findAndHook(Thread.class, "dispatchUncaughtException", Throwable.class, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                write((Thread) param.thisObject, (Throwable) param.args[0]);
            }
        });
    }

    private static synchronized void write(Thread thread, Throwable error) {
        File target = file;
        if (target == null || error == null) return;
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        // A crash loop keeps only the newest crashes.
        boolean append = target.length() < LIMIT;
        try (FileWriter out = new FileWriter(target, append)) {
            out.write("=== " + time + " · thread " + thread.getName() + " · module " + BuildConfig.VERSION_NAME + "\n");
            out.write(stack.toString());
            out.write("\n");
        } catch (Throwable ignored) {
            // Nothing more can be done while crashing.
        }
    }
}
