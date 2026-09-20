package com.crystalgraphics.trace;

import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T5 — the Chrome JSON export, which is what gives this engine a mature viewer before its own exists.
 *
 * <p>These assert on the SHAPE Perfetto reads: a complete slice per zone and per frame, a counter
 * track, process-scoped instants, async pairs for chains, and thread metadata so the tracks are named
 * rather than numbered. What no test here can do is open a browser, so the last check is the user's.</p>
 */
public class CgTraceExportTest {

    private static final CgTraceChannel UI = CgTrace.channel("export.ui");

    @Before
    public void quiet() {
        CgTrace.resetForTesting();
    }

    private String exportOf() throws Exception {
        StringWriter out = new StringWriter();
        CgTraceExport.writeChromeJson(out, CgTrace.snapshot());
        return out.toString();
    }

    /** Drives a small but complete trace: frames, nested zones, a counter, a marker and a chain. */
    private void record() {
        CgTrace.enable("export");
        // ONE CLOCK for the whole trace. Mixing the real one into a synthetic run puts events outside
        // every window that would show them -- which is how the first version of this test lost its
        // marker without anything reporting a problem.
        long clock = 5_000_000_000L;
        long chain = CgTrace.spanBeginAt(UI, "open file", clock);
        for (int i = 0; i < 3; i++) {
            clock += 8_000_000L;
            CgTrace.frameBegin(clock);
            CgTrace.zoneDone(UI, "paint:tree", clock + 1_000L, clock + 4_000_000L);
            CgTrace.counter(UI, "drawcalls", 31 + i);
            CgTrace.frameEnd(clock + 5_000_000L);
        }
        CgTrace.markerAt(UI, "rematched", "Tooltip.reposition:214", clock + 1_000_000L);
        CgTrace.spanEndAt(chain, clock + 2_000_000L);
        clock += 8_000_000L;
        CgTrace.frameBegin(clock);   // commits the third
    }

    @Test
    public void aFrameIsASliceOnATrackOfItsOwn() throws Exception {
        record();
        String json = exportOf();
        assertTrue(json, json.contains("\"name\":\"Frames\""));
        assertTrue(json, json.contains("\"name\":\"Frame 0\""));
        assertTrue(json, json.contains("\"name\":\"Frame 2\""));
        // A FRAME CARRIES ITS CPU FIGURE as an argument, so the viewer can show wall and CPU apart --
        // the distinction a readout that shows only wall time loses right up until it collapses.
        assertTrue(json, json.contains("\"cpu_ms\""));
    }

    @Test
    public void aZoneIsACompleteSliceCategorisedByItsChannel() throws Exception {
        record();
        String json = exportOf();
        assertTrue(json, json.contains("\"ph\":\"X\""));
        assertTrue(json, json.contains("\"name\":\"paint:tree\""));
        // THE CHANNEL IS THE CATEGORY, which is what lets Perfetto filter by owner -- the same split
        // the mask makes at capture time.
        assertTrue(json, json.contains("\"cat\":\"export.ui\""));
        // And the source location rides along, so a slice in the viewer names the line that opened it.
        assertTrue(json, json.contains("\"src\":\""));
    }

    @Test
    public void countersMarkersAndChainsEachGetTheirOwnShape() throws Exception {
        record();
        String json = exportOf();
        assertTrue("no counter track", json.contains("\"ph\":\"C\""));
        assertTrue(json, json.contains("\"name\":\"drawcalls\""));
        // PROCESS-SCOPED, so a marker draws across every track rather than as a tick on one: it is
        // about the frame, not about a thread.
        assertTrue("no instant", json.contains("\"ph\":\"i\",\"s\":\"p\""));
        assertTrue(json, json.contains("Tooltip.reposition:214"));
        // A chain is async because it outlives the frame it began in.
        assertTrue("no async begin", json.contains("\"ph\":\"b\""));
        assertTrue("no async end", json.contains("\"ph\":\"e\""));
        assertTrue(json, json.contains("\"name\":\"Chains\""));
    }

    @Test
    public void theTraceStartsAtZeroRatherThanAtNanoTimesOrigin() throws Exception {
        record();
        String json = exportOf();
        // `System.nanoTime()` has an arbitrary origin, often a large negative one, and a viewer handed
        // those draws a timeline starting decades ago. Everything is relative to the earliest event.
        assertFalse("an absolute nanoTime leaked into the export", json.contains("\"ts\":5000000"));
        assertTrue(json, json.contains("\"ts\":0.000"));
    }

    @Test
    public void anOpenZoneIsLeftOutRatherThanGivenAMadeUpEnd() throws Exception {
        CgTrace.enable("export");
        CgTrace.frameBegin(1_000_000_000L);
        CgTrace.begin(UI, "still running");
        String json = exportOf();
        // A zone with no end has no duration, and inventing one would put a slice in the viewer that
        // never happened.
        assertFalse(json, json.contains("still running"));
    }

    @Test
    public void theWholeThingIsWellFormedJson() throws Exception {
        record();
        String json = exportOf();
        assertTrue(json.startsWith("{\"displayTimeUnit\":\"ms\",\"traceEvents\":["));
        assertTrue(json.endsWith("]}"));
        assertEquals("unbalanced braces", count(json, '{'), count(json, '}'));
        assertEquals("unbalanced brackets", count(json, '['), count(json, ']'));
        assertFalse("a trailing comma would make Perfetto refuse the file", json.contains(",\n]}"));
    }

    private static int count(String text, char of) {
        int found = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            if (at == '\\') {
                i++;
                continue;
            }
            if (at == '"') inString = !inString;
            else if (!inString && at == of) found++;
        }
        return found;
    }
}
