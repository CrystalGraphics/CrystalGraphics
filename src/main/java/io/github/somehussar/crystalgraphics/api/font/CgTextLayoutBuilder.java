package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBFont;
import io.github.somehussar.crystalgraphics.text.CgLineBreaker;
import io.github.somehussar.crystalgraphics.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.text.CgTextShaper;
import io.github.somehussar.crystalgraphics.text.RunReshaper;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full text layout pipeline: BiDi analysis → shaping → line breaking → layout result.
 *
 * <p>Placed in {@code api/font} to access {@link CgFont#getHbFontInternal()}
 * (package-private) without exposing HarfBuzz in the public API.</p>
 *
 * <p>Delegates to {@link CgTextShaper} for per-run shaping and
 * {@link CgLineBreaker} for line breaking and visual reordering.</p>
 */
public class CgTextLayoutBuilder {

    private final CgTextShaper shaper = new CgTextShaper();
    private final CgLineBreaker lineBreaker = new CgLineBreaker();

    /**
     * Runs the full layout pipeline on the given text.
     *
     * <ol>
     *   <li>{@link Bidi} paragraph analysis → directional runs</li>
     *   <li>{@link CgTextShaper#shape} each run</li>
     *   <li>{@link CgLineBreaker#breakLines} with per-line visual reorder</li>
     *   <li>Measure total width / height</li>
     * </ol>
     *
     * @param text      input string (Java UTF-16)
     * @param font      provides the font key, metrics, and internal HBFont
     * @param maxWidth  maximum width in pixels; &lt;= 0 means unbounded
     * @param maxHeight maximum height in pixels; &lt;= 0 means unbounded
     * @return immutable layout result
     */
    public CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight) {
        return layout(text, font, maxWidth, maxHeight, font != null ? font.getKey().getTargetPx() : 0);
    }

    /**
     * Runs the full layout pipeline with explicit logical pixel size.
     *
     * <p>Shaping and glyph rendering use {@code font}'s target pixel size. Layout
     * constraints ({@code maxWidth}, {@code maxHeight}) and reported dimensions
     * ({@link CgTextLayout#getTotalWidth()}, {@link CgTextLayout#getTotalHeight()})
     * are expressed in logical pixels. Internally, constraints are scaled up to
     * target pixels for line breaking, and final measurements are scaled back down.</p>
     *
     * <p>When {@code logicalPx == targetPx}, this behaves identically to the
     * 4-argument overload (no scaling).</p>
     *
     * @param text       input string (Java UTF-16)
     * @param font       provides the font key, metrics, and internal HBFont
     * @param maxWidth   maximum width in logical pixels; &lt;= 0 means unbounded
     * @param maxHeight  maximum height in logical pixels; &lt;= 0 means unbounded
     * @param logicalPx  logical pixel size for layout dimensions (must be &gt; 0)
     * @return immutable layout result with dimensions in logical pixels
     */
    public CgTextLayout layout(String text, CgFont font,
                               float maxWidth, float maxHeight, float logicalPx) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (font == null || font.isDisposed()) {
            throw new IllegalArgumentException("font must not be null or disposed");
        }

        CgFontKey fontKey = font.getKey();
        CgFontMetrics metrics = font.getMetrics();
        HBFont hbFont = font.getHbFontInternal();
        float targetPx = fontKey.getTargetPx();

        // Scale factor: convert logical → target pixels for line-breaking,
        // then target → logical for reported dimensions.
        float scale = (logicalPx > 0 && targetPx > 0) ? targetPx / logicalPx : 1.0f;
        float inverseScale = (logicalPx > 0 && targetPx > 0) ? logicalPx / targetPx : 1.0f;

        if (text.isEmpty()) {
            List<List<CgShapedRun>> emptyLines = new ArrayList<List<CgShapedRun>>();
            return new CgTextLayout(emptyLines, 0, 0, metrics);
        }

        // Scale constraints from logical to target pixels for line breaking
        float targetMaxWidth = maxWidth > 0 ? maxWidth * scale : maxWidth;
        float targetMaxHeight = maxHeight > 0 ? maxHeight * scale : maxHeight;

        // 1. Split into paragraphs at normalized newline boundaries (\r\n, \r, \n).
        //    Each paragraph is shaped and line-broken independently so newline
        //    characters never reach HarfBuzz.
        List<String> paragraphs = splitParagraphs(text);

        // 2. Shape and line-break each paragraph independently
        List<List<CgShapedRun>> allLines = new ArrayList<List<CgShapedRun>>();
        float totalHeight = 0.0f;
        float lineHeight = metrics.getLineHeight();

        for (String paragraph : paragraphs) {
            if (targetMaxHeight > 0 && totalHeight + lineHeight > targetMaxHeight) {
                break;
            }

            if (paragraph.isEmpty()) {
                // Empty paragraph → empty visual line (preserves consecutive newlines)
                allLines.add(new ArrayList<CgShapedRun>());
                totalHeight += lineHeight;
                continue;
            }

            List<CgShapedRun> shapedRuns = splitAndShapeRuns(paragraph, fontKey, hbFont);

            // Create a reshaper that delegates to the shaper for intra-run word wrapping
            final CgFontKey fk = fontKey;
            final HBFont hf = hbFont;
            RunReshaper reshaper = new RunReshaper() {
                @Override
                public CgShapedRun reshape(CgShapedRun run, int subStart, int subEnd) {
                    return shaper.shape(run.getSourceText(), subStart, subEnd,
                            fk, run.isRtl(), hf);
                }
            };

            float remainingHeight = targetMaxHeight > 0 ? targetMaxHeight - totalHeight : 0;
            List<List<CgShapedRun>> paraLines = lineBreaker.breakLines(
                    shapedRuns, targetMaxWidth, remainingHeight, metrics, reshaper);

            allLines.addAll(paraLines);
            totalHeight += paraLines.size() * lineHeight;
        }

        // 3. Measure total width and height in target pixels, then scale to logical
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

        return new CgTextLayout(allLines,
                totalWidth * inverseScale,
                totalHeight * inverseScale,
                metrics);
    }

    /**
     * Splits text into paragraphs at newline boundaries.
     *
     * <p>Normalizes all common line endings ({@code \r\n}, {@code \r}, {@code \n})
     * into paragraph boundaries. A trailing newline produces a final empty paragraph
     * so that trailing blank lines are preserved in the layout.</p>
     *
     * @param text non-null, non-empty input text
     * @return list of paragraph strings (never empty for non-empty input)
     */
    List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<String>();
        int len = text.length();
        int start = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                paragraphs.add(text.substring(start, i));
                // Consume \r\n as a single line ending
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n') {
                paragraphs.add(text.substring(start, i));
                start = i + 1;
            }
        }

        // Final segment (always added — trailing newline produces empty string)
        paragraphs.add(text.substring(start));
        return paragraphs;
    }

    /**
     * Splits text into BiDi directional runs and shapes each one.
     *
     * <p>Pipeline: {@link Bidi} → extract run boundaries and levels →
     * {@link CgTextShaper#shape} per run → collect into logical-order list.</p>
     */
    private List<CgShapedRun> splitAndShapeRuns(String text, CgFontKey fontKey, HBFont hbFont) {
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        int runCount = bidi.getRunCount();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>(runCount);

        for (int i = 0; i < runCount; i++) {
            int start = bidi.getRunStart(i);
            int end = bidi.getRunLimit(i);
            int level = bidi.getRunLevel(i);
            boolean rtl = (level % 2) != 0;

            CgShapedRun run = shaper.shape(text, start, end, fontKey, rtl, hbFont);
            runs.add(run);
        }

        return runs;
    }
}
