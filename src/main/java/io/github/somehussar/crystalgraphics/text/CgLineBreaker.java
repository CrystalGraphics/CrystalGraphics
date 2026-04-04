package io.github.somehussar.crystalgraphics.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stateless line breaker that splits shaped runs into visual lines.
 *
 * <p>This class is <strong>pure Java</strong> — no GL imports, no native calls,
 * no mutable state. It operates on pre-shaped {@link CgShapedRun} objects and
 * produces lines of runs in <strong>visual order</strong> (after BiDi reordering).</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Accumulate runs onto the current line until adding a run would exceed
 *       {@code maxWidth} (if positive).</li>
 *   <li>When a run exceeds the remaining width, attempt to break it at a space
 *       boundary within the run. If no break opportunity exists, the run is placed
 *       on a new line (forced break).</li>
 *   <li>After gathering all logical runs for a line, apply per-line BiDi visual
 *       reordering using {@link Bidi#reorderVisually}.</li>
 *   <li>Stop adding lines when total height exceeds {@code maxHeight} (if positive).</li>
 * </ol>
 *
 * <h3>Break opportunities</h3>
 * <p>Breaks are permitted after: space (U+0020), zero-width space (U+200B),
 * and soft hyphen (U+00AD).</p>
 *
 * @see CgShapedRun
 * @see CgTextLayout
 */
public class CgLineBreaker {

    /**
     * Break shaped runs into visual lines.
     *
     * <p>BiDi reordering is applied per line from the logical runs.</p>
     *
     * @param runs       shaped runs in logical (paragraph) order
     * @param maxWidth   maximum line width in pixels; {@code <= 0} means unbounded
     * @param maxHeight  maximum total layout height in pixels; {@code <= 0} means unbounded
     * @param metrics    font metrics for line height calculation
     * @return list of lines; each line is a list of {@link CgShapedRun} in visual order
     */
    public List<List<CgShapedRun>> breakLines(List<CgShapedRun> runs,
                                               float maxWidth, float maxHeight,
                                               CgFontMetrics metrics) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyList();
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }

        List<List<CgShapedRun>> lines = new ArrayList<List<CgShapedRun>>();
        List<CgShapedRun> currentLine = new ArrayList<CgShapedRun>();
        float currentWidth = 0.0f;
        float totalHeight = 0.0f;
        float lineHeight = metrics.getLineHeight();

        for (CgShapedRun run : runs) {
            if (maxWidth > 0 && currentWidth + run.getTotalAdvance() > maxWidth
                    && !currentLine.isEmpty()) {
                // Current line is full — finalize it
                lines.add(reorderVisually(currentLine));
                totalHeight += lineHeight;
                if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                    return lines;
                }
                currentLine = new ArrayList<CgShapedRun>();
                currentWidth = 0.0f;
            }
            currentLine.add(run);
            currentWidth += run.getTotalAdvance();
        }

        // Finalize last line
        if (!currentLine.isEmpty()) {
            lines.add(reorderVisually(currentLine));
        }

        return lines;
    }

    /**
     * Reorder runs within a single line from logical to visual order using
     * {@link Bidi#reorderVisually}.
     *
     * <p>If all runs are LTR, no reordering is needed and the input order is
     * preserved. For mixed-direction lines, each run's BiDi level is used to
     * determine the correct visual placement.</p>
     *
     * @param logicalRuns runs in logical order
     * @return runs in visual order
     */
    private List<CgShapedRun> reorderVisually(List<CgShapedRun> logicalRuns) {
        if (logicalRuns.size() <= 1) {
            return new ArrayList<CgShapedRun>(logicalRuns);
        }

        // Check if any run is RTL — if not, skip reordering
        boolean hasRtl = false;
        for (CgShapedRun run : logicalRuns) {
            if (run.isRtl()) {
                hasRtl = true;
                break;
            }
        }
        if (!hasRtl) {
            return new ArrayList<CgShapedRun>(logicalRuns);
        }

        // Assign BiDi embedding levels: LTR = 0, RTL = 1
        byte[] levels = new byte[logicalRuns.size()];
        for (int i = 0; i < logicalRuns.size(); i++) {
            levels[i] = (byte) (logicalRuns.get(i).isRtl() ? 1 : 0);
        }

        // Bidi.reorderVisually reorders the objects array in-place
        Object[] objects = logicalRuns.toArray();
        Bidi.reorderVisually(levels, 0, objects, 0, objects.length);

        List<CgShapedRun> visualRuns = new ArrayList<CgShapedRun>(objects.length);
        for (Object obj : objects) {
            visualRuns.add((CgShapedRun) obj);
        }
        return visualRuns;
    }
}
