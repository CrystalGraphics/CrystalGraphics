package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgRenderQueue;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CgSortKeyTest {

    @Test
    public void opaqueIsFrontToBackAndTransparentBackToFront() {
        long near = CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 0, 7, 3, 2f);
        long far = CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 0, 7, 3, 500f);
        assertTrue(near < far);
        assertTrue(alone(0, 500f) < alone(0, 2f));
    }

    @Test
    public void slotsAndLayersOutrankDistance() {
        assertTrue(CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 0, 1, 1, 50_000f)
                < CgSortKey.opaque(CgRenderQueue.ALPHA_TEST, 0, 0, 1, 1, 0f));
        assertTrue(CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 0, 1, 1, 50_000f)
                < CgSortKey.opaque(CgRenderQueue.GEOMETRY, 1, 0, 1, 1, 0f));
        assertTrue(alone(0, 0f) < alone(1, 50_000f));
    }

    /** A group draws whole, back to front among others in its layer; within it its own order, then distance. */
    @Test
    public void aGroupSortsAsOne() {
        long farHaze = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 40f, false, 4, 0, 39f);
        long farCore = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 40f, false, 7, 0, 60f);
        long nearHaze = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 10f, false, 4, 0, 10f);
        assertTrue("within a group, order over distance", farHaze < farCore);
        assertTrue("a farther group draws whole before a nearer one", farCore < nearHaze);
        assertTrue("a draw alone sorts among groups by its distance", farCore < alone(1, 20f) && alone(1, 20f) < nearHaze);
        assertTrue("a later layer outranks any distance", nearHaze < alone(2, 50_000f));
    }

    /** A draw writing depth goes first in its group whatever its order, and never leaves the group for it. */
    @Test
    public void depthWritersDrawFirstInTheirGroup() {
        long smoke = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 40f, false, 1, 0, 30f);
        long ink = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 40f, true, 3, 0, 50f);
        long fartherGroup = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 60f, false, 7, 0, 60f);
        long nearerGroup = CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 10f, true, 0, 0, 10f);
        assertTrue("before a lower order of its group", ink < smoke);
        assertTrue("after a farther group", fartherGroup < ink);
        assertTrue("before a nearer group", smoke < nearerGroup);
    }

    private static long alone(int layer, float distance) {
        return CgSortKey.transparent(CgRenderQueue.TRANSPARENT, layer, distance, false, 0, 0, distance);
    }

    @Test
    public void bucketsStayDistinctNearTheEyeAndInRangeFarAway() {
        assertTrue(CgSortKey.bucket(1f) < CgSortKey.bucket(1.1f));
        assertTrue(CgSortKey.bucket(59_000f) <= 0xFFFF);
        assertTrue(CgSortKey.bucket(30_000f) < CgSortKey.bucket(59_000f));
    }
}
