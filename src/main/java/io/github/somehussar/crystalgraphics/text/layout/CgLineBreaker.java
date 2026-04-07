package io.github.somehussar.crystalgraphics.text.layout;

import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;

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
     * Break shaped runs into visual lines with optional intra-run word wrapping.
     *
     * <p>When a run overflows and carries source-text context, the breaker scans
     * the source text for break opportunities (space, ZWSP, soft hyphen), re-shapes
     * the fragments via the {@code reshaper}, and splits across lines. If no reshaper
     * is provided or the run lacks source context, whole-run wrapping is used.</p>
     *
     * @param runs       shaped runs in logical (paragraph) order
     * @param maxWidth   maximum line width in pixels; {@code <= 0} means unbounded
     * @param maxHeight  maximum total layout height in pixels; {@code <= 0} means unbounded
     * @param metrics    font metrics for line height calculation
     * @param reshaper   callback for re-shaping run fragments; may be {@code null}
     * @return list of lines; each line is a list of {@link CgShapedRun} in visual order
     */
    public List<List<CgShapedRun>> breakLines(List<CgShapedRun> runs,
                                               float maxWidth, float maxHeight,
                                               CgFontMetrics metrics,
                                               RunReshaper reshaper) {
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
            float remainingWidth = maxWidth > 0 ? maxWidth - currentWidth : Float.MAX_VALUE;

            if (maxWidth > 0 && run.getTotalAdvance() > remainingWidth && !currentLine.isEmpty()) {
                // Current line is full — finalize it and start a new line
                lines.add(reorderVisually(currentLine));
                totalHeight += lineHeight;
                if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                    return lines;
                }
                currentLine = new ArrayList<CgShapedRun>();
                currentWidth = 0.0f;
                remainingWidth = maxWidth;
            }

            // Try intra-run splitting if the run still overflows the (possibly fresh) line
            if (maxWidth > 0 && run.getTotalAdvance() > remainingWidth
                    && run.hasSourceContext() && reshaper != null) {
                List<CgShapedRun> fragments = splitRunAtBreakOpportunities(
                        run, remainingWidth, maxWidth, reshaper);

                if (fragments != null && fragments.size() > 1) {
                    for (CgShapedRun fragment : fragments) {
                        float fragRemaining = maxWidth > 0 ? maxWidth - currentWidth : Float.MAX_VALUE;
                        if (maxWidth > 0 && fragment.getTotalAdvance() > fragRemaining
                                && !currentLine.isEmpty()) {
                            lines.add(reorderVisually(currentLine));
                            totalHeight += lineHeight;
                            if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                                return lines;
                            }
                            currentLine = new ArrayList<CgShapedRun>();
                            currentWidth = 0.0f;
                        }
                        if (fragment.getGlyphIds().length > 0) {
                            currentLine.add(fragment);
                            currentWidth += fragment.getTotalAdvance();
                        }
                    }
                    continue;
                }
            }

            // Whole-run placement (fallback)
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
     * Break shaped runs into visual lines.
     *
     * <p>Backward-compatible overload without intra-run reshaping support.
     * BiDi reordering is applied per line from the logical runs.</p>
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
        return breakLines(runs, maxWidth, maxHeight, metrics, null);
    }

    /**
     * Attempts to split a run at word-break opportunities within its source text.
     *
     * <p>Scans the source text segment for break characters (space, ZWSP, soft hyphen)
     * and finds the last break position where the re-shaped prefix fits within
     * {@code availableWidth}. If a valid split is found, returns two or more
     * re-shaped fragments. Returns {@code null} if no valid break was found.</p>
     *
     * <p>Break positions are always <em>after</em> the break character (i.e., the
     * space stays at the end of the first fragment). This respects cluster boundaries
     * because the split happens in the source text, not in the glyph array.</p>
     *
     * @param run            the run to split (must have source context)
     * @param availableWidth remaining width on the current line
     * @param maxLineWidth   full line width for subsequent fragments
     * @param reshaper       callback to re-shape text sub-ranges
     * @return list of re-shaped fragments, or {@code null} if no break found
     */
    private List<CgShapedRun> splitRunAtBreakOpportunities(CgShapedRun run,
                                                            float availableWidth,
                                                            float maxLineWidth,
                                                            RunReshaper reshaper) {
        String sourceText = run.getSourceText();
        int runStart = run.getSourceStart();
        int runEnd = run.getSourceEnd();
        String segment = sourceText.substring(runStart, runEnd);

        // Scan for break opportunities: find the rightmost break position
        // where the re-shaped prefix fits within availableWidth.
        // Break opportunities are *after* break characters.
        int bestBreak = -1;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (isBreakOpportunity(c)) {
                int breakPos = i + 1; // break *after* the break character
                if (breakPos >= segment.length()) {
                    continue; // no point splitting at the very end
                }
                CgShapedRun prefix = reshaper.reshape(run, runStart, runStart + breakPos);
                if (prefix != null && prefix.getTotalAdvance() <= availableWidth) {
                    bestBreak = breakPos;
                } else if (prefix != null && prefix.getTotalAdvance() > availableWidth) {
                    // Past the available width — stop scanning
                    break;
                }
            }
        }

        if (bestBreak <= 0) {
            return null;
        }

        // Re-shape the two fragments
        List<CgShapedRun> fragments = new ArrayList<CgShapedRun>();
        CgShapedRun head = reshaper.reshape(run, runStart, runStart + bestBreak);
        if (head != null) {
            fragments.add(head);
        }

        // Recursively split the tail if it also overflows
        int tailStart = runStart + bestBreak;
        if (tailStart < runEnd) {
            CgShapedRun tail = reshaper.reshape(run, tailStart, runEnd);
            if (tail != null) {
                if (tail.getTotalAdvance() > maxLineWidth && tail.hasSourceContext()) {
                    List<CgShapedRun> tailFragments = splitRunAtBreakOpportunities(
                            tail, maxLineWidth, maxLineWidth, reshaper);
                    if (tailFragments != null) {
                        fragments.addAll(tailFragments);
                    } else {
                        fragments.add(tail);
                    }
                } else {
                    fragments.add(tail);
                }
            }
        }

        return fragments.size() > 1 ? fragments : null;
    }

    /**
     * Returns {@code true} if the character is a valid word-break opportunity.
     */
    private static boolean isBreakOpportunity(char c) {
        return c == ' ' || c == '\u200B' || c == '\u00AD';
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
            return new ArrayList<>(logicalRuns);
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
            return new ArrayList<>(logicalRuns);
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
