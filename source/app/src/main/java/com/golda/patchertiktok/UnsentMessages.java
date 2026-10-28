package com.golda.patchertiktok;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a chat message the other person unsent, marked "Unsent" under it. TikTok's message model
 * (found by its "s:is_recalled" ext key) answers isRecalled() and getMessageStatus() for every
 * bind; for a received text message that still has its text, both answer as if it was never
 * unsent. When the unsend arrives, TikTok clears the text through setContent from its recall
 * handlers (RecallMsgFeature / RecallMsgUseCase); that write is skipped. Only messages this phone
 * already received are kept, and your own unsent messages disappear as usual.
 * Idea and first version by RSR1337 (PR #15).
 */
final class UnsentMessages {
    private static final String FLAG = "s:is_recalled";
    private static final String USER_SERVICE = "com.ss.android.ugc.aweme.framework.services.IUserService";
    /** How long the signed-in account id is trusted before it is read again (account switches). */
    private static final long OWN_ID_MS = 30_000L;
    private static final Set<String> RECALLERS = new HashSet<>();
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

        // Both run on every bind: the switch and TikTok's own answer are checked before anything costly.
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
                // A message being built has no text yet, so kept() is false and the stack is never walked.
                if (Prefs.on(Prefs.KEEP_UNSENT) && !RECALLERS.isEmpty() && kept(param.thisObject) && fromRecall()) {
                    param.setResult(null);
                }
            }
        };
        int guards = Hooks.hookAll(model, "setContent", keepContent) + Hooks.hookAll(model, "setContentPB", keepContent);
        installed = hooks > 0;
        RuntimeLog.log("unsent messages: hooks=" + hooks + " guards=" + guards + " recallers=" + RECALLERS.size());
    }

    /** Whether this message was unsent and is shown only because it is kept. */
    static boolean marked(Object message) {
        if (!installed || message == null || !Prefs.on(Prefs.KEEP_UNSENT)) return false;
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

    /** A received message that still has its text. */
    private static boolean kept(Object message) {
        Object content = Reflect.call(message, "getContent");
        return content instanceof String && ((String) content).trim().length() > 2 && !mine(message);
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

    /** The signed-in account id, read from TikTok's user service at most every 30 seconds. */
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
