package com.crystalgraphics.api.canvas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CgCullRectTest {

    @Test
    public void aVerticalListExpandsDownAndUpOnlyWithinItsContents() {
        float[] cull = new float[4];
        // A 400 x 600 viewport scrolled 1000 into a 400 x 20000 list
        CgCullRect.forScroller(0, 1000, 400, 600, 400, 20000, 1f, cull);
        assertArrayEquals(new float[] {0, 0, 400, 5600}, cull, 1e-3f);   // 4000 each way, clamped at the top
    }

    @Test
    public void scrollingBothWaysHalvesTheMargin() {
        float[] cull = new float[4];
        CgCullRect.forScroller(5000, 5000, 1000, 800, 20000, 20000, 1f, cull);
        assertArrayEquals(new float[] {3000, 3000, 8000, 7800}, cull, 1e-3f);
    }

    @Test
    public void aSmallScrollerExpandsByTheMinimum() {
        float[] cull = new float[4];
        // 200 x 200 is under 100000 device pixels: twice the changed-enough distance, 1024
        CgCullRect.forScroller(0, 3000, 200, 200, 200, 10000, 1f, cull);
        assertArrayEquals(new float[] {0, 1976, 200, 4224}, cull, 1e-3f);
    }

    @Test
    public void aScrollWithinTheMarginRecordsNothingAgain() {
        float[] old = new float[4], now = new float[4];
        CgCullRect.forScroller(0, 1000, 400, 600, 400, 20000, 1f, old);
        CgCullRect.forScroller(0, 1300, 400, 600, 400, 20000, 1f, now);
        assertFalse(CgCullRect.changedEnough(old, now, 400, 20000, 1f));
        CgCullRect.forScroller(0, 9000, 400, 600, 400, 20000, 1f, now);
        assertTrue(CgCullRect.changedEnough(old, now, 400, 20000, 1f));
    }

    @Test
    public void reachingTheContentsEdgeRecordsAgain() {
        float[] old = {0, 1000, 400, 9000}, now = {0, 1100, 400, 9100};
        // Within the 512 threshold, but the new rect touches the contents' bottom the old did not
        assertTrue(CgCullRect.changedEnough(old, now, 400, 9100, 1f));
    }
}
