package com.crystalgraphics.api.font;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.text.font.Sfnt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The default font ships, is variable, and is instanced at the weight asked rather than its Thin default. */
public class CgDefaultFontTest {

    @Test
    public void outfitShipsWithItsWeightAxis() {
        byte[] data = CgDefaultFont.bytes();
        assertTrue("the font is in the jar", data.length > 100_000);
        assertArrayEquals(new float[] {100f, 100f, 900f}, Sfnt.weightAxis(data), 0f);
        assertTrue(CgFont.hasWeightAxis(data));
    }

    @Test
    public void regularAndBoldAreInstancesOfTheAxis() {
        CgFont regular = CgDefaultFont.regular(16), bold = CgDefaultFont.bold(16);
        assertNotNull(regular.getKey());
        assertEquals(List.of(new CgFontVariation("wght", 400f)), regular.getKey().getVariations());
        assertEquals(List.of(new CgFontVariation("wght", 700f)), bold.getKey().getVariations());
        assertNotEquals(regular.getKey(), bold.getKey());
        assertSame("one unsized font per weight", CgDefaultFont.unsized(400), CgDefaultFont.unsized(400));
    }

    @Test
    public void aStaticFontHasNoWeightAxis() {
        assertEquals(null, Sfnt.weightAxis(new byte[64]));
    }
}
