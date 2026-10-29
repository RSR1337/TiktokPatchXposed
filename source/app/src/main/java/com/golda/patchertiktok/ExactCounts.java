package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.NumberFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Likes, comments, shares, views and profile counts in full ("1 234 567" instead of "1.2M").
 * TikTok shortens them in static (long) -> String helpers: one family carries the "1.0M"/"1.0B"
 * rounding literals, the locale-aware family calls the compact number formatter. The feed buttons
 * and grid cells (named view models and cells) lead to both. Profile counts go through one static
 * (int, Number, String) -> CharSequence helper of the relation rows. Only the shown text changes.
 */
final class ExactCounts {
    private static final String[] CALLERS = {
            "com.ss.android.ugc.aweme.feed.assem.digg.VideoDiggVM",
            "com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentVM",
            "com.ss.android.ugc.aweme.feed.assem.share.VideoShareViewModel",
            "com.ss.android.ugc.aweme.cell.BaseVideoCell"};
    private static final String[] PROFILE_CALLERS = {
            "com.ss.android.ugc.profile.business.ur.platform.business.RelationFollowerProtocol",
            "com.ss.android.ugc.profile.business.ur.platform.business.RelationFollowingProtocol",
            "com.ss.android.ugc.profile.business.ur.platform.business.RelationLikeProtocol"};
    private static final ThreadLocal<NumberFormat[]> FORMAT = ThreadLocal.withInitial(() -> new NumberFormat[1]);
    private static final ThreadLocal<Locale[]> FORMAT_LOCALE = ThreadLocal.withInitial(() -> new Locale[1]);

    private ExactCounts() { }

    static void install(ClassLoader loader) {
        Set<Method> shorteners = new LinkedHashSet<>();
        // The literal family, wherever it is used.
        for (String hit : Discovery.methodsUsing("1.0B")) {
            for (Method method : resolve(loader, hit)) {
                if (isShortener(method) && Discovery.methodsUsing("1.0M").contains(hit)) shorteners.add(method);
            }
        }
        // The compact family: what the feed's count screens call that itself calls a (Number, …) -> CharSequence formatter.
        for (String name : CALLERS) {
            Class<?> owner = Hooks.findClass(name, loader);
            if (owner == null) continue;
            for (Method method : Reflect.methods(owner)) {
                for (String call : Discovery.invokedBy(owner.getName(), method.getName())) {
                    for (Method candidate : resolve(loader, call)) {
                        if (isShortener(candidate) && callsCompactFormatter(loader, candidate)) shorteners.add(candidate);
                    }
                }
            }
        }
        for (Method method : shorteners) {
            Hooks.tryHook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.EXACT_COUNTS) || !(param.args[0] instanceof Long)) return;
                    long value = (Long) param.args[0];
                    if (value >= 10_000) param.setResult(full(value));
                }
            });
        }
        int profile = installProfile(loader);
        RuntimeLog.log("exact counts: shorteners=" + shorteners.size() + " profile=" + profile);
    }

    private static int installProfile(ClassLoader loader) {
        Set<Method> helpers = new LinkedHashSet<>();
        for (String name : PROFILE_CALLERS) {
            Class<?> owner = Hooks.findClass(name, loader);
            if (owner == null) continue;
            for (Method method : Reflect.methods(owner)) {
                for (String call : Discovery.invokedBy(owner.getName(), method.getName())) {
                    for (Method candidate : resolve(loader, call)) {
                        Class<?>[] p = candidate.getParameterTypes();
                        if (Modifier.isStatic(candidate.getModifiers()) && candidate.getReturnType() == CharSequence.class
                                && p.length == 3 && p[0] == int.class && p[1] == Number.class && p[2] == String.class) {
                            helpers.add(candidate);
                        }
                    }
                }
            }
        }
        for (Method helper : helpers) {
            Hooks.tryHook(helper, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    // Null or negative: TikTok's own fallback text stays.
                    if (!Prefs.on(Prefs.EXACT_COUNTS) || !(param.args[1] instanceof Number)) return;
                    long value = ((Number) param.args[1]).longValue();
                    if (value >= 10_000) param.setResult(full(value));
                }
            });
        }
        return helpers.size();
    }

    private static boolean isShortener(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return Modifier.isStatic(method.getModifiers()) && method.getReturnType() == String.class && p.length == 1 && p[0] == long.class;
    }

    /** Whether {@code method} calls a static (Number, …) -> CharSequence formatter, as TikTok's compact counts do. */
    private static boolean callsCompactFormatter(ClassLoader loader, Method method) {
        for (String call : Discovery.invokedBy(method.getDeclaringClass().getName(), method.getName())) {
            for (Method callee : resolve(loader, call)) {
                Class<?>[] p = callee.getParameterTypes();
                if (Modifier.isStatic(callee.getModifiers()) && callee.getReturnType() == CharSequence.class
                        && p.length >= 3 && p[0] == Number.class) return true;
            }
        }
        return false;
    }

    private static List<Method> resolve(ClassLoader loader, String entry) {
        List<Method> found = new java.util.ArrayList<>();
        String[] parts = entry.split("#", 2);
        if (parts.length != 2 || parts[1].startsWith("<")) return found;
        Class<?> type = Hooks.findClass(parts[0], loader);
        if (type == null || type.getName().startsWith("java.") || type.getName().startsWith("android.")) return found;
        for (Method method : Reflect.methods(type)) if (method.getName().equals(parts[1])) found.add(method);
        return found;
    }

    /** The count with the phone language's digit grouping. */
    static String full(long value) {
        Locale locale = Locale.getDefault();
        NumberFormat[] cache = FORMAT.get();
        if (cache[0] == null || !locale.equals(FORMAT_LOCALE.get()[0])) {
            cache[0] = NumberFormat.getIntegerInstance(locale);
            cache[0].setGroupingUsed(true);
            FORMAT_LOCALE.get()[0] = locale;
        }
        return cache[0].format(value);
    }
}
