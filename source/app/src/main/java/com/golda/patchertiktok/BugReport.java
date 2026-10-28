package com.golda.patchertiktok;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Everything an issue needs, as one text the user saves or copies: module and TikTok versions,
 * whether TikTok runs as 32 or 64 bit, device and memory, the switches that are on, features that
 * did not start, the last Java crashes and the module log of this and the previous launch. No
 * account data, texts the user typed or chat ids are included.
 */
final class BugReport {
    /** Each log is cut to its newest part so the report stays a few hundred kilobytes. */
    private static final int LOG_TAIL = 160 * 1024;

    private BugReport() { }

    static String build(Context context) {
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("TiktokPatchXposed bug report\n");
        out.append("Module: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            out.append("TikTok: ").append(context.getPackageName()).append(' ').append(info.versionName)
                    .append(" (").append(code).append(")\n");
        } catch (Exception error) {
            out.append("TikTok: ").append(context.getPackageName()).append(" (version unknown)\n");
        }
        ApplicationInfo app = context.getApplicationInfo();
        out.append("Process: ").append(Process.is64Bit() ? "64-bit" : "32-bit")
                .append(", libraries ").append(app.nativeLibraryDir).append('\n');
        out.append("Device ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("Framework: ").append(Hooks.backendName()).append('\n');
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Runtime runtime = Runtime.getRuntime();
        out.append("Memory: heap ").append(runtime.maxMemory() >> 20).append(" MB max, ")
                .append((runtime.totalMemory() - runtime.freeMemory()) >> 20).append(" MB used");
        if (manager != null) out.append(", class ").append(manager.getMemoryClass()).append('/').append(manager.getLargeMemoryClass())
                .append(" MB, low RAM ").append(manager.isLowRamDevice());
        out.append('\n');

        out.append("\n--- Switches on\n");
        StringBuilder on = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : Prefs.switches().entrySet()) {
            if (entry.getValue()) on.append(on.length() == 0 ? "" : ", ").append(entry.getKey());
        }
        out.append(on.length() == 0 ? "(none)" : on).append('\n');
        out.append("region=").append(Prefs.region().isEmpty() ? "off" : Prefs.region())
                .append(", feed region=").append(Prefs.feedRegion().isEmpty() ? "same" : Prefs.feedRegion()).append('\n');

        out.append("\n--- Features with problems\n");
        int problems = 0;
        for (String feature : FeatureStatus.features()) {
            String problem = FeatureStatus.problem(feature);
            if (problem == null) continue;
            out.append(feature).append(": ").append(problem).append('\n');
            problems++;
        }
        if (problems == 0) out.append("(none)\n");

        File cache = context.getCacheDir();
        section(out, "Java crashes (native crashes and freezes are not recorded here)", new File(cache, CrashLog.FILE));
        section(out, "Module log, this launch", new File(cache, RuntimeLog.FILE));
        section(out, "Module log, previous launch", new File(cache, RuntimeLog.PREVIOUS));
        String text = out.toString();
        // Also kept in TikTok's cache, for anyone who can read it there (root).
        try (java.io.FileWriter file = new java.io.FileWriter(new File(cache, FILE), false)) {
            file.write(text);
        } catch (Exception ignored) {
            // The saved or copied report is what matters.
        }
        return text;
    }

    static final String FILE = "patcher-report.txt";

    private static void section(StringBuilder out, String title, File file) {
        out.append("\n--- ").append(title).append('\n');
        String text = tail(file);
        out.append(text == null || text.isEmpty() ? "(empty)\n" : text);
        if (text != null && !text.endsWith("\n")) out.append('\n');
    }

    private static String tail(File file) {
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - LOG_TAIL);
            if (skip > 0) in.skip(skip);
            byte[] data = new byte[(int) Math.min(LOG_TAIL, file.length())];
            int read = 0;
            for (int n; read < data.length && (n = in.read(data, read, data.length - read)) > 0; ) read += n;
            String text = new String(data, 0, read, StandardCharsets.UTF_8);
            return skip > 0 ? "(older lines cut)\n" + text.substring(Math.max(0, text.indexOf('\n') + 1)) : text;
        } catch (Exception error) {
            return "(could not read: " + error.getClass().getSimpleName() + ")";
        }
    }
}
