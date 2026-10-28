package com.golda.patchertiktok;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks what a LIVE recording saves. TikTok lists a LIVE's streams per quality ("origin", "uhd",
 * "hd", "sd", "ld", "ao" for audio only), each with an FLV link and its resolution and codec in
 * "sdk_params". The recording is a plain copy of the FLV stream, so the best stream is the largest
 * H.264 one with an FLV link: H.264 FLV plays in common players, HEVC in FLV often does not.
 */
final class LiveStreams {
    static final class Option {
        final String quality;
        final String flv;
        final int width;
        final int height;
        final String codec;

        Option(String quality, String flv, int width, int height, String codec) {
            this.quality = quality == null ? "" : quality;
            this.flv = flv;
            this.width = width;
            this.height = height;
            this.codec = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
        }

        boolean audioOnly() { return "ao".equals(quality) || quality.startsWith("audio"); }

        boolean h264() { return codec.isEmpty() || codec.contains("264") || codec.contains("avc"); }

        /** The short side, e.g. 1080 for 1920x1080 and 1080x1920; 0 when unknown. */
        int lines() { return Math.min(width, height) > 0 ? Math.min(width, height) : Math.max(width, height); }
    }

    /** Known quality names from best to worst, for streams without a resolution. */
    private static final String[] ORDER = {"origin", "uhd_60", "uhd", "hd_60", "hd", "sd", "ld"};
    private static final Pattern FLV = Pattern.compile("https?://[^\"'\\s\\\\]+?\\.flv[^\"'\\s\\\\]*");

    private LiveStreams() { }

    static List<Option> choices(List<Option> options) {
        List<Option> result = new ArrayList<>();
        Set<String> links = new HashSet<>();
        for (Option option : options) {
            if (option != null && DownloadFormat.webUrl(option.flv) && links.add(option.flv)) result.add(option);
        }
        Collections.sort(result, (a, b) -> {
            if (a.audioOnly() != b.audioOnly()) return a.audioOnly() ? 1 : -1;
            if (a.lines() != b.lines()) return Integer.compare(b.lines(), a.lines());
            if (a.h264() != b.h264()) return a.h264() ? -1 : 1;
            return Integer.compare(rank(a.quality), rank(b.quality));
        });
        return result;
    }

    static String firstFlv(String data) {
        if (data == null) return null;
        Matcher matcher = FLV.matcher(data.replace("\\/", "/").replace("\\u0026", "&"));
        return matcher.find() ? matcher.group() : null;
    }

    static String detail(Option option) {
        StringBuilder text = new StringBuilder();
        if (option.lines() > 0 && !option.quality.isEmpty()) {
            text.append(option.quality.replace('_', ' ').toUpperCase(Locale.ROOT)).append(" · ");
        }
        String codec = codec(option);
        if (codec != null) text.append(codec).append(" · ");
        return text.append("FLV").toString();
    }

    private static String codec(Option option) {
        if (option.codec.isEmpty() || option.audioOnly()) return null;
        if (option.h264()) return "H.264";
        if (option.codec.contains("265") || option.codec.contains("hevc") || option.codec.contains("bytevc1")) return "H.265";
        return option.codec.toUpperCase(Locale.ROOT);
    }

    /** The option to record, or null if none has an FLV link. */
    static Option best(List<Option> options) {
        Option best = null;
        for (Option option : options) {
            if (option == null || !DownloadFormat.webUrl(option.flv) || option.audioOnly()) continue;
            if (best == null || better(option, best)) best = option;
        }
        return best;
    }

    private static boolean better(Option a, Option b) {
        if (a.h264() != b.h264()) return a.h264();
        if (a.lines() != b.lines()) return a.lines() > b.lines();
        return rank(a.quality) < rank(b.quality);
    }

    private static int rank(String quality) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(quality)) return i;
        return ORDER.length;
    }

    /** {"resolution":"720x1280"} → {720, 1280}; {0, 0} when missing or malformed. */
    static int[] resolution(String value) {
        if (value == null) return new int[]{0, 0};
        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("[x*]");
        if (parts.length != 2) return new int[]{0, 0};
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            return w > 0 && h > 0 ? new int[]{w, h} : new int[]{0, 0};
        } catch (NumberFormatException error) {
            return new int[]{0, 0};
        }
    }

    /** "1080p", or the quality name when the resolution is unknown. */
    static String label(Option option) {
        int lines = option.lines();
        return lines > 0 ? lines + "p" : option.quality.toUpperCase(Locale.ROOT);
    }

    /** "mm:ss" up to an hour, "h:mm:ss" after. */
    static String elapsed(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
