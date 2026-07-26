package com.crystalgraphics.text.richtext;

import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgTagMarkupParser} — well-formed input, unclosed tags,
 * unmatched closing tags, and nested tags producing merged (not overlapping) spans.
 */
public class CgTagMarkupParserTest {

    private final CgTagMarkupParser lenient = new CgTagMarkupParser();
    private final CgTagMarkupParser strict = new CgTagMarkupParser(true);

    @Test
    public void testPlainText_noTags() {
        CgStyledText result = lenient.parse("hello world");
        assertEquals("hello world", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test
    public void testBoldTag_wellFormed() {
        CgStyledText result = lenient.parse("a <b>bold</b> b");
        assertEquals("a bold b", result.plainText());
        assertEquals(1, result.spans().size());
        CgStyleSpan span = result.spans().get(0);
        assertEquals(2, span.start());
        assertEquals(6, span.end());
        assertTrue(span.bold());
        assertFalse(span.italic());
    }

    @Test
    public void testStrongAndEmAliases_behaveLikeBAndI() {
        CgStyledText result = lenient.parse("<strong>x</strong><em>y</em>");
        assertEquals("xy", result.plainText());
        assertEquals(2, result.spans().size());
        assertTrue(result.spans().get(0).bold());
        assertTrue(result.spans().get(1).italic());
    }

    @Test
    public void testUnderlineTag() {
        CgStyledText result = lenient.parse("<u>under</u>");
        CgStyleSpan span = result.spans().get(0);
        assertEquals(CgTextDecoration.UNDERLINE, span.decoration());
    }

    @Test
    public void testColorTag_parsesHex() {
        CgStyledText result = lenient.parse("<color=#FF0000>red</color>");
        CgStyleSpan span = result.spans().get(0);
        assertEquals(0xFFFF0000, span.argbColor());
    }

    @Test
    public void testColorTag_withoutHashPrefix() {
        CgStyledText result = lenient.parse("<color=00FF00>green</color>");
        assertEquals(0xFF00FF00, result.spans().get(0).argbColor());
    }

    @Test
    public void testNestedTags_produceMergedNonOverlappingSpans() {
        // <b><i>x</i></b> — inner close then outer close must not overlap
        CgStyledText result = lenient.parse("<b><i>x</i></b>");
        assertEquals("x", result.plainText());
        // Must not throw (CgStyledText rejects overlapping spans) and must merge correctly
        for (CgStyleSpan span : result.spans()) {
            assertTrue(span.bold() || span.italic());
        }
        assertEquals(1, result.plainText().length());
    }

    @Test
    public void testNestedTags_partialOverlap_boldWrapsItalicWord() {
        // "a <b>b <i>c</i> d</b> e" — bold covers "b c d", italic covers only "c"
        CgStyledText result = lenient.parse("a <b>b <i>c</i> d</b> e");
        assertEquals("a b c d e", result.plainText());

        boolean foundBoldOnly = false;
        boolean foundBoldItalic = false;
        for (CgStyleSpan span : result.spans()) {
            if (span.bold() && span.italic()) foundBoldItalic = true;
            else if (span.bold()) foundBoldOnly = true;
        }
        assertTrue("Should have a bold-only region", foundBoldOnly);
        assertTrue("Should have a bold+italic region", foundBoldItalic);
    }

    @Test
    public void testUnclosedTag_lenient_implicitlyClosesAtEnd() {
        CgStyledText result = lenient.parse("<b>bold to the end");
        assertEquals("bold to the end", result.plainText());
        assertEquals(1, result.spans().size());
        assertTrue(result.spans().get(0).bold());
        assertEquals(result.plainText().length(), result.spans().get(0).end());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testUnclosedTag_strict_throws() {
        strict.parse("<b>bold to the end");
    }

    @Test
    public void testUnmatchedClosingTag_lenient_isNoOp() {
        CgStyledText result = lenient.parse("hello </b> world");
        assertEquals("hello  world", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testUnmatchedClosingTag_strict_throws() {
        strict.parse("hello </b> world");
    }

    @Test
    public void testUnknownTag_lenient_isStrippedNoEffect() {
        CgStyledText result = lenient.parse("<marquee>text</marquee>");
        assertEquals("text", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testUnknownTag_strict_throws() {
        strict.parse("<marquee>text</marquee>");
    }

    @Test
    public void testUnterminatedTag_lenient_treatedAsLiteralText() {
        CgStyledText result = lenient.parse("hello <b broken");
        assertEquals("hello <b broken", result.plainText());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testUnterminatedTag_strict_throws() {
        strict.parse("hello <b broken");
    }

    @Test
    public void testMalformedColor_lenient_treatedAsNoColor() {
        CgStyledText result = lenient.parse("<color=notahexvalue>text</color>");
        assertTrue("No valid color, so no span should be emitted at all",
                result.spans().isEmpty());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testMalformedColor_strict_throws() {
        strict.parse("<color=notahexvalue>text</color>");
    }

    @Test
    public void testEmptyTagBody_emitsNoZeroLengthSpan() {
        CgStyledText result = lenient.parse("<b></b>hello");
        assertEquals("hello", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test
    public void testAdjacentSiblingTags_produceSeparateSpans() {
        CgStyledText result = lenient.parse("<b>a</b><i>b</i>");
        assertEquals("ab", result.plainText());
        assertEquals(2, result.spans().size());
        assertEquals(List.of(0, 1), List.of(result.spans().get(0).start(), result.spans().get(1).start()));
    }
}
