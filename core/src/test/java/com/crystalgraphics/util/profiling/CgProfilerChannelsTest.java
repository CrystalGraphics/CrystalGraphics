package com.crystalgraphics.util.profiling;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The facade's side of the trace: which channel a name lands on, and what reaches the ring. */
public class CgProfilerChannelsTest {

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
    }

    @After
    public void tearDown() {
        CgProfiler.reset();
        CgTrace.resetForTesting();
    }

    @Test
    public void aNamesFirstSegmentPicksItsChannel() {
        CgProfiler.setEnabled(true);
        CgTrace.frameBegin();
        for (String name : new String[] {"shape.run", "doBind.save", "worker.job", "glFlush", "myThing"}) {
            try (CgProfiler.Scope ignored = CgProfiler.scope(name)) {
                // empty
            }
        }
        CgTrace.frameEnd();

        Map<String, String> channelOf = new HashMap<>();
        for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesIn(onlyFrame())) channelOf.put(zone.name(), zone.channel());
        assertEquals("crystalgraphics.text", channelOf.get("shape.run"));
        assertEquals("crystalgraphics.gl", channelOf.get("doBind.save"));
        assertEquals("crystalgraphics.async", channelOf.get("worker.job"));
        assertEquals("crystalgraphics.gl", channelOf.get("glFlush"));
        assertEquals("crystalgraphics.misc", channelOf.get("myThing"));
    }

    @Test
    public void oneSubsystemRecordsWithoutTheRest() {
        CgTrace.enable("crystalgraphics.text");
        assertTrue(CgProfiler.isEnabled());
        CgTrace.frameBegin();
        try (CgProfiler.Scope ignored = CgProfiler.scope("shape.run")) {
            try (CgProfiler.Scope inner = CgProfiler.scope("doBind.save")) {
                // empty
            }
        }
        CgTrace.frameEnd();

        List<CgTraceSnapshot.ZoneView> zones = CgTrace.zonesIn(onlyFrame());
        assertEquals(1, zones.size());
        assertEquals("shape.run", zones.get(0).name());
    }

    @Test
    public void aForeignZoneBetweenTwoScopesIsNotInThePath() {
        CgProfiler.setEnabled(true);
        CgTrace.enable("test.host");
        try (CgProfiler.Scope ignored = CgProfiler.scope("resolve")) {
            try (CgTrace.Zone host = CgTrace.zone(CgTrace.channel("test.host"), "hostWork")) {
                try (CgProfiler.Scope inner = CgProfiler.scope("flatten")) {
                    // empty
                }
            }
        }

        List<CgProfilerReport.ScopeEntry> scopes = CgProfiler.report().scopes();
        assertEquals(2, scopes.size());
        assertEquals("resolve", scopes.get(0).path());
        assertEquals("resolve/flatten", scopes.get(1).path());
    }

    @Test
    public void aFramesCountIsWrittenOnceTheFrameHasMovedOn() {
        CgProfiler.setEnabled(true);
        CgTrace.frameBegin();
        CgProfiler.count("glyph.atlasHit");
        CgProfiler.count("glyph.atlasHit", 2);
        CgProfiler.sample("async.pendingGlyphs", 2.6);
        CgTrace.frameEnd();
        CgTrace.frameBegin();
        CgProfiler.count("glyph.atlasHit");
        CgTrace.frameEnd();

        Map<String, Long> first = new HashMap<>();
        for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(CgTrace.frames().get(0))) {
            first.put(counter.name(), counter.value());
        }
        assertEquals(Long.valueOf(3), first.get("glyph.atlasHit"));
        assertEquals(Long.valueOf(3), first.get("async.pendingGlyphs"));
        assertEquals(Long.valueOf(4), CgProfiler.report().counters().get("glyph.atlasHit"));
    }

    @Test
    public void aScopeClosesItsZoneEvenIfTheChannelWentOffInside() {
        CgProfiler.setEnabled(true);
        try (CgProfiler.Scope ignored = CgProfiler.scope("shape.run")) {
            CgProfiler.setEnabled(false);
        }
        assertFalse(CgProfiler.isEnabled());
        CgProfiler.setEnabled(true);
        try (CgProfiler.Scope ignored = CgProfiler.scope("shape.next")) {
            // empty
        }
        // Had the first zone stayed open, the second would sit one level under it.
        CgTraceSnapshot.ZoneView next = null;
        for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesOfThread(Thread.currentThread(), Long.MIN_VALUE)) {
            if (zone.name().equals("shape.next")) next = zone;
        }
        assertNotNull(next);
        assertEquals(0, next.depth());
    }

    /** A frame is committed by the next frameBegin, not by frameEnd. */
    private static CgFrameRecord onlyFrame() {
        CgTrace.frameBegin();
        List<CgFrameRecord> frames = CgTrace.frames();
        assertEquals(1, frames.size());
        return frames.get(0);
    }
}
