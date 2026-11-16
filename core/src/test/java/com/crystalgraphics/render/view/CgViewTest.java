package com.crystalgraphics.render.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A view's two sizes: what its world draws at and what it presents at. */
public class CgViewTest {

    @Test
    public void unrealsDisplayBasedScreenPercentage() {
        assertEquals(1f, CgView.autoFraction(1280, 720), 1e-6f);
        assertEquals((float) Math.sqrt(0.53125), CgView.autoFraction(1920, 1080), 1e-6f);   // 73%: by pixel count between 720 and 2160 lines
        assertEquals(0.5f, CgView.autoFraction(3840, 2160), 1e-6f);
        assertEquals(1f / 3f, CgView.autoFraction(7680, 4320), 1e-6f);
        assertEquals(1f / 3f, CgView.autoFraction(15360, 8640), 1e-6f);
    }

    @Test
    public void aScaledViewDrawsSmallerAndPresentsWhole() {
        CgView view = CgView.create("test.scaled").screenPercentage(50f);
        view.size(1921, 1081, true);
        assertEquals(961, view.width());
        assertEquals(541, view.height());
        assertEquals(1921, view.outputWidth());
        assertTrue(view.scaled());
        assertEquals(0.5f, view.resolutionFraction(), 0f);
    }

    @Test
    public void aViewOverAHostsOwnWorldDrawsWhole() {
        CgView view = CgView.create("test.host").screenPercentage(50f);
        view.size(1920, 1080, false);
        assertEquals(1920, view.width());
        assertFalse(view.scaled());
        assertEquals(1f, view.resolutionFraction(), 0f);
    }

    @Test
    public void theScreenPercentageIsClamped() {
        assertEquals(CgView.MIN_SCREEN_PERCENTAGE, CgView.create("test.low").screenPercentage(5f).screenPercentage(), 0f);
        assertEquals(100f, CgView.create("test.high").screenPercentage(200f).screenPercentage(), 0f);
    }
}
