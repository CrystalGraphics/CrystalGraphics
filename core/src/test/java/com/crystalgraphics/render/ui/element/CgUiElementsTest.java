package com.crystalgraphics.render.ui.element;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgraphics.render.draw.CgInstanceKind;

public class CgUiElementsTest {

    @Test
    public void halvesMatchIeee() {
        assertEquals(0x0000, CgUiElements.half(0f));
        assertEquals(0x8000, CgUiElements.half(-0f));
        assertEquals(0x3C00, CgUiElements.half(1f));
        assertEquals(0xC000, CgUiElements.half(-2f));
        assertEquals(0x3555, CgUiElements.half(1f / 3f));
        assertEquals(0x7BFF, CgUiElements.half(65504f));
        assertEquals(0x7C00, CgUiElements.half(1e6f));
        assertEquals(0x0001, CgUiElements.half(5.9604645e-8f));   // the smallest subnormal
        assertEquals(0x0400, CgUiElements.half(6.1035156e-5f));   // the smallest normal
        assertEquals(0x4B80, CgUiElements.half(15f));
        assertEquals(0x3C00, CgUiElements.half(1.0004883f));      // a tie rounds to even
    }

    @Test
    public void recordLayoutMatchesTheFormat() {
        assertEquals(24, CgUiElements.FLOATS);
        assertEquals(96, CgInstanceKind.ELEMENT.stride());
        assertEquals(CgUiElements.PAINT + 1, CgUiElements.FLOATS);
    }

    @Test
    public void aZeroRecordIsOpaqueAtUnitIntensity() {
        float[] r = new float[CgUiElements.FLOATS];
        CgUiElements.opacity(r, 0, 1f, 1f);
        assertEquals(0, Float.floatToRawIntBits(r[CgUiElements.FADE_BOOST]));
    }

    @Test
    public void radiiSetTheRoundedFlagOnlyWhenRound() {
        float[] r = new float[CgUiElements.FLOATS];
        CgUiElements.radii(r, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, Float.floatToRawIntBits(r[CgUiElements.FLAGS]) & CgUiElements.FLAG_ROUNDED);
        CgUiElements.radii(r, 0, 4, 2, 0, 0, 0, 0, 0, 0);
        assertEquals(CgUiElements.FLAG_ROUNDED, Float.floatToRawIntBits(r[CgUiElements.FLAGS]) & CgUiElements.FLAG_ROUNDED);
        assertEquals(CgUiElements.half2(4f, 2f), Float.floatToRawIntBits(r[CgUiElements.RADII]));
    }
}
