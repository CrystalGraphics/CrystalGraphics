package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;

import java.nio.charset.StandardCharsets;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Internal base implementation of the text layout pipeline.
 *
 * <p>The concrete shaping/fallback bridge is supplied by a subclass so the real
 * algorithm can live in the {@code text} package while package-private font
 * internals remain encapsulated inside {@code api/font}.</p>
 */
public abstract class CgTextLayoutEngine {

    private final CgLineBreaker lineBreaker = new CgLineBreaker();

    public CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight) {
        return layout(text, CgFontFamily.of(font), maxWidth, maxHeight,
                font != null ? font.getKey().getTargetPx() : 0);
    }

    public CgTextLayout layout(String text, CgFont font,
                               float maxWidth, float maxHeight, float logicalPx) {
        return layout(text, CgFontFamily.of(font), maxWidth, maxHeight, logicalPx);
    }

    public CgTextLayout layout(String text, CgFontFamily family, float maxWidth, float maxHeight) {
        return layout(text, family, maxWidth, maxHeight,
                family != null ? family.getTargetPx() : 0);
    }

    public CgTextLayout layout(String text, CgFontFamily family,
                               float maxWidth, float maxHeight, float logicalPx) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");

        CgFontMetrics metrics = family.getLayoutMetrics();
        float targetPx = family.getTargetPx();

        float scale = (logicalPx > 0 && targetPx > 0) ? targetPx / logicalPx : 1.0f;
        float inverseScale = (logicalPx > 0 && targetPx > 0) ? logicalPx / targetPx : 1.0f;

        if (text.isEmpty()) {
            return new CgTextLayout(new ArrayList<>(), 0, 0, metrics, CgBakedGlyphs.EMPTY);
        }

        float targetMaxWidth = maxWidth > 0 ? maxWidth * scale : maxWidth;
        float targetMaxHeight = maxHeight > 0 ? maxHeight * scale : maxHeight;

        List<String> paragraphs = splitParagraphs(text);

        List<List<CgShapedRun>> allLines = new ArrayList<>();
        List<boolean[]> justifiableByLine = new ArrayList<>();
        float totalHeight = 0.0f;
        float lineHeight = metrics.getLineHeight();

        for (String paragraph : paragraphs) {
            if (targetMaxHeight > 0 && totalHeight + lineHeight > targetMaxHeight) {
                break;
            }

            if (paragraph.isEmpty()) {
                allLines.add(new ArrayList<>());
                justifiableByLine.add(new boolean[0]);
                totalHeight += lineHeight;
                continue;
            }

            List<CgShapedRun> shapedRuns = splitAndShapeRuns(paragraph, family);
            float remainingHeight = targetMaxHeight > 0 ? targetMaxHeight - totalHeight : 0;
            CgReshapeContext reshapeContext = new CgReshapeContext(paragraph);
            List<List<CgShapedRun>> paraLines = lineBreaker.breakLines(
                    shapedRuns, targetMaxWidth, remainingHeight, metrics,
                    reshapeContext, createRunReshaper(family));

            BitSet lineBreakBoundaries = collectLineBreakBoundaries(paragraph);
            for (List<CgShapedRun> line : paraLines) {
                allLines.add(line);
                justifiableByLine.add(computeJustifiable(paragraph, lineBreakBoundaries, line));
            }
            totalHeight += paraLines.size() * lineHeight;
        }

        float totalWidth = 0;
        for (List<CgShapedRun> line : allLines) {
            float lineWidth = 0;
            for (CgShapedRun run : line) {
                lineWidth += run.getTotalAdvance();
            }
            if (lineWidth > totalWidth) {
                totalWidth = lineWidth;
            }
        }
        totalHeight = allLines.size() * lineHeight;

        CgBakedGlyphs baked = bakeGlyphs(allLines, justifiableByLine, metrics);

        return new CgTextLayout(allLines, totalWidth * inverseScale, totalHeight * inverseScale, metrics, baked);
    }

    protected List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<String>();
        int len = text.length();
        int start = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                paragraphs.add(text.substring(start, i));
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n') {
                paragraphs.add(text.substring(start, i));
                start = i + 1;
            }
        }

        paragraphs.add(text.substring(start));
        return paragraphs;
    }

    private List<CgShapedRun> splitAndShapeRuns(String text, CgFontFamily family) {
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        int runCount = bidi.getRunCount();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>(runCount);

        for (int i = 0; i < runCount; i++) {
            int start = bidi.getRunStart(i);
            int end = bidi.getRunLimit(i);
            int level = bidi.getRunLevel(i);
            boolean rtl = (level % 2) != 0;
            collectShapedRuns(text, start, end, rtl, family, runs);
        }

        return runs;
    }

    protected abstract void collectShapedRuns(String text, int start, int end, boolean rtl, CgFontFamily family, List<CgShapedRun> out);

    protected abstract RunReshaper createRunReshaper(CgFontFamily family);

    /**
     * Bakes {@code lines} (and their parallel {@code justifiableByLine} flags) into a flat
     * {@link CgBakedGlyphs} — one walk over the final line/run tree, pen positions relative
     * to the layout's own origin (the caller's own draw {@code x, y} is added later, once,
     * by whoever resolves atlas placements).
     */
    static CgBakedGlyphs bakeGlyphs(List<List<CgShapedRun>> lines,
                                     List<boolean[]> justifiableByLine,
                                     CgFontMetrics metrics) {
        int glyphCount = 0;
        for (List<CgShapedRun> line : lines) {
            for (CgShapedRun run : line) {
                glyphCount += run.getGlyphIds().length;
            }
        }

        CgFontKey[] fontKeys = new CgFontKey[glyphCount];
        CgFont[] fonts = new CgFont[glyphCount];
        int[] glyphIds = new int[glyphCount];
        float[] penX = new float[glyphCount];
        float[] penY = new float[glyphCount];
        float[] offsetX = new float[glyphCount];
        boolean[] justifiable = new boolean[glyphCount];
        float[] lineHeights = new float[lines.size()];
        int[] lineStart = new int[lines.size() + 1];

        float lineHeight = metrics.getLineHeight();
        int index = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            List<CgShapedRun> line = lines.get(lineIndex);
            boolean[] lineJustifiable = justifiableByLine.get(lineIndex);
            lineStart[lineIndex] = index;
            lineHeights[lineIndex] = lineHeight;

            float lineBaseline = metrics.getAscender() + lineIndex * lineHeight;
            float penXCursor = 0;
            int glyphInLine = 0;
            for (CgShapedRun run : line) {
                CgFontKey fontKey = run.getFontKey();
                CgFont font = run.getResolvedFont();
                int[] ids = run.getGlyphIds();
                float[] advances = run.getAdvancesX();
                float[] offsetsX = run.getOffsetsX();
                float[] offsetsY = run.getOffsetsY();
                for (int i = 0; i < ids.length; i++) {
                    fontKeys[index] = fontKey;
                    fonts[index] = font;
                    glyphIds[index] = ids[i];
                    penX[index] = penXCursor + offsetsX[i];
                    penY[index] = lineBaseline + offsetsY[i];
                    offsetX[index] = offsetsX[i];
                    justifiable[index] = lineJustifiable[glyphInLine];
                    penXCursor += advances[i];
                    index++;
                    glyphInLine++;
                }
            }
        }
        lineStart[lines.size()] = glyphCount;

        return new CgBakedGlyphs(glyphCount, fontKeys, fonts, glyphIds, penX, penY, offsetX, justifiable, lineHeights, lineStart);
    }

    /**
     * Collects UAX#14 line-break boundary char offsets for the whole paragraph, once,
     * so per-line justifiable tagging doesn't re-run {@link BreakIterator} per line.
     * Excludes the trivial boundary at {@code 0} (breaking before any text is useless —
     * same exclusion {@link CgLineBreaker#collectBoundaries} applies).
     */
    static BitSet collectLineBreakBoundaries(String paragraphText) {
        BreakIterator iterator = BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(paragraphText);
        BitSet boundaries = new BitSet(paragraphText.length() + 1);
        int boundary = iterator.first();
        while (boundary != BreakIterator.DONE) {
            if (boundary > 0) {
                boundaries.set(boundary);
            }
            boundary = iterator.next();
        }
        return boundaries;
    }

    /**
     * Tags each glyph in {@code line} as justifiable (Seam A — see {@link CgBakedGlyphs})
     * by mapping its HarfBuzz cluster id (a UTF-8 byte offset into its run's source
     * substring) back to a UTF-16 char offset in {@code paragraphText}, then checking
     * whether a UAX#14 boundary falls exactly there. The last flagged glyph on the line
     * is un-flagged per the seam's contract (no justification point at line end).
     */
    static boolean[] computeJustifiable(String paragraphText, BitSet lineBreakBoundaries,
                                         List<CgShapedRun> line) {
        int glyphCount = 0;
        for (CgShapedRun run : line) {
            glyphCount += run.getGlyphIds().length;
        }

        boolean[] result = new boolean[glyphCount];
        int index = 0;
        int lastJustifiableIndex = -1;
        for (CgShapedRun run : line) {
            int runStart = run.getSourceStart();
            int[] byteToChar = utf8ByteOffsetToCharOffset(
                    paragraphText.substring(runStart, run.getSourceEnd()));
            for (int cluster : run.getClusterIds()) {
                int absoluteCharOffset = runStart + byteToChar[cluster];
                if (lineBreakBoundaries.get(absoluteCharOffset)) {
                    result[index] = true;
                    lastJustifiableIndex = index;
                }
                index++;
            }
        }
        if (lastJustifiableIndex >= 0) {
            result[lastJustifiableIndex] = false;
        }
        return result;
    }

    /**
     * Maps each UTF-8 byte offset in {@code text}'s UTF-8 encoding to the UTF-16 char
     * offset of the codepoint that owns that byte — the mapping needed to translate a
     * HarfBuzz cluster id (a UTF-8 byte offset) back into a position in the original
     * Java string.
     */
    private static int[] utf8ByteOffsetToCharOffset(String text) {
        int byteLength = text.getBytes(StandardCharsets.UTF_8).length;
        int[] map = new int[byteLength + 1];
        int byteOffset = 0;
        int charOffset = 0;
        int len = text.length();
        while (charOffset < len) {
            int codePoint = text.codePointAt(charOffset);
            int charCount = Character.charCount(codePoint);
            int byteCount = utf8ByteCount(codePoint);
            for (int b = 0; b < byteCount; b++) {
                map[byteOffset + b] = charOffset;
            }
            byteOffset += byteCount;
            charOffset += charCount;
        }
        map[byteLength] = len;
        return map;
    }

    private static int utf8ByteCount(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
