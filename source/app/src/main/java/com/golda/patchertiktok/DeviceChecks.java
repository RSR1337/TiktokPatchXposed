package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * What TikTok checks and runs on the device itself. Root: its yes/no probes (found by the su paths
 * they test) answer no, and the two shell probes that run "which su" and "su -c id" are not run at
 * all, which also keeps the root manager's prompt away; they answer in their own map shape.
 * Passkey: TikTok's "is this device able to use passkeys" check answers no, so it falls back to
 * the normal sign-in and stops offering passkey setup. Motion: the activity recognition service
 * that reads the accelerometer and gyroscope while videos play is never started.
 */
final class DeviceChecks {
    /** Literals of the boolean root probes: the general one and the crash reporter's. */
    private static final String[] ROOT_PROBES = {"/system/app/Superuser.apk", "/system/usr/we-need-root/su"};
    private static final String PASSKEY = "com.ss.android.ugc.aweme.account.login.passkey.CredentialManagerService";
    private static final String GMS = "com.google.android.gms.common.GoogleApiAvailability#getInstance";
    private static final String HAR = "com.ss.android.ugc.aweme.ml.impl.har.SmartHARServiceImpl";

    private DeviceChecks() { }

    static void install(ClassLoader loader) {
        RuntimeLog.log("device checks: root=" + installRoot(loader) + " passkey=" + installPasskey(loader)
                + " motion=" + installMotion(loader));
    }

    private static int installRoot(ClassLoader loader) {
        int hooks = 0;
        for (String literal : ROOT_PROBES) {
            for (Method method : methods(loader, literal)) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class
                        || method.getParameterCount() != 0) continue;
                Hooks.hook(method, answer(Prefs.HIDE_ROOT, false));
                hooks++;
            }
        }
        Map<String, String> which = new HashMap<>();
        which.put("state", "not_detected");
        which.put("path", "");
        hooks += shellProbe(loader, "which su", which);
        Map<String, String> uid = new HashMap<>();
        uid.put("state", "not_detected");
        uid.put("uidZero", "false");
        hooks += shellProbe(loader, "uidZero", uid);
        return hooks;
    }

    /** A static () -> Map probe using {@code literal}: answered with a fresh copy of {@code clean}, nothing is run. */
    private static int shellProbe(ClassLoader loader, String literal, Map<String, String> clean) {
        int hooks = 0;
        for (Method method : methods(loader, literal)) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || !Map.class.isAssignableFrom(method.getReturnType())) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    if (Prefs.on(Prefs.HIDE_ROOT)) param.setResult(new HashMap<>(clean));
                }
            });
            hooks++;
        }
        return hooks;
    }

    /** The service's () -> boolean that asks Google Play services for passkey support. */
    private static int installPasskey(ClassLoader loader) {
        Class<?> service = Hooks.findClass(PASSKEY, loader);
        if (service == null) return 0;
        int hooks = 0;
        for (Method method : Reflect.methods(service)) {
            if (method.getReturnType() != boolean.class || method.getParameterCount() != 0
                    || !Discovery.invokedBy(service.getName(), method.getName()).contains(GMS)) continue;
            Hooks.hook(method, answer(Prefs.HIDE_PASSKEY, false));
            hooks++;
        }
        return hooks;
    }

    private static int installMotion(ClassLoader loader) {
        Class<?> har = Hooks.findClass(HAR, loader);
        if (har == null) return 0;
        int hooks = 0;
        for (Method method : Reflect.methods(har)) {
            if (method.getParameterCount() != 0) continue;
            if (method.getName().equals("enable") && method.getReturnType() == boolean.class) {
                Hooks.hook(method, answer(Prefs.NO_MOTION_SENSORS, false));
                hooks++;
            } else if (method.getName().equals("checkAndInit") && method.getReturnType() == void.class) {
                Hooks.hook(method, answer(Prefs.NO_MOTION_SENSORS, null));
                hooks++;
            }
        }
        return hooks;
    }

    private static Hooks.Hook answer(String key, Object value) {
        return new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(key)) param.setResult(value);
            }
        };
    }

    private static java.util.List<Method> methods(ClassLoader loader, String literal) {
        java.util.List<Method> found = new java.util.ArrayList<>();
        for (String hit : Discovery.methodsUsing(literal)) {
            String[] parts = hit.split("#", 2);
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type == null) continue;
            for (Method method : Reflect.methods(type)) if (method.getName().equals(parts[1])) found.add(method);
        }
        return found;
    }
}
