package com.crystalgraphics.render.ui.paint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.crystalgraphics.api.paint.gradient.CgGradient;

/** The gradient table's layout and identity, as gradient_table.glsl reads it. */
public class CgGradientTableTest {

    private static final CgGradient SKY = CgGradient.radial(0.5f, 0.25f, 0.5f, 0.75f).reflect()
            .stop(0f, 0xFF000000).stop(0.4f, 0x80FFFFFF).stop(1f, 0xFF0000FF);

    @Test
    public void layoutIsHeaderGeometryColoursPositions() {
        CgGradientTable t = new CgGradientTable();
        int g = t.add(SKY);
        assertEquals(0, g);
        float[] w = new float[4];
        t.read(g, w);
        assertEquals("shape", CgGradient.Shape.RADIAL.ordinal(), w[0], 0f);
        assertEquals("spread", 2f, w[1], 0f);
        assertEquals("stops", 3f, w[2], 0f);
        assertEquals("dithered", 1f, w[3], 0f);
        t.read(g + 1, w);
        assertEquals(0.25f, w[1], 0f);
        t.read(g + 3, w);
        assertEquals("premultiplied", 128 / 255f, w[0], 1e-6f);
        assertEquals(128 / 255f, w[3], 1e-6f);
        t.read(g + 5, w);
        assertEquals(0.4f, w[1], 0f);
        assertEquals(2 + 3 + 1, t.words());
    }

    @Test
    public void theSameGradientIsOneEntry() {
        CgGradientTable t = new CgGradientTable();
        CgGradient other = CgGradient.linear(0f).stop(0f, 1).stop(1f, 2);
        int a = t.add(SKY);
        int b = t.add(other);
        assertNotEquals(a, b);
        assertEquals(a, t.add(SKY));
        for (int i = 0; i < 100; i++) t.add(CgGradient.linear(i).stop(0f, 1).stop(1f, 2));
        assertEquals("still found after the table grew", b, t.add(other));
        t.reset();
        assertEquals(0, t.words());
        assertEquals(0, t.add(other));
    }

    @Test
    public void copyKeepsIndices() {
        CgGradientTable t = new CgGradientTable();
        int a = t.add(SKY);
        CgGradientTable copy = new CgGradientTable();
        copy.copyFrom(t);
        assertEquals(a, copy.add(SKY));
        assertEquals(t.words(), copy.words());
    }

    @Test
    public void anIncompleteGradientFailsByValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new CgGradientTable().add(CgGradient.linear(0f).stop(0f, 1)));
    }
}
