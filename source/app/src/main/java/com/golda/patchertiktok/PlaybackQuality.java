package com.golda.patchertiktok;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Which stream a video plays. TikTok hands the player its list of renditions through
 * SimVideo.setBitRate / SimVideoUrlModel.setBitRate before it picks one by bandwidth; a filtered
 * copy goes in instead: H.264 only (when the video has it), SDR only, and per network either the
 * best rendition alone or the smallest. "Auto" leaves the list as TikTok sent it. HDR is also
 * turned off at TikTok's own "force HDR off" switch. If a filter leaves nothing, the full list stays.
 * Streams TikTok sends as one packed DASH model are not reached here.
 */
final class PlaybackQuality {
    private static final String[] MODELS = {
            "com.ss.android.ugc.playerkit.simapicommon.model.SimVideo",
            "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel"};
    private static final String PLAYER_CONFIG = "com.ss.android.ugc.aweme.video.simplayer.PlayerConfigImpl";
    static final int AUTO = 0;
    static final int BEST = 1;
    static final int SAVER = 2;

    private static volatile boolean metered;
    private static volatile long checkedAt;

    private PlaybackQuality() { }

    static void install(ClassLoader loader) {
        int hooks = 0;
        for (String name : MODELS) {
            Class<?> model = Hooks.findClass(name, loader);
            if (model == null) continue;
            for (Method method : Reflect.methods(model)) {
                Class<?>[] p = method.getParameterTypes();
                if (!method.getName().equals("setBitRate") || p.length != 1 || p[0] != List.class) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (param.args[0] instanceof List) param.args[0] = choose((List<?>) param.args[0]);
                    }
                });
                hooks++;
            }
        }
        Class<?> config = Hooks.findClass(PLAYER_CONFIG, loader);
        int hdr = 0;
        if (config != null) {
            for (Method method : Reflect.methods(config)) {
                if (!method.getName().equals("isForceHdrOff") || method.getReturnType() != boolean.class) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.NO_HDR)) param.setResult(true);
                    }
                });
                hdr++;
            }
        }
        RuntimeLog.log("playback quality: lists=" + hooks + " hdr=" + hdr);
    }

    private static List<?> choose(List<?> gears) {
        boolean h264 = Prefs.on(Prefs.PREFER_H264);
        boolean sdr = Prefs.on(Prefs.NO_HDR);
        int mode = (int) Prefs.number(metered() ? Prefs.QUALITY_MOBILE : Prefs.QUALITY_WIFI);
        if (gears.size() < 2 || (!h264 && !sdr && mode == AUTO)) return gears;
        List<Object> kept = new ArrayList<>(gears);
        if (h264) kept = keep(kept, gear -> number(gear, "getCodecType") == 0);
        if (sdr) kept = keep(kept, gear -> number(gear, "getHdrType") == 0);
        if (mode == BEST || mode == SAVER) {
            Object chosen = null;
            for (Object gear : kept) {
                if (chosen == null) {
                    chosen = gear;
                    continue;
                }
                int compared = Long.compare(size(gear), size(chosen));
                if (compared == 0) compared = Long.compare(number(gear, "getBitRate"), number(chosen, "getBitRate"));
                if (mode == BEST ? compared > 0 : compared < 0) chosen = gear;
            }
            if (chosen != null) {
                kept = new ArrayList<>();
                kept.add(chosen);
            }
        }
        return kept.isEmpty() ? gears : kept;
    }

    private static List<Object> keep(List<Object> gears, java.util.function.Predicate<Object> wanted) {
        List<Object> kept = new ArrayList<>();
        for (Object gear : gears) if (wanted.test(gear)) kept.add(gear);
        return kept.isEmpty() ? gears : kept;
    }

    /** Pixels of a rendition; its bitrate breaks ties between equal sizes. */
    private static long size(Object gear) {
        return number(gear, "getVideoWidth") * number(gear, "getVideoHeight");
    }

    private static long number(Object gear, String getter) {
        Object value = Reflect.call(gear, getter);
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    /** Mobile data (a metered network), checked at most every few seconds. */
    private static boolean metered() {
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt < 5_000L) return metered;
        checkedAt = now;
        try {
            Context context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            ConnectivityManager connectivity = context == null ? null : context.getSystemService(ConnectivityManager.class);
            metered = connectivity != null && connectivity.isActiveNetworkMetered();
        } catch (ReflectiveOperationException | RuntimeException error) {
            metered = false;
        }
        return metered;
    }
}
