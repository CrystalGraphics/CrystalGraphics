package com.crystalgraphics.api.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.text.style.CgTextStyle;

public class CgTextTest {

    @Test
    public void aStringStartsPlainUnsizedUnboundedAndContained() {
        CgText t = CgText.of("Spawn");
        assertEquals("Spawn", t.text());
        assertSame(CgTextStyle.PLAIN, t.style());
        assertEquals(-1, t.size());
        assertEquals(0f, t.maxWidth(), 0f);
        assertTrue(t.contained());
    }

    @Test
    public void withersChangeOneFieldAndKeepTheRest() {
        CgText base = CgText.of("Spawn").size(24).maxWidth(200).contained(false);
        CgText other = base.text("Respawn");
        assertEquals("Respawn", other.text());
        assertEquals(24, other.size());
        assertEquals(200f, other.maxWidth(), 0f);
        assertFalse(other.contained());
        assertEquals("Spawn", base.text());
    }

    @Test
    public void valuesCompareByValue() {
        assertEquals(CgText.of("a").size(12), CgText.of("a").size(12));
        assertEquals(CgText.of("a").size(12).hashCode(), CgText.of("a").size(12).hashCode());
    }

    @Test
    public void aNewStringDropsAParagraphOrLayout() {
        CgText t = CgText.of("a").maxHeight(40).text("b");
        assertNull(t.paragraph());
        assertNull(t.layout());
        assertEquals(40f, t.maxHeight(), 0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSizeOfZeroIsRefused() {
        CgText.of("a").size(0);
    }

    @Test(expected = NullPointerException.class)
    public void aNullStyleIsRefused() {
        CgText.of("a").style(null);
    }
}
