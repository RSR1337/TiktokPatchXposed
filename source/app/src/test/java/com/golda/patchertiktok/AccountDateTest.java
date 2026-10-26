package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AccountDateTest {
    private static final long NOW = 1_790_000_000L;

    @Test
    public void readsTimeFromId() {
        assertEquals(1579654208L, AccountDate.createdSeconds("6784563164518122502", NOW));
    }

    @Test
    public void skipsIdsWithoutTime() {
        assertEquals(0, AccountDate.createdSeconds("107955", NOW));
        assertEquals(0, AccountDate.createdSeconds("", NOW));
        assertEquals(0, AccountDate.createdSeconds(null, NOW));
        assertEquals(0, AccountDate.createdSeconds("user", NOW));
    }

    @Test
    public void skipsFutureTimes() {
        assertEquals(0, AccountDate.createdSeconds("6784563164518122502", 1_500_000_000L));
    }
}
