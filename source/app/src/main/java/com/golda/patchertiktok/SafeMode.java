package com.golda.patchertiktok;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * When TikTok keeps closing right after it starts, the module stands back once: every launch is
 * counted until it has run for 20 seconds, and after three short launches in a row (within ten
 * minutes) the next one starts with only the settings and the bug report. TikTok then opens, so
 * the report with the recorded crash can be saved. A launch that lasts resets the count.
 */
final class SafeMode {
    private static final String FILE = "patcher-launches";
    private static final int SHORT_LAUNCHES = 3;
    private static final long WINDOW_MS = 10 * 60_000L;
    private static final long STABLE_MS = 20_000L;
    private static volatile boolean active;
    private static volatile File file;

    private SafeMode() { }

    static boolean active() { return active; }

    /** Called as TikTok's main process loads, before anything else is hooked. */
    static void enter(String packageName) {
        File cache = new File("/data/user/" + Process.myUid() / 100_000 + "/" + packageName + "/cache");
        if (!cache.isDirectory()) cache = new File("/data/data/" + packageName + "/cache");
        file = new File(cache, FILE);
        long now = System.currentTimeMillis();
        int count = 0;
        try {
            if (file.isFile()) {
                String[] saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split(" ");
                if (saved.length == 2 && now - Long.parseLong(saved[1]) < WINDOW_MS) count = Integer.parseInt(saved[0]);
            }
        } catch (Throwable ignored) {
            // A broken file counts as no short launches.
        }
        active = count >= SHORT_LAUNCHES;
        // In safe mode the count starts over, so the next launch is a normal one again.
        save(active ? 0 : count + 1, now);
    }

    /** Called once the app has started: a launch that survives a while is not a short one. */
    static void started() {
        new Handler(Looper.getMainLooper()).postDelayed(Safe.run("safe mode", () -> save(0, System.currentTimeMillis())), STABLE_MS);
    }

    private static void save(int count, long time) {
        File target = file;
        if (target == null) return;
        try (FileWriter out = new FileWriter(target, false)) {
            out.write(count + " " + time);
        } catch (Throwable ignored) {
            // Without the file the module simply never enters safe mode.
        }
    }
}
