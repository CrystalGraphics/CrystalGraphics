package com.crystalgraphics.api.vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Paths, strokes and radii as values: what the builder stores and what it refuses. */
public class CgVectorPathTest {

    @Test
    public void builderStoresSteps() {
        CgVectorPath p = CgVectorPath.builder().moveTo(0, 0).lineTo(10, 0).quadTo(15, 5, 10, 10).close().build();
        assertEquals(4, p.verbCount());
        assertEquals(CgVectorPath.Verb.QUAD, p.verb(2));
        assertEquals(15f, p.maxX(), 0f);
        assertEquals(10f, p.maxY(), 0f);
        assertEquals(p, CgVectorPath.builder().moveTo(0, 0).lineTo(10, 0).quadTo(15, 5, 10, 10).close().build());
        assertNotEquals(p, p.fillRule(CgFillRule.EVEN_ODD));
    }

    @Test
    public void arcEndsExactlyAndStaysOnItsCircle() {
        // A half circle of radius 10 from (0, 0) to (20, 0), centre (10, 0).
        CgVectorPath arc = CgVectorPath.builder().moveTo(0, 0).arcTo(10, 10, 0, false, true, 20, 0).build();
        List<float[]> ends = new ArrayList<>();
        arc.forEach(new Recorder(ends));
        float[] last = ends.get(ends.size() - 1);
        assertEquals(20f, last[0], 0f);
        assertEquals(0f, last[1], 0f);
        assertEquals("two quarter turns", 3, arc.verbCount());
        for (float[] e : ends) {
            assertEquals("each segment ends on the circle", 10.0, Math.hypot(e[0] - 10, e[1]), 1e-3);
        }
    }

    @Test
    public void roundRectFitsItsRadii() {
        CgCornerRadii huge = CgCornerRadii.of(40f);
        CgCornerRadii fitted = huge.fitted(20f, 10f);
        assertEquals("scaled so the short side's corners meet", 5f, fitted.topLeftY(), 1e-5f);
        CgVectorPath box = CgVectorPath.builder().addRoundRect(0, 0, 20, 10, huge).build();
        assertEquals(20f, box.maxX(), 1e-5f);
        assertTrue(CgCornerRadii.NONE.isNone());
        assertTrue(CgCornerRadii.of(3f).isUniformCircle());
    }

    @Test
    public void strokeRules() {
        CgStroke s = CgStroke.of(2f).dash(4f);
        assertEquals("an odd dash list repeats once", 2, s.dashes().length);
        assertThrows(IllegalArgumentException.class, () -> CgStroke.of(1f).trim(0.6f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> CgStroke.of(0f));
        assertEquals(CgStroke.of(1f).trim(0f, 0.5f), CgStroke.of(1f).trim(0f, 0.5f));
        assertTrue(CgStroke.of(1f).isUntrimmed());
    }

    @Test
    public void contourMustStartWithMove() {
        assertThrows(IllegalStateException.class, () -> CgVectorPath.builder().lineTo(1, 1));
    }

    private record Recorder(List<float[]> ends) implements CgVectorPath.Visitor {
        @Override
        public void moveTo(float x, float y) {
        }

        @Override
        public void lineTo(float x, float y) {
            ends.add(new float[]{x, y});
        }

        @Override
        public void quadTo(float cx, float cy, float x, float y) {
            ends.add(new float[]{x, y});
        }

        @Override
        public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
            ends.add(new float[]{x, y});
        }

        @Override
        public void close() {
        }
    }
}
