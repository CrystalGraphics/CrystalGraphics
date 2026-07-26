package com.crystalgraphics.text.richtext;

import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgMinecraftColorCodeParser} — well-formed codes, reset behavior,
 * and lenient-vs-strict handling of unknown/dangling codes.
 */
public class CgMinecraftColorCodeParserTest {

    private static final String S = "§"; // §

    private final CgMinecraftColorCodeParser lenient = new CgMinecraftColorCodeParser();
    private final CgMinecraftColorCodeParser strict = new CgMinecraftColorCodeParser(true);

    @Test
    public void testPlainText_noCodes() {
        CgStyledText result = lenient.parse("hello world");
        assertEquals("hello world", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test
    public void testBoldCode_wellFormed() {
        CgStyledText result = lenient.parse("a " + S + "lbold" + S + "r b");
        assertEquals("a bold b", result.plainText());
        assertEquals(1, result.spans().size());
        CgStyleSpan span = result.spans().get(0);
        assertTrue(span.bold());
        assertEquals(2, span.start());
        assertEquals(6, span.end());
    }

    @Test
    public void testItalicCode() {
        CgStyledText result = lenient.parse(S + "oitalic");
        assertTrue(result.spans().get(0).italic());
    }

    @Test
    public void testUnderlineCode() {
        CgStyledText result = lenient.parse(S + "nunderlined");
        assertEquals(Set.of(CgTextDecoration.UNDERLINE), result.spans().get(0).decorations());
    }

    @Test
    public void testUnderlineAndStrikethrough_combineOnOneSpan() {
        CgStyledText result = lenient.parse(S + "n" + S + "mboth");
        assertEquals(Set.of(CgTextDecoration.UNDERLINE, CgTextDecoration.STRIKETHROUGH),
                result.spans().get(0).decorations());
    }

    @Test
    public void testResetCode_clearsAllActiveFormatting() {
        CgStyledText result = lenient.parse(S + "l" + S + "obold_italic" + S + "rplain");
        assertEquals("bold_italicplain", result.plainText());
        assertEquals(1, result.spans().size());
        CgStyleSpan span = result.spans().get(0);
        assertTrue(span.bold());
        assertTrue(span.italic());
        assertEquals("bold_italic".length(), span.end());
        // "plain" after §r has no active formatting, so it gets no span
    }

    @Test
    public void testCodesAreFlatNotNested_stayOnUntilReset() {
        // §l turns bold on and it stays on; §o then adds italic on top for the text
        // that follows — "one " is bold-only, "two" is bold+italic, no §r in between.
        CgStyledText result = lenient.parse(S + "lone " + S + "otwo");
        assertEquals("one two", result.plainText());
        assertEquals(2, result.spans().size());

        CgStyleSpan boldOnly = result.spans().get(0);
        assertTrue(boldOnly.bold());
        assertFalse(boldOnly.italic());
        assertEquals(0, boldOnly.start());
        assertEquals("one ".length(), boldOnly.end());

        CgStyleSpan boldItalic = result.spans().get(1);
        assertTrue(boldItalic.bold());
        assertTrue("italic should combine with the still-active bold", boldItalic.italic());
        assertEquals("one ".length(), boldItalic.start());
        assertEquals("one two".length(), boldItalic.end());
    }

    @Test
    public void testCaseInsensitiveCodes() {
        CgStyledText result = lenient.parse(S + "Lbold");
        assertTrue(result.spans().get(0).bold());
    }

    @Test
    public void testUnknownCode_lenient_isStrippedNoEffect() {
        CgStyledText result = lenient.parse("a" + S + "zb");
        assertEquals("ab", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testUnknownCode_strict_throws() {
        strict.parse("a" + S + "zb");
    }

    @Test
    public void testDanglingSectionSign_lenient_keptAsLiteral() {
        CgStyledText result = lenient.parse("trailing" + S);
        assertEquals("trailing" + S, result.plainText());
    }

    @Test(expected = CgMarkupParseException.class)
    public void testDanglingSectionSign_strict_throws() {
        strict.parse("trailing" + S);
    }

    @Test
    public void testColorCode_appliesArgb() {
        CgStyledText result = lenient.parse(S + "cred");
        assertEquals(0xFFFF5555, result.spans().get(0).argbColor());
    }

    @Test
    public void testColorCode_resetsActiveStyles() {
        // Vanilla behavior: a color code clears bold/italic/underline, not just sets the color.
        CgStyledText result = lenient.parse(S + "l" + S + "obold_italic" + S + "cred");
        assertEquals("bold_italicred", result.plainText());
        assertEquals(2, result.spans().size());

        CgStyleSpan boldItalic = result.spans().get(0);
        assertTrue(boldItalic.bold());
        assertTrue(boldItalic.italic());
        assertEquals(0, boldItalic.argbColor());

        CgStyleSpan redOnly = result.spans().get(1);
        assertFalse(redOnly.bold());
        assertFalse(redOnly.italic());
        assertEquals(0xFFFF5555, redOnly.argbColor());
    }

    @Test
    public void testResetCode_alsoClearsColor() {
        CgStyledText result = lenient.parse(S + "cred" + S + "rplain");
        assertEquals("redplain", result.plainText());
        assertEquals(1, result.spans().size());
        assertEquals(0xFFFF5555, result.spans().get(0).argbColor());
    }

    @Test
    public void testStrikethroughCode() {
        CgStyledText result = lenient.parse(S + "mstrike");
        assertEquals(Set.of(CgTextDecoration.STRIKETHROUGH), result.spans().get(0).decorations());
    }

    @Test
    public void testObfuscatedCode_parsedButNoStyleEffect() {
        // §k is recognized (doesn't get treated as unknown/stripped-as-error), but CgStyleSpan
        // has no field for it yet, so a run with ONLY §k active produces no span at all.
        CgStyledText result = lenient.parse("a" + S + "kobfuscated" + S + "rb");
        assertEquals("aobfuscatedb", result.plainText());
        assertTrue(result.spans().isEmpty());
    }

    @Test
    public void testObfuscatedCode_strictDoesNotThrow() {
        // §k is a known code even though it has no visible style effect -- strict mode must
        // not treat it as unknown.
        CgStyledText result = strict.parse("a" + S + "kb");
        assertEquals("ab", result.plainText());
    }

    @Test
    public void testNoUnclosedTagConcept_strictDoesNotThrowForStillActiveFormattingAtEnd() {
        // Unlike the HTML-like parser, there's no open/close pairing — bold left "on"
        // at end of input is not an error, even in strict mode.
        CgStyledText result = strict.parse(S + "lbold to the end");
        assertEquals("bold to the end", result.plainText());
        assertTrue(result.spans().get(0).bold());
    }
}
