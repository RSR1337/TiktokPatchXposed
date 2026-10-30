package com.golda.patchertiktok;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached reflective access; feed filtering touches every item, so lookups must not repeat. */
final class Reflect {
    private static final Object MISSING = new Object();
    private static final Map<String, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> FIELDS = new ConcurrentHashMap<>();

    private Reflect() { }

    static Method method(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        Object cached = METHODS.get(key);
        if (cached == null) {
            cached = MISSING;
            for (Class<?> current = type; current != null && cached == MISSING; current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod(name);
                    method.setAccessible(true);
                    cached = method;
                } catch (Throwable ignored) { }
            }
            METHODS.put(key, cached);
        }
        return cached == MISSING ? null : (Method) cached;
    }

    /**
     * The class's declared methods whose signatures load. On some builds a method names a class
     * that fails to resolve, and its getParameterTypes() throws NoClassDefFoundError (#12); one
     * such method must not hide the others.
     */
    static Method[] methods(Class<?> type) {
        try {
            return usable(type, type.getDeclaredMethods());
        } catch (Throwable error) {
            skipped(type, -1, error);
            return new Method[0];
        }
    }

    /** Public methods, own and inherited, whose signatures load; see {@link #methods}. */
    static Method[] publicMethods(Class<?> type) {
        try {
            return usable(type, type.getMethods());
        } catch (Throwable error) {
            skipped(type, -1, error);
            return new Method[0];
        }
    }

    /** Declared fields whose types load; see {@link #methods}. */
    static Field[] fields(Class<?> type) {
        Field[] all;
        try {
            all = type.getDeclaredFields();
        } catch (Throwable error) {
            skipped(type, -1, error);
            return new Field[0];
        }
        java.util.ArrayList<Field> usable = new java.util.ArrayList<>(all.length);
        Throwable first = null;
        for (Field field : all) {
            try {
                field.getType();
                usable.add(field);
            } catch (Throwable error) {
                if (first == null) first = error;
            }
        }
        if (first != null) skipped(type, all.length - usable.size(), first);
        return first == null ? all : usable.toArray(new Field[0]);
    }

    /** Declared constructors whose parameters load; see {@link #methods}. */
    static Constructor<?>[] constructors(Class<?> type) {
        Constructor<?>[] all;
        try {
            all = type.getDeclaredConstructors();
        } catch (Throwable error) {
            skipped(type, -1, error);
            return new Constructor<?>[0];
        }
        java.util.ArrayList<Constructor<?>> usable = new java.util.ArrayList<>(all.length);
        Throwable first = null;
        for (Constructor<?> constructor : all) {
            try {
                constructor.getParameterTypes();
                usable.add(constructor);
            } catch (Throwable error) {
                if (first == null) first = error;
            }
        }
        if (first != null) skipped(type, all.length - usable.size(), first);
        return first == null ? all : usable.toArray(new Constructor<?>[0]);
    }

    private static Method[] usable(Class<?> type, Method[] all) {
        java.util.ArrayList<Method> usable = new java.util.ArrayList<>(all.length);
        Throwable first = null;
        for (Method method : all) {
            try {
                method.getParameterTypes();
                method.getReturnType();
                usable.add(method);
            } catch (Throwable error) {
                if (first == null) first = error;
            }
        }
        if (first != null) skipped(type, all.length - usable.size(), first);
        return first == null ? all : usable.toArray(new Method[0]);
    }

    /** Classes already noted in the log; a class is scanned many times, the line is written once. */
    private static final java.util.Set<String> NOTED = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Notes members skipped on this device (count -1: the whole class), so bug reports show it. */
    private static void skipped(Class<?> type, int count, Throwable error) {
        String name = type == null ? "?" : type.getName();
        if (!NOTED.add(name) || NOTED.size() > 50) return;
        try {
            RuntimeLog.log("reflect: skipped " + (count < 0 ? "all members" : count + " member(s)") + " of " + name
                    + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } catch (Throwable ignored) { }
    }

    static Field field(Class<?> type, String name) {
        String key = type.getName() + '.' + name;
        Object cached = FIELDS.get(key);
        if (cached == null) {
            cached = MISSING;
            for (Class<?> current = type; current != null && cached == MISSING; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    cached = field;
                } catch (Throwable ignored) { }
            }
            FIELDS.put(key, cached);
        }
        return cached == MISSING ? null : (Field) cached;
    }

    static Object call(Object target, String name) {
        if (target == null) return null;
        Method method = method(target.getClass(), name);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean callBoolean(Object target, String name) {
        return Boolean.TRUE.equals(call(target, name));
    }

    static Object get(Object target, String name) {
        if (target == null) return null;
        Field field = field(target.getClass(), name);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean getBoolean(Object target, String name) {
        return Boolean.TRUE.equals(get(target, name));
    }
}
