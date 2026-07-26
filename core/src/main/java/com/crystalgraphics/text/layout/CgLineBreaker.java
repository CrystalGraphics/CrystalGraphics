package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
 *   <li>When a run overflows, split it at real Unicode line-break boundaries
 *       ({@link BreakIterator#getLineInstance}, UAX #14) — the largest fitting
 *       prefix is found via binary search rather than linear scan, since a full
 *       re-shape is required to measure each candidate. If no word-level boundary
 *       produces a fitting prefix at all (a single token wider than the line on
 *       its own), fall back to forced grapheme-cluster-level breaking
 *       ({@link BreakIterator#getCharacterInstance}) so the token still gets
 *       sliced to fit — never severing a combining-character sequence or
 *       surrogate pair. Word-level wrapping resumes immediately for whatever
 *       text follows the forced break.</li>
 *   <li>After gathering all logical runs for a line, apply per-line BiDi visual
 *       reordering using {@link Bidi#reorderVisually}.</li>
 *   <li>Stop adding lines when total height exceeds {@code maxHeight} (if positive).</li>
 * </ol>
 *
 * <h3>Break opportunities</h3>
 * <p>Word-level break opportunities are whatever {@link BreakIterator#getLineInstance}
 * reports for the {@linkplain Locale#ROOT root locale} (UAX #14 — spaces, hyphens,
 * CJK ideograph boundaries, punctuation classes, etc., not a hardcoded character
 * list). When none of those fit, grapheme-cluster boundaries from
 * {@link BreakIterator#getCharacterInstance} are used instead, guaranteeing
 * {@code maxWidth} is respected even for a single unbreakable token.</p>
 *
 * @see CgShapedRun
 * @see CgTextLayout
 */
public class CgLineBreaker {

    /**
     * Break shaped runs into visual lines with optional intra-run word wrapping.
     *
     * <p>When a run overflows and carries source-text context, the breaker splits
     * it at Unicode line-break boundaries (falling back to forced grapheme-cluster
     * breaking when nothing fits), re-shapes the fragments via the {@code reshaper},
     * and places them across lines. If no reshaper is provided or the run lacks
     * source context, whole-run wrapping is used.</p>
     *
     * @param runs       shaped runs in logical (paragraph) order
     * @param maxWidth   maximum line width in pixels; {@code <= 0} means unbounded
     * @param maxHeight  maximum total layout height in pixels; {@code <= 0} means unbounded
     * @param metrics    font metrics for line height calculation
     * @param context    the paragraph's source-text context; may be {@code null} if
     *                   {@code reshaper} is also {@code null}
     * @param reshaper   callback for re-shaping run fragments; may be {@code null}
     * @return list of lines; each line is a list of {@link CgShapedRun} in visual order
     */
    public List<List<CgShapedRun>> breakLines(List<CgShapedRun> runs,
                                               float maxWidth, float maxHeight,
                                               CgFontMetrics metrics,
                                               CgReshapeContext context,
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
                    && context != null && reshaper != null) {
                List<CgShapedRun> fragments = splitAtLineBreaks(
                        context, run, remainingWidth, maxWidth, reshaper);

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
        return breakLines(runs, maxWidth, maxHeight, metrics, null, null);
    }

    /**
     * Splits a run at Unicode line-break boundaries (UAX #14, via
     * {@link BreakIterator#getLineInstance}). Finds the largest boundary whose
     * re-shaped prefix still fits {@code availableWidth} via binary search — width
     * is assumed to grow monotonically with included text, the same assumption
     * production line breakers make for this purpose.
     *
     * <p>Falls back to {@link #splitAtGraphemeBreaks} when no word-level boundary
     * fits (including when the segment has no line-break boundaries at all, e.g.
     * a single unbroken token).</p>
     *
     * @param context        the paragraph's source-text context
     * @param run            the run to split
     * @param availableWidth remaining width on the current line
     * @param maxLineWidth   full line width for subsequent fragments
     * @param reshaper       callback to re-shape text sub-ranges
     * @return list of re-shaped fragments, or {@code null} if no split was possible
     */
    private List<CgShapedRun> splitAtLineBreaks(CgReshapeContext context, CgShapedRun run,
                                                 float availableWidth,
                                                 float maxLineWidth,
                                                 RunReshaper reshaper) {
        String sourceText = context.sourceText();
        int runStart = run.getSourceStart();
        int runEnd = run.getSourceEnd();
        String segment = sourceText.substring(runStart, runEnd);
        if (segment.isEmpty()) {
            return null;
        }

        int[] boundaries = collectBoundaries(BreakIterator.getLineInstance(Locale.ROOT), segment);
        int best = findBestFittingBoundary(context, boundaries, run, runStart, availableWidth, reshaper);

        if (best <= 0) {
            // Nothing at word granularity fits — a single token is wider than the line
            // on its own. Force a character-level break instead of giving up entirely.
            return splitAtGraphemeBreaks(context, run, availableWidth, maxLineWidth, reshaper);
        }

        List<CgShapedRun> fragments = buildFragments(context, run, runStart, runEnd, best, maxLineWidth, reshaper);
        return fragments.size() > 1 ? fragments : null;
    }

    /**
     * Forces a break at a grapheme-cluster boundary ({@link BreakIterator#getCharacterInstance})
     * when no word-level break fits — guarantees {@code maxWidth} is respected even for a
     * single unbreakable token, at the cost of splitting mid-word. Never severs a
     * combining-character sequence or surrogate pair, since grapheme-cluster boundaries
     * are exactly the positions where doing so is safe.
     *
     * <p>If even a single grapheme cluster doesn't fit {@code availableWidth}, it is placed
     * anyway — an empty fragment would make no progress, and a one-cluster-wide overflow is
     * the unavoidable minimum once a line is narrower than a single glyph.</p>
     *
     * @return list of re-shaped fragments, or {@code null} if the segment is a single
     *         atomic grapheme cluster with no boundary to split at at all
     */
    private List<CgShapedRun> splitAtGraphemeBreaks(CgReshapeContext context, CgShapedRun run,
                                                      float availableWidth,
                                                      float maxLineWidth,
                                                      RunReshaper reshaper) {
        String sourceText = context.sourceText();
        int runStart = run.getSourceStart();
        int runEnd = run.getSourceEnd();
        String segment = sourceText.substring(runStart, runEnd);

        int[] boundaries = collectBoundaries(BreakIterator.getCharacterInstance(Locale.ROOT), segment);
        if (boundaries.length == 0) {
            return null;
        }

        int best = findBestFittingBoundary(context, boundaries, run, runStart, availableWidth, reshaper);
        if (best <= 0) {
            best = boundaries[0];
        }

        List<CgShapedRun> fragments = buildFragments(context, run, runStart, runEnd, best, maxLineWidth, reshaper);
        return fragments.size() > 1 ? fragments : null;
    }

    /**
     * Re-shapes the head/tail fragments at {@code breakPos} (absolute index into the run's
     * source text). Recurses back into {@link #splitAtLineBreaks} for the tail if it still
     * overflows a fresh line — so a forced grapheme break doesn't force everything after it
     * to also break mid-word; normal word wrapping resumes as soon as it can.
     */
    private List<CgShapedRun> buildFragments(CgReshapeContext context, CgShapedRun run,
                                              int runStart, int runEnd, int breakPos,
                                              float maxLineWidth, RunReshaper reshaper) {
        List<CgShapedRun> fragments = new ArrayList<CgShapedRun>();
        CgShapedRun head = reshaper.reshape(context, run, runStart, runStart + breakPos);
        if (head != null) {
            fragments.add(head);
        }

        int tailStart = runStart + breakPos;
        if (tailStart < runEnd) {
            CgShapedRun tail = reshaper.reshape(context, run, tailStart, runEnd);
            if (tail != null) {
                if (tail.getTotalAdvance() > maxLineWidth) {
                    List<CgShapedRun> tailFragments = splitAtLineBreaks(context, tail, maxLineWidth, maxLineWidth, reshaper);
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

        return fragments;
    }

    /**
     * Binary-searches {@code boundaries} (sorted ascending) for the largest offset whose
     * re-shaped prefix {@code [runStart, runStart+boundary)} fits within {@code availableWidth}.
     * Assumes prefix width grows monotonically with boundary position.
     *
     * @return the largest fitting boundary, or {@code -1} if even the first one doesn't fit
     */
    private int findBestFittingBoundary(CgReshapeContext context, int[] boundaries, CgShapedRun run, int runStart,
                                         float availableWidth, RunReshaper reshaper) {
        int lo = 0, hi = boundaries.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int breakPos = boundaries[mid];
            CgShapedRun prefix = reshaper.reshape(context, run, runStart, runStart + breakPos);
            float width = prefix != null ? prefix.getTotalAdvance() : Float.MAX_VALUE;
            if (width <= availableWidth) {
                best = breakPos;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /**
     * Collects break boundaries from a {@link BreakIterator} over {@code segment}, excluding
     * the boundary at {@code segment.length()} (there's no point splitting at the very end —
     * nothing follows) and the implicit boundary at 0 (breaking before any text is useless).
     * Each returned offset is a position where {@code segment} may be split, matching the
     * "break *after* this position" convention {@link #buildFragments} expects.
     */
    private static int[] collectBoundaries(BreakIterator iterator, String segment) {
        iterator.setText(segment);
        List<Integer> boundaries = new ArrayList<Integer>();
        iterator.first();
        int boundary = iterator.next();
        while (boundary != BreakIterator.DONE) {
            if (boundary < segment.length()) {
                boundaries.add(boundary);
            }
            boundary = iterator.next();
        }
        int[] result = new int[boundaries.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = boundaries.get(i);
        }
        return result;
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
