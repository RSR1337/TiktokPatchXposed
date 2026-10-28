package com.golda.patchertiktok;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Videos already watched, so the feed can leave them out when TikTok sends them again (it does
 * when its own watch history is incomplete, for example with its analytics off). A video counts as
 * watched once a tenth of it has played, at least one second and at most five; TikTok's player
 * reports the progress through PlayerController.onPlayProgressChange(id, position, duration).
 *
 * The list stays on the phone, one per TikTok account, newest 10 000 videos. Ids are numbers, so
 * they are kept as longs ({@link SeenIds}: about 200 KB in memory, 8 bytes each on disk). The
 * file is read once in the background; the feed only looks ids up in memory, and new ones are
 * appended to the file behind it.
 */
final class SeenVideos {
    private static final String PLAYER = "com.ss.android.ugc.aweme.feed.controller.PlayerController";
    private static final String USER_SERVICE = "com.ss.android.ugc.aweme.framework.services.IUserService";
    private static final int LIMIT = 10_000;
    private static final long UNKNOWN_LENGTH_MS = 2_000L;
    private static final long MIN_MS = 1_000L;
    private static final long MAX_MS = 5_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(Safe.run("seen videos", task), "tpx-seen");
        thread.setDaemon(true);
        return thread;
    });
    private static final SeenIds SEEN = new SeenIds(LIMIT);

    private static volatile File folder;
    private static volatile ClassLoader classLoader;
    private static volatile Object users;
    private static volatile String account;
    private static volatile boolean loaded;
    /** The video the player reports now, and whether it is already marked. */
    private static volatile long playing;
    private static volatile boolean marked;
    private static int appended;

    private SeenVideos() { }

    static void install(Context context, ClassLoader loader) {
        folder = context.getFilesDir();
        classLoader = loader;
        Class<?> player = Hooks.findClass(PLAYER, loader);
        int hooks = 0;
        if (player != null) {
            for (java.lang.reflect.Method method : player.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (!method.getName().equals("onPlayProgressChange") || p.length != 3 || p[0] != String.class
                        || p[1] != long.class || p[2] != long.class) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_SEEN)) progress((String) param.args[0], (Long) param.args[1], (Long) param.args[2]);
                    }
                });
                hooks++;
            }
        }
        if (Prefs.on(Prefs.HIDE_SEEN)) IO.execute(Safe.run("seen videos", SeenVideos::load));
        RuntimeLog.log(hooks == 0 ? "seen videos unavailable: player progress not found" : "seen videos: hooks=" + hooks);
    }

    /** Whether the feed should leave this video out. */
    static boolean seen(Object aweme) {
        if (!loaded) {
            if (folder != null) IO.execute(Safe.run("seen videos", SeenVideos::load));
            return false;
        }
        long id = id(Reflect.call(aweme, "getAid"));
        if (id == 0 || id == playing) return false;
        synchronized (SEEN) {
            return SEEN.contains(id);
        }
    }

    private static void progress(String aid, long position, long duration) {
        long id = id(aid);
        if (id == 0) return;
        if (id != playing) {
            playing = id;
            marked = false;
        }
        if (marked || !watched(position, duration)) return;
        marked = true;
        IO.execute(Safe.run("seen videos", () -> {
            load();
            synchronized (SEEN) {
                if (!SEEN.add(id)) return;
            }
            append(id);
        }));
    }

    /** A tenth of the video, held between one and five seconds; two seconds when its length is unknown. */
    static boolean watched(long position, long duration) {
        if (duration <= 0) return position >= UNKNOWN_LENGTH_MS;
        long floor = duration < MIN_MS ? duration / 2 : MIN_MS;
        return position >= Math.max(floor, Math.min(MAX_MS, duration / 10));
    }

    private static long id(Object aid) {
        if (!(aid instanceof String)) return 0;
        try {
            return Long.parseLong((String) aid);
        } catch (NumberFormatException notNumeric) {
            return 0;
        }
    }

    /** Reads the signed-in account's list, again whenever the account changes. Runs on {@link #IO}. */
    private static void load() {
        String current = currentAccount();
        if (loaded && current.equals(account)) return;
        synchronized (SEEN) {
            SEEN.clear();
            File file = file(current);
            if (file != null && file.isFile()) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                    while (true) SEEN.add(in.readLong());
                } catch (EOFException end) {
                    // The whole file is read; a cut-off last id is simply dropped.
                } catch (Exception error) {
                    RuntimeLog.log("seen videos: list not read, " + error.getClass().getSimpleName());
                }
            }
        }
        account = current;
        appended = 0;
        loaded = true;
    }

    private static void append(long id) {
        File file = file(account);
        if (file == null) return;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file, true))) {
            out.writeLong(id);
        } catch (Exception error) {
            return;
        }
        // The file only grows; now and then it is rewritten with the ids memory still keeps.
        if (++appended > LIMIT / 4) compact(file);
    }

    private static void compact(File file) {
        long[] ids;
        synchronized (SEEN) {
            ids = SEEN.toArray();
        }
        File temp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp, false)))) {
            for (long id : ids) out.writeLong(id);
        } catch (Exception error) {
            return;
        }
        if (temp.renameTo(file)) appended = 0;
    }

    /** Forgets every watched video of the signed-in account. */
    static void clear() {
        IO.execute(Safe.run("seen videos", () -> {
            load();
            synchronized (SEEN) {
                SEEN.clear();
            }
            File file = file(account);
            if (file != null) file.delete();
            appended = 0;
        }));
    }

    static int size() {
        synchronized (SEEN) {
            return SEEN.size();
        }
    }

    private static File file(String owner) {
        File dir = folder;
        return dir == null || owner == null ? null : new File(dir, "tpx_seen_" + owner + ".bin");
    }

    /** The signed-in account's id, or "guest"; ids are digits, so the two never collide. */
    private static String currentAccount() {
        try {
            if (users == null) users = StreakApi.service(classLoader, USER_SERVICE);
            Object id = Reflect.call(users, "getCurrentUserID");
            if (id instanceof String && ((String) id).matches("\\d+") && !"0".equals(id)) return (String) id;
        } catch (Throwable ignored) {
            // Not signed in, or the service is not ready yet.
        }
        return "guest";
    }
}
