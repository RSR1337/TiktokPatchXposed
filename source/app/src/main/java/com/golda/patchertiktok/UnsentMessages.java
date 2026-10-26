package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class UnsentMessages {
    private static final String FLAG = "s:is_recalled";
    private static final String USER_SERVICE = "com.ss.android.ugc.aweme.framework.services.IUserService";
    private static final Set<String> RECALLERS = new HashSet<>();
    private static volatile boolean installed;
    private static volatile ClassLoader classLoader;
    private static volatile Object users;

    private UnsentMessages() { }

    static void install(ClassLoader loader) {
        classLoader = loader;
        Class<?> model = model(loader);
        if (model == null) {
            RuntimeLog.log("unsent messages unavailable: message model not found");
            return;
        }
        Method status = Reflect.method(model, "getMessageStatus");
        Object available = constant(status.getReturnType(), "AVAILABLE");
        if (available == null) {
            RuntimeLog.log("unsent messages unavailable: message status not recognised");
            return;
        }
        RECALLERS.addAll(Discovery.classesUsing("RecallMsgFeature"));
        RECALLERS.addAll(Discovery.classesUsing("RecallMsgUseCase"));

        int hooks = Hooks.hookAll(model, "isRecalled", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Boolean.TRUE.equals(param.getResult()) && kept(param.thisObject)) param.setResult(false);
            }
        });
        Hooks.hook(status, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object result = param.getResult();
                if (result instanceof Enum && "RECALLED".equals(((Enum<?>) result).name()) && kept(param.thisObject)) {
                    param.setResult(available);
                }
            }
        });
        Hooks.Hook keepContent = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (kept(param.thisObject) && fromRecall()) param.setResult(null);
            }
        };
        int guards = Hooks.hookAll(model, "setContent", keepContent) + Hooks.hookAll(model, "setContentPB", keepContent);
        installed = hooks > 0;
        RuntimeLog.log("unsent messages: " + model.getName() + " hooks=" + hooks + " guards=" + guards
                + " recallers=" + RECALLERS);
    }

    static boolean marked(Object message) {
        if (!installed || message == null) return false;
        Object ext = Reflect.call(message, "getExt");
        if (!(ext instanceof Map)) return false;
        Map<?, ?> values = (Map<?, ?>) ext;
        return ("1".equals(values.get(FLAG)) || "true".equals(values.get("s:recalled"))) && kept(message);
    }

    private static Class<?> model(ClassLoader loader) {
        for (String name : Discovery.classesUsing(FLAG)) {
            Class<?> type = Hooks.findClass(name, loader);
            if (type != null && Reflect.method(type, "isRecalled") != null && Reflect.method(type, "getUuid") != null
                    && Reflect.method(type, "getMessageStatus") != null) {
                return type;
            }
        }
        return null;
    }

    private static boolean kept(Object message) {
        if (!Prefs.on(Prefs.KEEP_UNSENT)) return false;
        Object content = Reflect.call(message, "getContent");
        return content instanceof String && ((String) content).trim().length() > 2 && !mine(message);
    }

    private static boolean fromRecall() {
        if (RECALLERS.isEmpty()) return false;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (RECALLERS.contains(frame.getClassName())) return true;
        }
        return false;
    }

    private static boolean mine(Object message) {
        Object sender = Reflect.call(message, "getSender");
        String own = ownId();
        return sender != null && own != null && own.equals(String.valueOf(sender));
    }

    private static String ownId() {
        try {
            if (users == null) users = StreakApi.service(classLoader, USER_SERVICE);
            Object id = Reflect.call(users, "getCurrentUserID");
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object constant(Class<?> type, String name) {
        Object[] values = type.getEnumConstants();
        if (values == null) return null;
        for (Object value : values) if (name.equals(((Enum<?>) value).name())) return value;
        return null;
    }
}
