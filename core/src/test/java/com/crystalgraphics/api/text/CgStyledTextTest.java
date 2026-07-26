package com.crystalgraphics.api.text;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgStyledText} construction/validation — sorting, overlap
 * rejection, and bounds checking of its spans.
 */
public class CgStyledTextTest {

    @Test
    public void testPlain_hasNoSpans() {
        CgStyledText text = CgStyledText.plain("hello");
        assertEquals("hello", text.plainText());
        assertTrue(text.spans().isEmpty());
    }

    @Test
    public void testConstructor_sortsSpansByStart() {
        CgStyleSpan later = span(5, 8);
        CgStyleSpan earlier = span(0, 3);
        CgStyledText text = new CgStyledText("hello world", List.of(later, earlier));

        assertEquals(List.of(earlier, later), text.spans());
    }

    @Test
    public void testConstructor_acceptsAdjacentNonOverlappingSpans() {
        CgStyleSpan first = span(0, 3);
        CgStyleSpan second = span(3, 6);
        CgStyledText text = new CgStyledText("hello!", List.of(first, second));

        assertEquals(2, text.spans().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsOverlappingSpans() {
        CgStyleSpan first = span(0, 4);
        CgStyleSpan overlapping = span(2, 6);
        new CgStyledText("hello!", List.of(first, overlapping));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsSpanPastTextEnd() {
        new CgStyledText("hi", List.of(span(0, 5)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsNullText() {
        new CgStyledText(null, List.of());
    }

    @Test
    public void testConstructor_nullSpans_treatedAsEmpty() {
        CgStyledText text = new CgStyledText("hi", null);
        assertTrue(text.spans().isEmpty());
    }

    @Test
    public void testConstructor_defensivelyCopiesSpans() {
        java.util.ArrayList<CgStyleSpan> mutable = new java.util.ArrayList<>();
        mutable.add(span(0, 2));
        CgStyledText text = new CgStyledText("hi there", mutable);

        mutable.add(span(3, 5));

        assertEquals("Later mutation of the caller's list must not affect the styled text",
                1, text.spans().size());
    }

    private static CgStyleSpan span(int start, int end) {
        return CgStyleSpan.builder().start(start).end(end).build();
    }
}
