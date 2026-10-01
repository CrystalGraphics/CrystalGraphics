package com.crystalgraphics.render.graph;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Recording runs ahead of execution: an older request's answer has to count while the newest is still pending. */
public class CgRequestsTest {

    @Test
    public void anOlderAnswerCountsWhileTheNewestIsPending() {
        CgRequests compiles = new CgRequests();
        CgRequest first = new CgRequest("compile");
        CgRequest second = new CgRequest("compile");
        compiles.add(first);
        compiles.add(second);

        assertFalse(compiles.done());
        first.complete();
        assertTrue("the frame recorded first executed; the newest has not yet", compiles.done());
    }

    @Test
    public void anyFailureIsTheVerdict() {
        CgRequests compiles = new CgRequests();
        CgRequest refused = new CgRequest("compile");
        compiles.add(refused);
        compiles.add(new CgRequest("compile"));
        refused.fail("0(12) : error C1008: undefined variable");

        assertTrue(compiles.failed());
        assertEquals("0(12) : error C1008: undefined variable", compiles.failure());
    }

    @Test
    public void onlyTheNewestAreKept() {
        CgRequests compiles = new CgRequests();
        CgRequest oldest = new CgRequest("compile");
        compiles.add(oldest);
        for (int i = 0; i < CgRequests.KEPT; i++) compiles.add(new CgRequest("compile"));
        oldest.complete();

        assertFalse("a request older than the kept window belongs to a frame long gone", compiles.done());
        compiles.clear();
        assertTrue(compiles.isEmpty());
    }
}
