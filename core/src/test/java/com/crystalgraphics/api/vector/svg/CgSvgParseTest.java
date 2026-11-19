package com.crystalgraphics.api.vector.svg;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** What a file may not use, and a sprite sheet's symbols. */
public class CgSvgParseTest {

    private static final String SHEET = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">"
            + "<defs><symbol id=\"dot\" viewBox=\"0 0 8 8\"><circle cx=\"4\" cy=\"4\" r=\"3\"/></symbol></defs>"
            + "<rect width=\"64\" height=\"64\" fill=\"currentColor\"/></svg>";

    @Test
    public void aStyleBlockFailsByName() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CgSvg.parse(
                "<svg viewBox=\"0 0 16 16\"><style>.a{fill:red}</style><rect class=\"a\" width=\"8\" height=\"8\"/></svg>"));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("<style>"));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("presentation attributes"));
    }

    @Test
    public void aClassAttributeFailsByName() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CgSvg.parse("<svg viewBox=\"0 0 16 16\"><rect class=\"st0\" width=\"8\" height=\"8\"/></svg>"));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("class=\"st0\""));
    }

    @Test
    public void aSymbolDrawsAloneInItsOwnBox() {
        CgSvg sheet = CgSvg.parse(SHEET);
        assertTrue("the sheet's own picture is the rect", sheet.usesCurrentColor());
        CgSvg dot = sheet.symbol("dot");
        assertEquals(8f, dot.width(), 0f);
        assertEquals(8f, dot.height(), 0f);
        assertFalse("the rect is not the symbol's", dot.usesCurrentColor());
        // Its ink (1 to 7) inside its own box, so the bounds are the box
        assertEquals(0f, dot.minX(), 0f);
        assertEquals(8f, dot.maxX(), 0f);
        // A tier of a symbol resolves the symbol again, not the sheet
        assertEquals(8f, dot.tier(16f).width(), 0f);
        assertFalse(dot.tier(16f).ops().isEmpty());
    }

    @Test
    public void aMissingSymbolFailsByName() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CgSvg.parse(SHEET).symbol("nope"));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("id=\"nope\""));
    }
}
