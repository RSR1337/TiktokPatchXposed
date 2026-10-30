package com.golda.patchertiktok;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class SeenIdsTest {
    @Test
    public void keepsTheNewestIdsAndForgetsTheOldest() {
        SeenIds ids = new SeenIds(3);
        assertTrue(ids.add(10));
        assertTrue(ids.add(20));
        assertTrue(ids.add(30));
        assertFalse(ids.add(20));
        assertTrue(ids.add(40));
        assertFalse(ids.contains(10));
        assertTrue(ids.contains(20));
        assertTrue(ids.contains(40));
        assertArrayEquals(new long[]{20, 30, 40}, ids.toArray());
        assertEquals(3, ids.size());
    }

    @Test
    public void ignoresZero() {
        SeenIds ids = new SeenIds(4);
        assertFalse(ids.add(0));
        assertFalse(ids.contains(0));
        assertEquals(0, ids.size());
    }

    @Test
    public void matchesASimpleModelOverManyEvictions() {
        int capacity = 1_000;
        SeenIds ids = new SeenIds(capacity);
        ArrayDeque<Long> order = new ArrayDeque<>();
        Set<Long> model = new HashSet<>();
        Random random = new Random(7);
        for (int step = 0; step < 50_000; step++) {
            // Real ids are large; a narrow range forces repeats and long probe runs.
            long id = 7_400_000_000_000_000_000L + random.nextInt(5_000);
            boolean added = ids.add(id);
            assertEquals(!model.contains(id), added);
            if (added) {
                if (order.size() == capacity) model.remove(order.removeFirst());
                order.addLast(id);
                model.add(id);
            }
            if (step % 97 == 0) {
                for (int probe = 0; probe < 50; probe++) {
                    long other = 7_400_000_000_000_000_000L + random.nextInt(5_000);
                    assertEquals(model.contains(other), ids.contains(other));
                }
            }
        }
        assertEquals(capacity, ids.size());
    }
}
