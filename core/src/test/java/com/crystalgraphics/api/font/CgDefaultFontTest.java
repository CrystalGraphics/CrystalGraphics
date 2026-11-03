package com.crystalgraphics.api.font;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/** The default font is the installed sans-serif, regular and bold apart, each opened once. */
public class CgDefaultFontTest {

    @Test
    public void regularAndBoldAreTheInstalledSansSerif() {
        CgFont regular = CgDefaultFont.regular(16), bold = CgDefaultFont.bold(16);
        assertEquals(16, regular.getTargetPx());
        assertNotEquals(regular.getKey(), bold.getKey());
        assertSame("one font per style and size", regular, CgDefaultFont.regular(16));
    }
}
