package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The call tree nests by time containment, not by the depth a zone happened to be recorded at.
 *
 * <p>Zones handed their own times through {@code zoneDone} are all stored at the recording thread's
 * live depth -- zero, with nothing open -- so a tree that trusted the number had no children at all.
 * These pin that a zone inside another is its child whatever depth was stored, that siblings stay
 * siblings, and that overlap is not mistaken for a call.</p>
 */
public class CgTraceAggregateTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("aggregate.test");

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.setEnabled(CHANNEL, true);
    }

    @After
    public void tearDown() {
        CgTrace.resetForTesting();
    }

    private static List<CgTraceSnapshot.ZoneView> recorded(long frameStart, long frameEnd, Runnable zones) {
        CgTrace.frameBegin(frameStart);
        zones.run();
        CgTrace.frameEnd(frameEnd);
        CgTrace.frameBegin(frameEnd);
        CgTraceSnapshot snapshot = CgTrace.snapshot();
        return snapshot.zonesIn(snapshot.frames().get(0));
    }

    @Test
    public void aZoneWhollyInsideAnotherIsItsChildEvenWhenRecordedFlat() {
        List<CgTraceSnapshot.ZoneView> zones = recorded(1_000L, 20_000L, () -> {
            CgTrace.zoneDone(CHANNEL, "agg:parent", 2_000L, 10_000L);
            CgTrace.zoneDone(CHANNEL, "agg:child", 3_000L, 5_000L);
        });

        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zones);
        assertEquals("a contained zone came back as a second root", 1, roots.size());
        assertEquals("agg:parent", roots.get(0).name());
        assertEquals(1, roots.get(0).children().size());
        assertEquals("agg:child", roots.get(0).children().get(0).name());
        assertEquals("the child's depth was not derived from its nesting", 1, roots.get(0).children().get(0).depth());
        assertEquals("self time did not exclude the child", 6_000L, roots.get(0).selfNanos());
    }

    @Test
    public void zonesOneAfterAnotherStaySiblings() {
        List<CgTraceSnapshot.ZoneView> zones = recorded(1_000L, 20_000L, () -> {
            CgTrace.zoneDone(CHANNEL, "agg:first", 2_000L, 4_000L);
            CgTrace.zoneDone(CHANNEL, "agg:second", 4_000L, 6_000L);
        });

        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zones);
        assertEquals("two zones that merely touch were nested", 2, roots.size());
    }

    /** Partial overlap is two things running, not a call — it must not be drawn as one inside the other. */
    @Test
    public void anOverlapIsNotACall() {
        List<CgTraceSnapshot.ZoneView> zones = recorded(1_000L, 20_000L, () -> {
            CgTrace.zoneDone(CHANNEL, "agg:early", 2_000L, 6_000L);
            CgTrace.zoneDone(CHANNEL, "agg:late", 5_000L, 9_000L);
        });

        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zones);
        assertEquals("a zone overlapping another's end was made its child", 2, roots.size());
    }

    /** A parent and its first child can start on the same tick; the longer one must be the parent. */
    @Test
    public void aParentStartingOnItsChildsTickIsStillItsParent() {
        List<CgTraceSnapshot.ZoneView> zones = recorded(1_000L, 20_000L, () -> {
            CgTrace.zoneDone(CHANNEL, "agg:child", 2_000L, 3_000L);
            CgTrace.zoneDone(CHANNEL, "agg:parent", 2_000L, 8_000L);
        });

        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zones);
        assertEquals(1, roots.size());
        assertEquals("the shorter zone became the parent", "agg:parent", roots.get(0).name());
    }
}
