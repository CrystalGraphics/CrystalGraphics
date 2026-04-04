package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBFont;
import io.github.somehussar.crystalgraphics.text.CgLineBreaker;
import io.github.somehussar.crystalgraphics.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.text.CgTextShaper;

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
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (font == null || font.isDisposed()) {
            throw new IllegalArgumentException("font must not be null or disposed");
        }

        CgFontKey fontKey = font.getKey();
        CgFontMetrics metrics = font.getMetrics();
        HBFont hbFont = font.getHbFontInternal();

        if (text.isEmpty()) {
            List<List<CgShapedRun>> emptyLines = new ArrayList<List<CgShapedRun>>();
            return new CgTextLayout(emptyLines, 0, 0, metrics);
        }

        // 1. BiDi paragraph analysis
        List<CgShapedRun> shapedRuns = splitAndShapeRuns(text, fontKey, hbFont);

        // 2. Line breaking with per-line visual reorder
        List<List<CgShapedRun>> lines = lineBreaker.breakLines(shapedRuns, maxWidth, maxHeight, metrics);

        // 3. Measure total width and height
        float totalWidth = 0;
        for (List<CgShapedRun> line : lines) {
            float lineWidth = 0;
            for (CgShapedRun run : line) {
                lineWidth += run.getTotalAdvance();
            }
            if (lineWidth > totalWidth) {
                totalWidth = lineWidth;
            }
        }
        float totalHeight = lines.size() * metrics.getLineHeight();

        return new CgTextLayout(lines, totalWidth, totalHeight, metrics);
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
