package com.golda.patchertiktok;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which features found what they need in this TikTok build. Every feature installs inside
 * {@link Module}'s run(); a problem it logs while installing ("... unavailable", "... not found")
 * is kept here for the status screen.
 */
final class FeatureStatus {
    private static final Pattern PROBLEM = Pattern.compile("unavailable|not found|not recognised|ambiguous|missing");
    private static final List<String> FEATURES = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> PROBLEMS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile String current;

    private FeatureStatus() { }

    static void begin(String feature) {
        current = feature;
        if (!FEATURES.contains(feature)) FEATURES.add(feature);
    }

    static void end() { current = null; }

    static void failed(String feature, String reason) {
        if (!FEATURES.contains(feature)) FEATURES.add(feature);
        PROBLEMS.putIfAbsent(feature, reason);
    }

    /** Called for every runtime log line; only problems during an install are kept. */
    static void observe(String message) {
        String feature = current;
        if (feature != null && message != null && PROBLEM.matcher(message).find()) PROBLEMS.putIfAbsent(feature, message);
    }

    static List<String> features() {
        synchronized (FEATURES) {
            return new ArrayList<>(FEATURES);
        }
    }

    static String problem(String feature) { return PROBLEMS.get(feature); }

    /** An install error for the status screen: its type, message and where in the module it came from. */
    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.getClass().getSimpleName());
        if (error.getMessage() != null) text.append(": ").append(error.getMessage());
        for (StackTraceElement frame : error.getStackTrace()) {
            if (!frame.getClassName().startsWith(FeatureStatus.class.getPackage().getName())) continue;
            String file = frame.getFileName();
            text.append(" (").append(file != null ? file : frame.getClassName()).append(':').append(frame.getLineNumber()).append(')');
            break;
        }
        return text.length() > 160 ? text.substring(0, 157) + "…" : text.toString();
    }

    static int problems() { return PROBLEMS.size(); }
}
