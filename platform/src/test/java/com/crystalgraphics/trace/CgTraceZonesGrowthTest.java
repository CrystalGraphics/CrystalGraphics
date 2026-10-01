package com.crystalgraphics.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * An arena growing past a page copies nothing: the bigger store takes the old one's pages, and a reader still
 * holding the old store reads what was written into it. Copying was a 40-170 ms frame charged to whatever zone
 * filled the arena.
 */
public class CgTraceZonesGrowthTest {

    private static final int PAGE = 1 << CgTraceZones.Store.PAGE_BITS;

    @Test
    public void growingPastAPageSharesThePagesAndTheOldStoreStillReads() {
        CgTraceZones arena = new CgTraceZones("growth", 1, PAGE, PAGE * 4, false);
        for (int i = 0; i < PAGE; i++) arena.record(0, 0, i, i + 1L);
        CgTraceZones.Store held = arena.store;

        arena.record(0, 0, PAGE, PAGE + 1L);   // full: this one grows the arena
        CgTraceZones.Store grown = arena.store;

        assertNotSame(held, grown);
        assertEquals(PAGE * 2, grown.capacity);
        assertTrue("the grown store took the old pages rather than copying them", held.sharesPagesWith(grown));
        assertEquals(PAGE, arena.highFor(held));
        for (long slot : new long[] {0, 1, PAGE / 2, PAGE - 1}) {
            assertEquals(slot, held.start(slot));
            assertEquals(slot + 1, held.end(slot));
            assertEquals(slot, grown.start(slot));
        }
        assertEquals(PAGE, grown.start(PAGE));
    }

    @Test
    public void aStoreSmallerThanAPageCopiesWhenItGrows() {
        CgTraceZones arena = new CgTraceZones("small", 2, 16, 64, false);
        for (int i = 0; i < 17; i++) arena.record(0, 0, i, i + 1L);
        assertEquals(32, arena.store.capacity);
        for (long slot = 0; slot < 17; slot++) assertEquals(slot, arena.store.start(slot));
    }

    @Test
    public void aRetiredStoresSlotsCountAsOverwrittenOnceTheCurrentStoreWrapsOverThem() {
        CgTraceZones arena = new CgTraceZones("wrap", 3, PAGE, PAGE * 2, false);
        for (int i = 0; i < PAGE; i++) arena.record(0, 0, i, i + 1L);
        CgTraceZones.Store held = arena.store;
        assertEquals("only the slot a lap behind the next write is unsafe", 1L, arena.firstUnoverwritten(held));

        // Grow, fill the grown store and lap ten slots into the pages it shares with the held one.
        for (int i = PAGE; i < PAGE * 2 + 10; i++) arena.record(0, 0, i, i + 1L);
        assertSame(arena.store.start[0], held.start[0]);
        assertEquals("the held store's first eleven slots now hold the current store's lap",
                11L, arena.firstUnoverwritten(held));
    }
}
