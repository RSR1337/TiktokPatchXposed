package com.golda.patchertiktok;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Analytics, in two levels. Third-party trackers: Firebase/Google and Facebook read their own
 * documented off switches from the app's manifest metadata, so TikTok's metadata gets those
 * switches; AppsFlyer is never started. Nothing of TikTok itself changes.
 * TikTok's own event log (AppLog): its three upload points answer "delivered" without sending,
 * so TikTok drops the events it queued instead of retrying. Device registration, request
 * signing, IDs and the feed itself are untouched. Recommendations learn less from what you watch.
 */
final class Analytics {
    private static final String APPSFLYER = "com.appsflyer.AppsFlyerLib";
    private static final String PRIORITY = "com.bytedance.applog.priority.PriorityCallbackImpl";
    private static final String PRIORITY_RESPONSE = "com.bytedance.applog.priority.PriorityHttpResponse";
    /** The AppLog pack upload: a 10-argument int method holding both literals. */
    private static final String[] PACK = {"ss_app_log", "applog_forward"};
    private static final String FORWARD = "trySendForward start requestId={}, url={}";
    /** What AppLog's server answers when it accepted a priority batch. */
    private static final String ACCEPTED = "{\"message\":\"success\",\"magic_tag\":\"ss_app_log\"}";

    private Analytics() { }

    /** Before the Application attaches: the trackers read the metadata as they start. */
    static void installTrackers() {
        Hooks.Hook switches = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object result = param.getResult();
                if (!(result instanceof ApplicationInfo) || !Prefs.on(Prefs.NO_TRACKERS)) return;
                ApplicationInfo info = (ApplicationInfo) result;
                if (!Module.targets(info.packageName)) return;
                Bundle meta = info.metaData == null ? new Bundle() : new Bundle(info.metaData);
                meta.putBoolean("firebase_analytics_collection_deactivated", true);
                meta.putBoolean("firebase_analytics_collection_enabled", false);
                meta.putBoolean("google_analytics_adid_collection_enabled", false);
                meta.putBoolean("firebase_performance_collection_deactivated", true);
                meta.putBoolean("com.facebook.sdk.AutoLogAppEventsEnabled", false);
                meta.putBoolean("com.facebook.sdk.AdvertiserIDCollectionEnabled", false);
                ApplicationInfo copy = new ApplicationInfo(info);
                copy.metaData = meta;
                param.setResult(copy);
            }
        };
        Class<?> manager = Hooks.findClass("android.app.ApplicationPackageManager", null);
        int hooks = manager == null ? 0 : Hooks.hookAll(manager, "getApplicationInfo", switches);
        RuntimeLog.log("trackers: metadata hooks=" + hooks);
    }

    /** After Discovery.init: AppsFlyer, then TikTok's own uploads. */
    static void install(ClassLoader loader) {
        int appsFlyer = installAppsFlyer(loader);
        int applog = installAppLog(loader);
        RuntimeLog.log("analytics: appsflyer=" + appsFlyer + " applog=" + applog);
    }

    private static int installAppsFlyer(ClassLoader loader) {
        Class<?> lib = Hooks.findClass(APPSFLYER, loader);
        if (lib == null) return 0;
        Object instance;
        try {
            instance = lib.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return 0;
        }
        if (instance == null) return 0;
        Hooks.Hook skip = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.NO_TRACKERS)) param.setResult(null);
            }
        };
        return Hooks.hookAll(instance.getClass(), "start", skip) + Hooks.hookAll(instance.getClass(), "logEvent", skip);
    }

    private static int installAppLog(ClassLoader loader) {
        int hooks = 0;
        List<String> withForward = Discovery.methodsUsing(PACK[1]);
        for (String hit : Discovery.methodsUsing(PACK[0])) {
            if (!withForward.contains(hit)) continue;
            for (Method method : methods(loader, hit)) {
                Class<?>[] p = method.getParameterTypes();
                if (method.getReturnType() != int.class || p.length != 10 || p[1] != byte[].class) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.NO_TIKTOK_ANALYTICS)) param.setResult(200);
                    }
                });
                hooks++;
            }
        }
        for (String hit : Discovery.methodsUsing(FORWARD)) {
            for (Method method : methods(loader, hit)) {
                if (method.getReturnType() != void.class || method.getParameterCount() != 3) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.NO_TIKTOK_ANALYTICS)) param.setResult(null);
                    }
                });
                hooks++;
            }
        }
        Class<?> priority = Hooks.findClass(PRIORITY, loader);
        Class<?> response = Hooks.findClass(PRIORITY_RESPONSE, loader);
        if (priority != null && response != null) {
            try {
                java.lang.reflect.Constructor<?> accepted = response.getConstructor(int.class, String.class, String.class);
                for (Method method : Reflect.methods(priority)) {
                    if (!method.getName().equals("doHttpPost") || method.getReturnType() != response) continue;
                    Hooks.hook(method, new Hooks.Hook() {
                        @Override protected void before(Hooks.Call param) {
                            if (!Prefs.on(Prefs.NO_TIKTOK_ANALYTICS)) return;
                            try {
                                param.setResult(accepted.newInstance(200, "", ACCEPTED));
                            } catch (ReflectiveOperationException error) {
                                // Sent as usual.
                            }
                        }
                    });
                    hooks++;
                }
            } catch (NoSuchMethodException | LinkageError ignored) {
                // No priority channel in this version.
            }
        }
        return hooks;
    }

    private static List<Method> methods(ClassLoader loader, String hit) {
        List<Method> found = new java.util.ArrayList<>();
        String[] parts = hit.split("#", 2);
        Class<?> type = Hooks.findClass(parts[0], loader);
        if (type == null) return found;
        for (Method method : Reflect.methods(type)) {
            if (method.getName().equals(parts[1]) && !Modifier.isAbstract(method.getModifiers())) found.add(method);
        }
        return found;
    }
}
