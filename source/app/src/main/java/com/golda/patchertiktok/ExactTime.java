package com.golda.patchertiktok;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dates as a date and time instead of "2d ago": under feed videos and in story headers, on
 * comments and on reposts. TikTok formats each of these with small static helpers that take
 * milliseconds; while one of the named screens binds, those helpers answer with the absolute time
 * instead. The feed row formats inline and then prefixes "· " in one static (String) -> String
 * of its view model; there the post's own time replaces the text. Model times are not touched.
 */
final class ExactTime {
    private static final String FEED_VM = "com.ss.android.ugc.aweme.feed.assem.videoauthorinfo.VideoAuthorInfoVM";
    private static final String ITEM = "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    private static final String CELL = "com.ss.android.ugc.aweme.commentv2.commentlist.powercell.BaseCommentCell";
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    /** Repost panels and the profile repost tab: their dates go through the same helpers. */
    private static final String[] REPOSTS = {
            "com.ss.android.ugc.aweme.upvote.detail.panel.UpvotePanelListCell",
            "com.ss.android.ugc.aweme.upvote.detail.replypanel.list.BaseRepostCell",
            "com.ss.android.ugc.aweme.upvote.detail.reposttab.RepostListCell"};
    /** Times TikTok can show: after 2014, before tomorrow. Anything else is a count, not a time. */
    private static final long EARLIEST = 1_400_000_000_000L;

    /** Nesting depth of the screens whose date helpers answer with absolute times. */
    private static final ThreadLocal<int[]> SCOPE = ThreadLocal.withInitial(() -> new int[1]);
    /** The feed post's time in seconds while its row state is built, else 0. */
    private static final ThreadLocal<long[]> POST = ThreadLocal.withInitial(() -> new long[1]);

    private ExactTime() { }

    static void install(ClassLoader loader) {
        Set<Method> helpers = new LinkedHashSet<>();
        int scopes = 0;
        Class<?> cell = Hooks.findClass(CELL, loader);
        if (cell != null) {
            for (Method method : Reflect.methods(cell)) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[0] != int.class || p[1] != boolean.class || method.getReturnType() != void.class) continue;
                List<String> calls = Discovery.invokedBy(cell.getName(), method.getName());
                if (!calls.contains(COMMENT + "#getCreateTime")) continue;
                scopes += scope(method);
                helpers.addAll(dateHelpers(loader, calls));
            }
        }
        for (String name : REPOSTS) {
            Class<?> owner = Hooks.findClass(name, loader);
            if (owner == null) continue;
            for (Method method : Reflect.methods(owner)) {
                if (Modifier.isAbstract(method.getModifiers())) continue;
                Set<Method> found = dateHelpers(loader, Discovery.invokedBy(owner.getName(), method.getName()));
                if (found.isEmpty()) continue;
                scopes += scope(method);
                helpers.addAll(found);
            }
        }
        for (Method helper : helpers) {
            Hooks.tryHook(helper, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (SCOPE.get()[0] == 0 || !Prefs.on(Prefs.EXACT_TIME) || !(param.args[0] instanceof Long)) return;
                    long millis = (Long) param.args[0];
                    if (millis >= EARLIEST && millis < System.currentTimeMillis() + 86_400_000L) param.setResult(format(millis));
                }
            });
        }
        int feed = installFeed(loader);
        RuntimeLog.log("exact time: scopes=" + scopes + " helpers=" + helpers.size() + " feed=" + feed);
    }

    /** Static (long) or (long, Context) -> String methods a screen calls: TikTok's date helpers. */
    private static Set<Method> dateHelpers(ClassLoader loader, List<String> calls) {
        Set<Method> found = new LinkedHashSet<>();
        for (String call : calls) {
            String[] parts = call.split("#", 2);
            if (parts.length != 2 || parts[1].startsWith("<")) continue;
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type == null || type.getName().startsWith("java.") || type.getName().startsWith("android.")) continue;
            for (Method method : Reflect.methods(type)) {
                Class<?>[] p = method.getParameterTypes();
                if (!method.getName().equals(parts[1]) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != String.class || p.length == 0 || p[0] != long.class) continue;
                if (p.length == 1 || (p.length == 2 && p[1] == Context.class)) found.add(method);
            }
        }
        return found;
    }

    private static int scope(Method method) {
        return Hooks.tryHook(method, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) { SCOPE.get()[0]++; }
            @Override protected void after(Hooks.Call param) { SCOPE.get()[0]--; }
        }) ? 1 : 0;
    }

    /** Feed and story header: the state builder and its "· " + date helper. */
    private static int installFeed(ClassLoader loader) {
        Class<?> vm = Hooks.findClass(FEED_VM, loader);
        Class<?> item = Hooks.findClass(ITEM, loader);
        if (vm == null || item == null) return 0;
        Method prefix = null;
        int prefixes = 0;
        for (Method method : Reflect.methods(vm)) {
            Class<?>[] p = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == String.class && p.length == 1 && p[0] == String.class) {
                prefix = method;
                prefixes++;
            }
        }
        if (prefixes != 1) return 0;
        int hooks = 0;
        for (Method method : Reflect.methods(vm)) {
            Class<?>[] p = method.getParameterTypes();
            if (!method.getName().equals("paramSync2StateAccept") || p.length != 2 || p[1] != item) continue;
            Hooks.tryHook(method, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    Object seconds = Reflect.call(Reflect.call(param.args[1], "getAweme"), "getCreateTime");
                    POST.get()[0] = seconds instanceof Long ? (Long) seconds : 0;
                }
                @Override protected void after(Hooks.Call param) { POST.get()[0] = 0; }
            });
            hooks++;
        }
        if (hooks == 0) return 0;
        Hooks.tryHook(prefix, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                long seconds = POST.get()[0];
                Object shown = param.getResult();
                if (seconds <= 0 || !Prefs.on(Prefs.EXACT_TIME) || !(shown instanceof String) || ((String) shown).isEmpty()) return;
                long millis = seconds * 1000L;
                if (millis < EARLIEST) return;
                // TikTok's prefix stays; only the date after it changes.
                String text = (String) shown;
                String date = (String) param.args[0];
                int at = date == null || date.isEmpty() ? -1 : text.lastIndexOf(date);
                param.setResult((at >= 0 ? text.substring(0, at) : "· ") + format(millis));
            }
        });
        return hooks + 1;
    }

    private static final ThreadLocal<java.text.SimpleDateFormat[]> FORMATS = ThreadLocal.withInitial(() -> new java.text.SimpleDateFormat[4]);
    private static final ThreadLocal<Locale[]> FORMAT_LOCALE = ThreadLocal.withInitial(() -> new Locale[1]);

    /** "21 Oct, 14:05" this year, "21 Oct 2025, 14:05" before; seconds when chosen. In the phone's language and clock style. */
    static String format(long millis) {
        Locale locale = Locale.getDefault();
        java.text.SimpleDateFormat[] cache = FORMATS.get();
        if (!locale.equals(FORMAT_LOCALE.get()[0])) {
            java.util.Arrays.fill(cache, null);
            FORMAT_LOCALE.get()[0] = locale;
        }
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        boolean thisYear = then.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        boolean seconds = Prefs.on(Prefs.EXACT_TIME_SECONDS);
        int slot = (thisYear ? 0 : 1) + (seconds ? 2 : 0);
        if (cache[slot] == null) {
            String skeleton = (thisYear ? "dMMM" : "dMMMy") + (seconds ? "jms" : "jm");
            cache[slot] = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale);
        }
        return cache[slot].format(new java.util.Date(millis));
    }
}
