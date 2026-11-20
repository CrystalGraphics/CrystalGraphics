package com.crystalgraphics.api.paint;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.gradient.CgSpread;
import com.crystalgraphics.api.paint.pattern.CgGrid;

/** The paint values' rules: what they keep, what they refuse, what they compute. */
public class CgPaintValuesTest {

    @Test
    public void gradientStopsAscendAndClamp() {
        CgGradient g = CgGradient.linear(90f).stop(0.5f, 0xFF000000).stop(0.2f, 0xFFFFFFFF).stop(2f, 0xFF808080);
        assertEquals(0.5f, g.position(0), 0f);
        assertEquals("a stop below the one before is raised to it", 0.5f, g.position(1), 0f);
        assertEquals("positions clamp to 1", 1f, g.position(2), 0f);
    }

    @Test
    public void gradientMixesPremultiplied() {
        CgGradient fade = CgGradient.linear(90f).stop(0f, 0xFFFF0000).stop(1f, 0x000000FF);
        int mid = fade.colorAt(0.5f);
        assertEquals("half alpha", 128, mid >>> 24, 1);
        assertEquals("premultiplied: the clear end adds no blue, so red holds", 255, (mid >>> 16) & 0xFF, 1);
        assertEquals(0, mid & 0xFF);
    }

    @Test
    public void gradientHardStopAndSpread() {
        CgGradient stripes = CgGradient.linear(0f).repeat()
                .stop(0f, 0xFFFFFFFF).stop(0.25f, 0xFFFFFFFF).stop(0.25f, 0xFF000000).stop(0.5f, 0xFF000000);
        assertEquals(0xFFFFFFFF, stripes.colorAt(0.1f));
        assertEquals(0xFF000000, stripes.colorAt(0.3f));
        assertEquals("repeats past its last stop", 0xFFFFFFFF, stripes.colorAt(0.6f));
        CgGradient mirror = CgGradient.linear(0f).reflect().stop(0f, 0xFF000000).stop(1f, 0xFFFFFFFF);
        assertEquals("reflect folds back", mirror.colorAt(0.25f), mirror.colorAt(1.75f));
        assertEquals(CgSpread.PAD, CgGradient.linear(0f).spread());
    }

    @Test
    public void gradientIsAValue() {
        CgGradient a = CgGradient.radial(0.5f, 0.5f, 0.5f, 0.5f).stop(0f, 1).stop(1f, 2);
        CgGradient b = CgGradient.radial(0.5f, 0.5f, 0.5f, 0.5f).stop(0f, 1).stop(1f, 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.repeat());
        assertNotEquals(a, a.dither(false));
        assertThrows(IllegalArgumentException.class, () -> CgGradient.radial(0f, 0f, 0f, 1f));
        assertThrows(IllegalArgumentException.class, () -> CgGradient.between(1f, 1f, 1f, 1f));
        assertThrows(IllegalStateException.class, () -> CgGradient.linear(0f).stop(0f, 1).colorAt(0f));
    }

    @Test
    public void filterChainsFoldColourMatrices() {
        CgFilter chain = CgFilter.saturate(0.5f).then(CgFilter.brightness(0.8f)).then(CgFilter.blur(2f))
                .then(CgFilter.opacity(0.5f));
        assertEquals(3, chain.ops().size());
        assertEquals(CgFilter.Kind.COLOR_MATRIX, chain.ops().get(0).kind());
        assertEquals(CgFilter.Kind.BLUR, chain.ops().get(1).kind());
        float[] identity = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};
        assertArrayEquals(identity, CgFilter.saturate(1f).ops().get(0).values(), 1e-6f);
        assertArrayEquals(identity, CgFilter.hueRotate(0f).ops().get(0).values(), 1e-3f);
        assertArrayEquals(identity, CgFilter.brightness(2f).then(CgFilter.brightness(0.5f)).ops().get(0).values(), 1e-6f);
        assertEquals("three sigma", 6f, CgFilter.blur(2f).reach(), 1e-6f);
        // CSS's shadow blur is two sigma; SVG's stdDeviation is sigma, per axis
        assertArrayEquals(new float[]{3, 4, 2, 2}, Arrays.copyOf(CgFilter.dropShadow(3, 4, 4, 0).ops().get(0).values(), 4), 1e-6f);
        assertEquals("three sigma past the offset", 3f * 3f + 4f, CgFilter.dropShadow(-4, 1, 1f, 3f, 0).reach(), 1e-6f);
        assertTrue(CgFilter.NONE.then(CgFilter.NONE).isNone());
        assertSame(chain, chain.then(CgFilter.NONE));
    }

    @Test
    public void gridAndSolidRefuseNonsense() {
        assertThrows(IllegalArgumentException.class, () -> CgGrid.lines(0).cell(0f));
        assertThrows(IllegalArgumentException.class, () -> CgPaint.color(0, -1f));
        assertTrue(CgPaint.color(0x00FFFFFF).isClear());
        assertEquals(CgGrid.checker(1, 2).cell(4f), CgGrid.checker(1, 2).cell(4f, 4f));
    }
}
