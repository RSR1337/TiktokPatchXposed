package com.golda.patchertiktok;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Photo posts in the best copy TikTok's server has. Each photo comes with its display copy and a
 * list of other sizes (bitrate_images); TikTok usually shows a middle one picked for the network.
 * Before TikTok turns a post's photos into what it displays (the converter that logs
 * "computeSinglePhotoModeImage position : "), the largest copy within 8 megapixels and 4096 px
 * on the long side becomes the display copy, and the network pick follows it. Saving a photo then
 * gets that copy too.
 */
final class PhotoQuality {
    private static final String CONVERTER = "computeSinglePhotoModeImage position : ";
    private static final String INFO = "com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo";
    private static final String DONE = "com.golda.patchertiktok.photoBest";
    private static final long MAX_PIXELS = 8_000_000L;
    private static final int MAX_SIDE = 4096;

    private PhotoQuality() { }

    static void install(ClassLoader loader) {
        Class<?> info = Hooks.findClass(INFO, loader);
        if (info == null) return;
        int hooks = 0;
        for (String hit : Discovery.methodsUsing(CONVERTER)) {
            Class<?> owner = Hooks.findClass(hit.split("#", 2)[0], loader);
            if (owner == null) continue;
            for (Method method : Reflect.methods(owner)) {
                Class<?>[] p = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) || p.length == 0 || p[0] != info) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.PHOTO_BEST)) upgrade(param.args[0]);
                    }
                });
                hooks++;
            }
        }
        RuntimeLog.log("photo quality: converters=" + hooks);
    }

    private static void upgrade(Object info) {
        Object images = Reflect.call(info, "getImageList");
        if (!(images instanceof List)) return;
        for (Object image : (List<?>) images) {
            if (image == null || Hooks.getExtra(image, DONE) != null) continue;
            Hooks.setExtra(image, DONE, Boolean.TRUE);
            Object display = Reflect.get(image, "displayImageNoWatermark");
            Object best = display;
            long bestPixels = pixels(display);
            Object copies = Reflect.get(image, "bitrateImages");
            if (copies instanceof List) {
                for (Object copy : (List<?>) copies) {
                    Object url = Reflect.get(copy, "image");
                    long size = pixels(url);
                    if (size > bestPixels && fits(url)) {
                        best = url;
                        bestPixels = size;
                    }
                }
            }
            if (best == display || best == null) continue;
            set(image, "displayImageNoWatermark", best);
            if (Reflect.get(image, "targetMultiRateImageUrl") != null) set(image, "targetMultiRateImageUrl", best);
        }
    }

    private static long pixels(Object url) {
        Object width = Reflect.call(url, "getWidth");
        Object height = Reflect.call(url, "getHeight");
        Object list = Reflect.call(url, "getUrlList");
        if (!(width instanceof Integer) || !(height instanceof Integer) || !(list instanceof List) || ((List<?>) list).isEmpty()) return 0;
        return (long) (Integer) width * (Integer) height;
    }

    private static boolean fits(Object url) {
        int width = (Integer) Reflect.call(url, "getWidth");
        int height = (Integer) Reflect.call(url, "getHeight");
        return Math.max(width, height) <= MAX_SIDE && (long) width * height <= MAX_PIXELS;
    }

    private static void set(Object target, String name, Object value) {
        Field field = Reflect.field(target.getClass(), name);
        if (field == null || !field.getType().isInstance(value)) return;
        try {
            field.set(target, value);
        } catch (IllegalAccessException ignored) {
            // Left as TikTok chose.
        }
    }
}
