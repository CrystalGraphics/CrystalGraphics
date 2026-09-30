package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A count that fires many times a frame, written once as the frame's value. */
public class CgTraceAddTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("test.add");

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.enable(CHANNEL.name());
    }

    @After
    public void tearDown() {
        CgTrace.resetForTesting();
    }

    @Test
    public void addsInOneFrameAreOneValueWrittenAtTheNextBoundary() {
        CgTrace.frameBegin();
        CgTrace.add(CHANNEL, "draws", 1);
        CgTrace.add(CHANNEL, "draws", 1);
        CgTrace.add(CHANNEL, "draws", 3);
        CgTrace.frameBegin();
        CgTrace.add(CHANNEL, "draws", 2);
        CgTrace.frameBegin();

        assertEquals(Long.valueOf(5), counters(0).get("draws"));
        assertEquals(Long.valueOf(2), counters(1).get("draws"));
    }

    @Test
    public void aDisabledChannelAddsNothingAndStampsZero() {
        CgTrace.frameBegin();
        CgTrace.disable(CHANNEL.name());
        CgTrace.add(CHANNEL, "draws", 1);
        assertEquals(0L, CgTrace.stamp(CHANNEL));
        CgTrace.enable(CHANNEL.name());
        assertTrue(CgTrace.stamp(CHANNEL) != 0L);
        CgTrace.frameBegin();
        assertNull(counters(0).get("draws"));
    }

    @Test
    public void aClearDropsATotalNotYetWritten() {
        CgTrace.frameBegin();
        CgTrace.add(CHANNEL, "draws", 7);
        CgTrace.clear();
        CgTrace.frameBegin();
        CgTrace.add(CHANNEL, "draws", 1);
        CgTrace.frameBegin();
        assertEquals(Long.valueOf(1), counters(0).get("draws"));
    }

    /** The launch property's list: each entry a prefix, blanks ignored, later channels taken too. */
    @Test
    public void aListOfPrefixesEnablesEachAndChannelsThatRegisterLater() {
        CgTrace.resetForTesting();
        CgTrace.enableAll(" test.add , ,test.later");
        CgTraceChannel later = CgTrace.channel("test.later.sub");
        assertTrue(CgTrace.isEnabled(CHANNEL));
        assertTrue("a channel registered after the list did not take it", CgTrace.isEnabled(later));
        CgTrace.enableAll(null);
    }

    private static Map<String, Long> counters(long index) {
        Map<String, Long> out = new HashMap<>();
        for (CgFrameRecord frame : CgTrace.frames()) {
            if (frame.index() != index) continue;
            for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(frame)) {
                out.merge(counter.name(), counter.value(), Long::sum);
            }
        }
        return out;
    }
}
