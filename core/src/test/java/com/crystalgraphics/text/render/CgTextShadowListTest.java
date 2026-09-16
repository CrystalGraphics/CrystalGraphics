package com.crystalgraphics.text.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A draw's shadow list: appending keeps what was set, and a scoped shadow reaches its glyphs alone. */
public class CgTextShadowListTest {

    @Test
    public void growingTheCountKeepsTheShadowsAlreadySet() {
        CgTextShadowList list = new CgTextShadowList();
        list.count(1);
        list.set(0, 1f, 2f, 3f, 0f, 0xFF000000, false);
        list.count(5);
        list.set(4, 0f, 0f, 1f, 0f, 0xFF00FF00, true);
        assertEquals(1f, list.x(0), 0f);
        assertEquals(3f, list.sigma(0), 0f);
        assertTrue(list.inset(4));
    }

    @Test
    public void aScopedShadowAppliesToItsGlyphsAloneAndNotToDecorations() {
        CgTextShadowList list = new CgTextShadowList();
        list.count(2);
        list.set(0, 0f, 0f, 0f, 0f, 0xFF000000, false);
        list.set(1, 0f, 0f, 2f, 0f, 0xFFFFD700, false);
        list.scope(1, 7);
        list.glyphScopes(new int[]{-1, 7, 7});

        assertTrue(list.appliesTo(0, 0));
        assertFalse(list.appliesTo(1, 0));
        assertTrue(list.appliesTo(1, 2));
        assertFalse("past the scoped glyphs", list.appliesTo(1, 3));
        assertTrue(list.scoped(1));
        assertFalse(list.scoped(0));
    }

    @Test
    public void reachIsOffsetThreeSigmaAndSpreadOverShadowsThatCast() {
        CgTextShadowList list = new CgTextShadowList();
        list.count(2);
        list.set(0, 2f, -5f, 1.5f, 1f, 0xFF000000, false);   // 5 + ceil(4.5) + 1
        list.set(1, 40f, 0f, 0f, 0f, 0x00000000, false);     // transparent: casts nothing
        assertEquals(11f, list.reach(), 0f);
    }
}
