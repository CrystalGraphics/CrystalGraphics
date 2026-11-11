package com.crystalgraphics.text.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** A draw's raster size: exact while its scale holds, on a ladder while it moves, exact again once settled. */
public class CgTextRasterMotionTest {

    /** A label at a steady scale keeps its exact size, however many frames: crisp UI text. */
    @Test
    public void aSteadyDrawIsExact() {
        CgTextRasterMotion m = new CgTextRasterMotion();
        for (int f = 0; f < 100; f++) assertEquals(37, m.resolve(37, f));
        assertFalse(m.moving(100));
    }

    /** A single change (a resize, a window drag step) is not an animation. */
    @Test
    public void oneChangeStaysExact() {
        CgTextRasterMotion m = new CgTextRasterMotion();
        m.resolve(37, 0);
        assertEquals(38, m.resolve(38, 1));
        assertEquals(38, m.resolve(38, 2));
    }

    /** A scale animation walking 34..66 a pixel a frame lands on a handful of sizes, not 33. */
    @Test
    public void aMovingDrawUsesTheLadder() {
        CgTextRasterMotion m = new CgTextRasterMotion();
        Set<Integer> sizes = new HashSet<>();
        int frame = 0;
        for (int px = 34; px <= 66; px++) sizes.add(m.resolve(px, frame++));
        assertTrue("the first changes are exact, then the ladder: " + sizes, sizes.size() <= 8);
        assertTrue(m.moving(frame));
    }

    /** Once the size holds for the settle time the draw is exact again, at the size it stopped on. */
    @Test
    public void aSettledDrawReturnsToExact() {
        CgTextRasterMotion m = new CgTextRasterMotion();
        int frame = 0;
        for (int px = 34; px <= 50; px++) m.resolve(px, frame++);
        int last = frame - 1;
        assertEquals(CgTextRasterMotion.ladder(50), m.resolve(50, last + CgTextRasterMotion.SETTLE - 1));
        assertEquals(50, m.resolve(50, last + CgTextRasterMotion.SETTLE));
        assertFalse(m.moving(last + CgTextRasterMotion.SETTLE));
    }

    /** Two changes far apart are two resizes, not an animation. */
    @Test
    public void changesApartAreNotMotion() {
        CgTextRasterMotion m = new CgTextRasterMotion();
        m.resolve(40, 0);
        m.resolve(41, 100);
        assertEquals(42, m.resolve(42, 200));
    }

    @Test
    public void theLadderIsCoarseMonotoneAndKeepsSmallSizes() {
        assertEquals(12, CgTextRasterMotion.ladder(12));
        int previous = 0;
        Set<Integer> rungs = new HashSet<>();
        for (int px = 17; px <= 256; px++) {
            int rung = CgTextRasterMotion.ladder(px);
            assertTrue(rung >= previous);
            assertTrue("within a step of " + px + ": " + rung, Math.abs(rung - px) <= px * 0.11 + 1);
            previous = rung;
            rungs.add(rung);
        }
        assertTrue("about 14 rungs over 17..256, not 240: " + rungs.size(), rungs.size() <= 16);
    }

    @Test
    public void shadowCellsMoveOnAGridCoarserThanTheGlyphs() {
        assertEquals(12, CgTextRasterMotion.shadowRung(12));
        Set<Integer> glyphRungs = new HashSet<>(), shadowRungs = new HashSet<>();
        for (int px = 33; px <= 69; px++) {
            glyphRungs.add(CgTextRasterMotion.ladder(px));
            shadowRungs.add(CgTextRasterMotion.shadowRung(px));
        }
        assertTrue("three shadow sizes over 33..69: " + shadowRungs, shadowRungs.size() <= 3);
        assertTrue(shadowRungs.size() < glyphRungs.size());
    }
}
