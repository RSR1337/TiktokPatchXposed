package com.golda.patchertiktok;

/**
 * A fixed set of the newest {@code capacity} video ids, kept as plain longs: a ring holds them in
 * the order they came, an open-addressing table answers "seen?". 10 000 ids take about 200 KB,
 * instead of the 1.5 MB a map of strings would. Not thread-safe; the caller locks.
 */
final class SeenIds {
    private final long[] ring;
    private final long[] table;
    private final int mask;
    private int head;
    private int size;

    SeenIds(int capacity) {
        ring = new long[capacity];
        int slots = Integer.highestOneBit(capacity * 3 / 2 - 1) << 1;
        table = new long[slots];
        mask = slots - 1;
    }

    int size() { return size; }

    boolean contains(long id) {
        if (id == 0) return false;
        for (int slot = slot(id); ; slot = (slot + 1) & mask) {
            long value = table[slot];
            if (value == 0) return false;
            if (value == id) return true;
        }
    }

    /** Adds the id; when full, the oldest one goes. False when it was already there. */
    boolean add(long id) {
        if (id == 0 || contains(id)) return false;
        if (size == ring.length) {
            // Full: the oldest id gives its place to the new one.
            remove(ring[head]);
            ring[head] = id;
            head = (head + 1) % ring.length;
        } else {
            ring[(head + size) % ring.length] = id;
            size++;
        }
        insert(id);
        return true;
    }

    /** The ids from oldest to newest. */
    long[] toArray() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) out[i] = ring[(head + i) % ring.length];
        return out;
    }

    void clear() {
        java.util.Arrays.fill(table, 0);
        head = 0;
        size = 0;
    }

    private void insert(long id) {
        int slot = slot(id);
        while (table[slot] != 0) slot = (slot + 1) & mask;
        table[slot] = id;
    }

    /** Linear-probing delete: later entries of the same run move back so lookups still find them. */
    private void remove(long id) {
        int slot = slot(id);
        while (table[slot] != id) {
            if (table[slot] == 0) return;
            slot = (slot + 1) & mask;
        }
        int gap = slot;
        for (int next = (gap + 1) & mask; table[next] != 0; next = (next + 1) & mask) {
            int home = slot(table[next]);
            // Move the entry back unless its home lies cyclically between the gap and it.
            boolean between = gap <= next ? (home > gap && home <= next) : (home > gap || home <= next);
            if (!between) {
                table[gap] = table[next];
                gap = next;
            }
        }
        table[gap] = 0;
    }

    private int slot(long id) {
        long mixed = id * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ (mixed >>> 32)) & mask;
    }
}
