package com.golda.patchertiktok;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class UnsentMessages {
    private static final String FLAG = "s:is_recalled";
    private static final String USER_SERVICE = "com.ss.android.ugc.aweme.framework.services.IUserService";
    private static final String ATTACHMENT_DAO = "IMAttachmentDao getAttachments";
    private static final long OWN_ID_MS = 30_000L;
    private static final Set<String> RECALLERS = new HashSet<>();
    private static final Set<String> KEPT = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile boolean installed;
    private static volatile ClassLoader classLoader;
    private static volatile Object users;
    private static volatile String ownId;
    private static volatile long ownIdRead;

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
                if (Prefs.on(Prefs.KEEP_UNSENT) && Boolean.TRUE.equals(param.getResult()) && kept(param.thisObject)) {
                    param.setResult(false);
                }
            }
        });
        Hooks.hook(status, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.KEEP_UNSENT)) return;
                Object result = param.getResult();
                if (result instanceof Enum && "RECALLED".equals(((Enum<?>) result).name()) && kept(param.thisObject)) {
                    param.setResult(available);
                }
            }
        });
        Hooks.Hook keepContent = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.KEEP_UNSENT) && !RECALLERS.isEmpty() && param.args.length == 1 && blank(param.args[0])
                        && kept(param.thisObject) && fromRecall()) {
                    Object uuid = Reflect.call(param.thisObject, "getUuid");
                    if (uuid instanceof String && KEPT.add((String) uuid)) MessageTime.unsent((String) uuid);
                    param.setResult(null);
                }
            }
        };
        int guards = Hooks.hookAll(model, "setContent", keepContent) + Hooks.hookAll(model, "setContentPB", keepContent);
        int attachments = keepAttachments(loader);
        installed = hooks > 0;
        RuntimeLog.log("unsent messages: hooks=" + hooks + " guards=" + guards + " attachments=" + attachments
                + " recallers=" + RECALLERS.size());
    }

    private static int keepAttachments(ClassLoader loader) {
        Class<?> dao = Discovery.firstClass(loader, ATTACHMENT_DAO);
        if (dao == null) return 0;
        Hooks.Hook keepRows = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                Object uuid = param.args[0];
                if (Prefs.on(Prefs.KEEP_UNSENT) && uuid instanceof String && KEPT.contains(uuid) && fromRecall()) {
                    param.setResult(null);
                }
            }
        };
        int count = 0;
        for (Method method : dao.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || p.length != 1 || p[0] != String.class) continue;
            if (Hooks.tryHook(method, keepRows)) count++;
        }
        return count;
    }

    static boolean marked(Object message) {
        if (!installed || message == null || !Prefs.on(Prefs.KEEP_UNSENT)) return false;
        Object ext = Reflect.call(message, "getExt");
        if (!(ext instanceof Map)) return false;
        Map<?, ?> values = (Map<?, ?>) ext;
        boolean recalled = "1".equals(values.get(FLAG)) || "true".equals(values.get("s:recalled"));
        if (!recalled && !KEPT.isEmpty()) {
            Object uuid = Reflect.call(message, "getUuid");
            recalled = uuid instanceof String && KEPT.contains(uuid);
        }
        return recalled && kept(message);
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
        Object content = Reflect.call(message, "getContent");
        boolean text = content instanceof String && ((String) content).trim().length() > 2;
        if (!text && blank(Reflect.call(message, "getContentPB"))) {
            Object attachments = Reflect.call(message, "getAttachments");
            if (!(attachments instanceof Collection) || ((Collection<?>) attachments).isEmpty()) return false;
        }
        return !mine(message);
    }

    private static boolean blank(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence) return ((CharSequence) value).length() == 0;
        Object size = Reflect.call(value, "size");
        return size instanceof Integer && (Integer) size == 0;
    }

    private static boolean fromRecall() {
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
        long now = SystemClock.uptimeMillis();
        String cached = ownId;
        if (cached != null && now - ownIdRead < OWN_ID_MS) return cached;
        try {
            if (users == null) users = StreakApi.service(classLoader, USER_SERVICE);
            Object id = Reflect.call(users, "getCurrentUserID");
            ownId = id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable error) {
            ownId = null;
        }
        ownIdRead = now;
        return ownId;
    }

    private static Object constant(Class<?> type, String name) {
        Object[] values = type.getEnumConstants();
        if (values == null) return null;
        for (Object value : values) if (name.equals(((Enum<?>) value).name())) return value;
        return null;
    }
}
