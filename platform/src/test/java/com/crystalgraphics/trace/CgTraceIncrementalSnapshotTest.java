package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link CgTrace#frameSnapshot(CgTraceSnapshot)}: carried forward, it must always answer exactly what a fresh copy
 * does -- across new writes, a wrapped store and a clear -- while reusing the views it already made.
 */
public class CgTraceIncrementalSnapshotTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("incremental.test");
    private static final long MS = 1_000_000L;

    private long at = 1_000L;
    private int value;

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.configure(2, 8, 256);
        CgTrace.setEnabled(CHANNEL, true);
    }

    @After
    public void tearDown() {
        CgTrace.configure(0, 600, 256);
        CgTrace.resetForTesting();
    }

    /** {@code frames} frames of {@code counters} counter writes each, the last committed. */
    private void frames(int frames, int counters) {
        for (int f = 0; f < frames; f++) {
            CgTrace.frameBegin(at);
            for (int c = 0; c < counters; c++) CgTrace.add(CHANNEL, "inc:c" + (c % 3), value++);
            at += MS;
        }
        CgTrace.frameBegin(at);
    }

    @Test
    public void carriedForwardItEqualsAFreshCopyAndReusesItsViews() {
        frames(4, 5);
        CgTraceSnapshot first = CgTrace.frameSnapshot(null);
        List<CgTraceSnapshot.CounterView> firstCounters = new ArrayList<>(first.counters());

        frames(3, 5);
        CgTraceSnapshot second = CgTrace.frameSnapshot(first);

        assertEquals(CgTrace.frameSnapshot().counters(), second.counters());
        assertEquals("the earlier snapshot must not see the later writes", firstCounters, first.counters());
        assertSame("a view already made is reused, not copied", first.counters().get(0), second.counters().get(0));
    }

    @Test
    public void aWrappedStoreStillMatches() {
        CgTraceSnapshot snapshot = CgTrace.frameSnapshot(null);
        for (int round = 0; round < 12; round++) {
            frames(3, 900);   // past the ring's counter capacity within a few rounds
            snapshot = CgTrace.frameSnapshot(snapshot);
            CgTraceSnapshot fresh = CgTrace.frameSnapshot();
            assertEquals("round " + round, fresh.counters(), snapshot.counters());
            for (CgFrameRecord frame : fresh.frames()) {
                assertEquals("round " + round + ", frame " + frame.index(),
                        fresh.countersIn(frame), snapshot.countersIn(frame));
            }
        }
    }

    @Test
    public void aClearStartsOver() {
        frames(4, 5);
        CgTraceSnapshot before = CgTrace.frameSnapshot(null);
        CgTrace.clear();
        frames(2, 4);

        CgTraceSnapshot after = CgTrace.frameSnapshot(before);
        assertEquals(CgTrace.frameSnapshot().counters(), after.counters());
    }
}
