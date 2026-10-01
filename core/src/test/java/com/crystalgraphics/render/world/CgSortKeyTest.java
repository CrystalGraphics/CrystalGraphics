package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgRenderQueue;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CgSortKeyTest {

    @Test
    public void opaqueIsFrontToBackAndTransparentBackToFront() {
        long near = CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 7, 3, 2f);
        long far = CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 7, 3, 500f);
        assertTrue(near < far);
        assertTrue(CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 0, 500f)
                < CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 0, 2f));
    }

    @Test
    public void slotsAndPriorityOutrankDistance() {
        assertTrue(CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 1, 1, 50_000f)
                < CgSortKey.opaque(CgRenderQueue.ALPHA_TEST, 0, 1, 1, 0f));
        assertTrue(CgSortKey.opaque(CgRenderQueue.GEOMETRY, 0, 1, 1, 50_000f)
                < CgSortKey.opaque(CgRenderQueue.GEOMETRY, 1, 1, 1, 0f));
        assertTrue(CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 0, 0f)
                < CgSortKey.transparent(CgRenderQueue.TRANSPARENT, 1, 50_000f));
    }

    @Test
    public void bucketsStayDistinctNearTheEyeAndInRangeFarAway() {
        assertTrue(CgSortKey.bucket(1f) < CgSortKey.bucket(1.1f));
        assertTrue(CgSortKey.bucket(59_000f) <= 0xFFFF);
        assertTrue(CgSortKey.bucket(30_000f) < CgSortKey.bucket(59_000f));
    }
}
