package com.crystalgraphics.api.font;

import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.text.layout.CgTextLayoutEngine;
import com.crystalgraphics.text.layout.RunReshaper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgTextLayoutBuilder}'s paragraph splitting logic.
 *
 * <p>These tests exercise the newline normalization and paragraph splitting
 * that happens before BiDi analysis. They use a tiny probe subclass of
 * {@link CgTextLayoutEngine}, avoiding the need for native HarfBuzz libraries.</p>
 */
public class CgTextLayoutBuilderTest {

    private static final class ParagraphProbe extends CgTextLayoutEngine {
        @Override
        protected void collectShapedRuns(String text,
                                         int start,
                                         int end,
                                         boolean rtl,
                                         CgFontFamily family,
                                         List<CgShapedRun> out) {
            throw new UnsupportedOperationException("Paragraph splitting tests do not shape runs");
        }

        @Override
        protected RunReshaper createRunReshaper(CgFontFamilyGroup group) {
            throw new UnsupportedOperationException("Paragraph splitting tests do not reshape runs");
        }

        List<String> split(String text) {
            return splitParagraphs(text);
        }
    }

    private final ParagraphProbe paragraphProbe = new ParagraphProbe();

    // ---------------------------------------------------------------
    //  Paragraph splitting: \n
    // ---------------------------------------------------------------

    @Test
    public void testSplitParagraphs_singleNewline() {
        List<String> result = paragraphProbe.split("hello\nworld");
        assertEquals(2, result.size());
        assertEquals("hello", result.get(0));
        assertEquals("world", result.get(1));
    }

    @Test
    public void testSplitParagraphs_consecutiveNewlines() {
        List<String> result = paragraphProbe.split("a\n\nb");
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
        assertEquals("", result.get(1));
        assertEquals("b", result.get(2));
    }

    @Test
    public void testSplitParagraphs_trailingNewline() {
        List<String> result = paragraphProbe.split("hello\n");
        assertEquals(2, result.size());
        assertEquals("hello", result.get(0));
        assertEquals("", result.get(1));
    }

    @Test
    public void testSplitParagraphs_leadingNewline() {
        List<String> result = paragraphProbe.split("\nhello");
        assertEquals(2, result.size());
        assertEquals("", result.get(0));
        assertEquals("hello", result.get(1));
    }

    // ---------------------------------------------------------------
    //  Paragraph splitting: \r\n (Windows)
    // ---------------------------------------------------------------

    @Test
    public void testSplitParagraphs_crLf() {
        List<String> result = paragraphProbe.split("A\r\nB");
        assertEquals(2, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
    }

    // ---------------------------------------------------------------
    //  Paragraph splitting: \r (old Mac)
    // ---------------------------------------------------------------

    @Test
    public void testSplitParagraphs_crOnly() {
        List<String> result = paragraphProbe.split("A\rB");
        assertEquals(2, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
    }

    // ---------------------------------------------------------------
    //  Mixed line endings
    // ---------------------------------------------------------------

    @Test
    public void testSplitParagraphs_mixedEndings() {
        List<String> result = paragraphProbe.split("A\r\nB\rC\nD");
        assertEquals(4, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
        assertEquals("D", result.get(3));
    }

    // ---------------------------------------------------------------
    //  Edge cases
    // ---------------------------------------------------------------

    @Test
    public void testSplitParagraphs_noNewlines() {
        List<String> result = paragraphProbe.split("hello world");
        assertEquals(1, result.size());
        assertEquals("hello world", result.get(0));
    }

    @Test
    public void testSplitParagraphs_onlyNewline() {
        List<String> result = paragraphProbe.split("\n");
        assertEquals(2, result.size());
        assertEquals("", result.get(0));
        assertEquals("", result.get(1));
    }

    @Test
    public void testSplitParagraphs_multipleTrailingNewlines() {
        List<String> result = paragraphProbe.split("a\n\n\n");
        assertEquals(4, result.size());
        assertEquals("a", result.get(0));
        assertEquals("", result.get(1));
        assertEquals("", result.get(2));
        assertEquals("", result.get(3));
    }

    // ---------------------------------------------------------------
    //  Layout API signature verification
    //
    //  The old layout(String/CgStyledText, CgFont/CgFontFamily/CgFontFamilyGroup,
    //  maxWidth, maxHeight[, logicalPx]) overload family is gone — replaced outright by
    //  CgTextLayoutRequest/CgShapedParagraph (phase 12). These checks now verify the
    //  shape/shapeStyled methods CgTextLayoutRequest delegates to still exist with the
    //  expected signature, and that the deleted overloads are actually gone.
    // ---------------------------------------------------------------

    @Test
    public void testShapeMethodExists() throws NoSuchMethodException {
        CgTextLayoutBuilder.class.getMethod("shape",
                String.class, CgFontFamily.class, com.crystalgraphics.api.text.CgParagraphKnobs.class);
    }

    @Test
    public void testShapeStyledMethodExists() throws NoSuchMethodException {
        CgTextLayoutBuilder.class.getMethod("shapeStyled",
                com.crystalgraphics.api.text.CgStyledText.class, CgFontFamilyGroup.class,
                com.crystalgraphics.api.text.CgParagraphKnobs.class);
    }

    @Test(expected = NoSuchMethodException.class)
    public void testOldLayoutOverload_noLongerExists() throws NoSuchMethodException {
        CgTextLayoutBuilder.class.getMethod("layout",
                String.class, CgFont.class, float.class, float.class);
    }
}
