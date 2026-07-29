package com.crystalgraphics.api.text;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgStyleSpan} and {@link CgFontFeature} construction/validation.
 */
public class CgStyleSpanTest {

    @Test
    public void testConstructor_storesFields() {
        CgStyleSpan span = new CgStyleSpan(0, 5, true, false,
                Set.of(CgTextDecoration.UNDERLINE), 0xFFFF0000,
                List.of(CgFontFeature.enable("smcp")), 2.0f);

        assertEquals(0, span.start());
        assertEquals(5, span.end());
        assertTrue(span.bold());
        assertFalse(span.italic());
        assertEquals(Set.of(CgTextDecoration.UNDERLINE), span.decorations());
        assertEquals(0xFFFF0000, span.argbColor());
        assertEquals(List.of(CgFontFeature.enable("smcp")), span.fontFeatures());
        assertEquals(2.0f, span.baselineShift(), 0.001f);
    }

    @Test
    public void testConstructor_nullDecorations_defaultsToEmpty() {
        CgStyleSpan span = new CgStyleSpan(0, 5, false, false, null, 0, null, 0);
        assertTrue(span.decorations().isEmpty());
    }

    @Test
    public void testConstructor_nullLists_defaultToEmpty() {
        CgStyleSpan span = new CgStyleSpan(0, 5, false, false, null, 0, null, 0);
        assertTrue(span.fontFeatures().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsNegativeStart() {
        new CgStyleSpan(-1, 5, false, false, null, 0, null, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsEndNotAfterStart() {
        new CgStyleSpan(5, 5, false, false, null, 0, null, 0);
    }

    @Test
    public void testBuilder_defaultsMatchPlainConstructionDefaults() {
        CgStyleSpan span = CgStyleSpan.builder().start(0).end(3).build();

        assertFalse(span.bold());
        assertFalse(span.italic());
        assertTrue(span.decorations().isEmpty());
        assertEquals(0, span.argbColor());
        assertTrue(span.fontFeatures().isEmpty());
        assertEquals(0f, span.baselineShift(), 0.001f);
    }

    @Test
    public void testBuilder_setsProvidedFields() {
        CgStyleSpan span = CgStyleSpan.builder()
                .start(2).end(8)
                .bold(true)
                .decorations(Set.of(CgTextDecoration.STRIKETHROUGH))
                .build();

        assertEquals(2, span.start());
        assertEquals(8, span.end());
        assertTrue(span.bold());
        assertEquals(Set.of(CgTextDecoration.STRIKETHROUGH), span.decorations());
    }

    @Test
    public void testBuilder_setsMultipleSimultaneousDecorations() {
        CgStyleSpan span = CgStyleSpan.builder()
                .start(0).end(3)
                .decorations(Set.of(CgTextDecoration.UNDERLINE, CgTextDecoration.STRIKETHROUGH))
                .build();

        assertEquals(Set.of(CgTextDecoration.UNDERLINE, CgTextDecoration.STRIKETHROUGH), span.decorations());
    }

    // ---------------------------------------------------------------
    //  CgFontFeature
    // ---------------------------------------------------------------

    @Test
    public void testFontFeature_enable_defaultsValueToOne() {
        CgFontFeature feature = CgFontFeature.enable("liga");
        assertEquals("liga", feature.tag());
        assertEquals(1, feature.value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFontFeature_rejectsShortTag() {
        new CgFontFeature("abc", 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFontFeature_rejectsLongTag() {
        new CgFontFeature("abcde", 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFontFeature_rejectsNullTag() {
        new CgFontFeature(null, 1);
    }
}
