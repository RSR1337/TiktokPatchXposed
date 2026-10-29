package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Keeps the phone's brightness on TikTok's camera, editor and publishing screens. Each of them
 * starts TikTok's brightness boost through one of two static (host, LifecycleOwner, config)
 * entries: BrightnessManager, or the older one that reads "creative_tools_opti_max_brightness".
 * Skipping those starts leaves the screen alone; the selfie flash and fill light set brightness
 * on their own and keep working.
 */
final class Brightness {
    private static final String MANAGER = "com.ss.android.ugc.aweme.property.BrightnessManager";
    private static final String LEGACY = "creative_tools_opti_max_brightness";
    private static final String OWNER = "androidx.lifecycle.LifecycleOwner";

    private Brightness() { }

    static void install(ClassLoader loader) {
        Class<?> owner = Hooks.findClass(OWNER, loader);
        if (owner == null) return;
        int hooks = 0;
        Class<?> manager = Hooks.findClass(MANAGER, loader);
        if (manager != null) hooks += hookStarts(manager, null, owner);
        for (String hit : Discovery.methodsUsing(LEGACY)) {
            String[] parts = hit.split("#", 2);
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type != null && type != manager) hooks += hookStarts(type, parts[1], owner);
        }
        RuntimeLog.log("keep brightness: starts=" + hooks);
    }

    /** Static void (x, LifecycleOwner, y) methods of {@code type} (named {@code name} when given). */
    private static int hookStarts(Class<?> type, String name, Class<?> owner) {
        int hooks = 0;
        for (Method method : Reflect.methods(type)) {
            Class<?>[] p = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class || p.length != 3
                    || p[1] != owner || (name != null && !method.getName().equals(name))) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    if (Prefs.on(Prefs.KEEP_BRIGHTNESS)) param.setResult(null);
                }
            });
            hooks++;
        }
        return hooks;
    }
}
