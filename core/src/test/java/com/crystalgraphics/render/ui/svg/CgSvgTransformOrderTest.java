package com.crystalgraphics.render.ui.svg;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A {@code transform} list composes as the spec's matrix product: the RIGHTMOST function reaches the
 * point first.
 *
 * <p>Pinned because the other reading is invisible on every icon that ships — a single function has no
 * order — and shows up only as artwork landing somewhere else entirely. The logo's gem was the first
 * casualty: scaled about its centre through {@code translate scale translate}, it was drawn at
 * {@code (-3, -5)} and nobody saw it.</p>
 */
public class CgSvgTransformOrderTest {

    @Test
    public void theRightmostFunctionReachesThePointFirst() {
        // Scaling about (36, 32) -- the idiom every editor writes for a nested group. The centre must
        // stay put, and a point one unit to its right lands 0.42 to its right.
        CgSvgTransform t = CgSvgTransform.parse("translate(36 32) scale(0.42) translate(-32 -32)");
        assertEquals(36f, t.applyX(32, 32), 1e-4f);
        assertEquals(32f, t.applyY(32, 32), 1e-4f);
        assertEquals(36.42f, t.applyX(33, 32), 1e-4f);
    }

    @Test
    public void aChainedListEqualsTheMatrixTheSpecSaysItIs() {
        // translate(10,20) scale(2) IS matrix(2 0 0 2 10 20); read leftmost-first it would be (2x+20, 2y+40).
        CgSvgTransform chained = CgSvgTransform.parse("translate(10,20) scale(2)");
        CgSvgTransform matrix = CgSvgTransform.parse("matrix(2 0 0 2 10 20)");
        for (float[] p : new float[][]{{0, 0}, {1, 0}, {0, 1}, {3, -4}}) {
            assertEquals(matrix.applyX(p[0], p[1]), chained.applyX(p[0], p[1]), 1e-4f);
            assertEquals(matrix.applyY(p[0], p[1]), chained.applyY(p[0], p[1]), 1e-4f);
        }
    }
}
