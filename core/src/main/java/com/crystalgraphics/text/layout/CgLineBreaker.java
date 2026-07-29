package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.util.profiling.CgProfiler;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
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

            if (maxWidth > 0 && run.totalAdvance() > remainingWidth) {
                // Try to split the run against the CURRENT line's remaining space first —
                // finalizing the line before even attempting a split (the old behavior) threw
                // away however much of the run would still have fit, leaving the line short of
                // maxWidth whenever a multi-run paragraph (styled/BiDi-split text) handed us a
                // large run while the current line still had room left.
                // allowGraphemeFallback only when the current line is already empty -- then
                // remainingWidth == maxWidth, i.e. this attempt already IS against a fresh
                // line, so a forced mid-word split here is the genuine last resort (a single
                // token wider than a whole line). When the line already has content,
                // remainingWidth is just whatever's left over; forcing a mid-word split to
                // grab that small sliver instead of moving the whole word to a fresh line
                // (where it likely fits fine on its own) would split a word that didn't need
                // to be split at all -- that case defers to the fresh-line retry below.
                boolean allowGraphemeFallback = currentLine.isEmpty();
                List<CgShapedRun> fragments = (context != null && reshaper != null)
                        ? splitAtLineBreaks(context, run, remainingWidth, maxWidth, reshaper, allowGraphemeFallback)
                        : null;

                if ((fragments == null || fragments.size() <= 1) && !currentLine.isEmpty()) {
                    // Nothing from this run fits the remaining space — finalize the current
                    // line and retry the split against a fresh, full-width line.
                    lines.add(reorderVisually(currentLine));
                    totalHeight += lineHeight;
                    if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                        return lines;
                    }
                    currentLine = new ArrayList<CgShapedRun>();
                    currentWidth = 0.0f;
                    remainingWidth = maxWidth;
                    if (context != null && reshaper != null && run.totalAdvance() > remainingWidth) {
                        fragments = splitAtLineBreaks(context, run, remainingWidth, maxWidth, reshaper, true);
                    }
                }

                if (fragments != null && fragments.size() > 1) {
                    for (CgShapedRun fragment : fragments) {
                        float fragRemaining = maxWidth > 0 ? maxWidth - currentWidth : Float.MAX_VALUE;
                        if (maxWidth > 0 && fragment.totalAdvance() > fragRemaining
                                && !currentLine.isEmpty()) {
                            lines.add(reorderVisually(currentLine));
                            totalHeight += lineHeight;
                            if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                                return lines;
                            }
                            currentLine = new ArrayList<CgShapedRun>();
                            currentWidth = 0.0f;
                        }
                        if (fragment.glyphIds().length > 0) {
                            currentLine.add(fragment);
                            currentWidth += fragment.totalAdvance();
                        }
                    }
                    continue;
                }
            }

            // Whole-run placement: the run fits as-is, or no splitting was possible/available.
            currentLine.add(run);
            currentWidth += run.totalAdvance();
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
     * <p>Falls back to {@link #splitAtGraphemeBreaks} when no word-level boundary fits AND
     * {@code allowGraphemeFallback} is {@code true} (including when the segment has no
     * line-break boundaries at all, e.g. a single unbroken token) — see that parameter's
     * doc for why this is conditional.</p>
     *
     * @param context               the paragraph's source-text context
     * @param run                   the run to split
     * @param availableWidth        remaining width on the current line
     * @param maxLineWidth          full line width for subsequent fragments
     * @param reshaper              callback to re-shape text sub-ranges
     * @param allowGraphemeFallback whether to force a mid-word character split when no word
     *                              boundary fits {@code availableWidth}. Pass {@code true}
     *                              only when {@code availableWidth} is itself a full, fresh
     *                              line's width — that is the actual "last resort" case (the
     *                              token doesn't fit a whole line on its own). Pass
     *                              {@code false} when {@code availableWidth} is merely
     *                              whatever's left over on an already-partially-filled line:
     *                              forcing a mid-word break there to grab a small leftover
     *                              sliver, instead of moving the whole token to a fresh line
     *                              where it likely fits fine, splits a word that didn't need
     *                              to be split at all.
     * @return list of re-shaped fragments, or {@code null} if no split was possible
     */
    private List<CgShapedRun> splitAtLineBreaks(CgReshapeContext context, CgShapedRun run,
                                                 float availableWidth,
                                                 float maxLineWidth,
                                                 RunReshaper reshaper,
                                                 boolean allowGraphemeFallback) {
        String sourceText = context.sourceText();
        int runStart = run.sourceStart();
        int runEnd = run.sourceEnd();
        String segment = sourceText.substring(runStart, runEnd);
        if (segment.isEmpty()) {
            return null;
        }

        int[] boundaries;
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.collectBoundaries")) {
            boundaries = collectBoundaries(LINE_ITERATOR.get(), segment);
        }
        CgProfiler.count("lineBreak.splitCalls");
        CgProfiler.count("lineBreak.segmentChars", segment.length());
        int best;
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.findBoundary")) {
            best = findBestFittingBoundary(context, boundaries, run, runStart, availableWidth, reshaper);
        }

        if (best <= 0) {
            // Nothing at word granularity fits. Only force a character-level break when this
            // is genuinely a last resort (see allowGraphemeFallback's doc) — otherwise defer
            // to the whole-run relocation path, which moves the token to a fresh line intact.
            if (!allowGraphemeFallback) return null;
            // Counted, deliberately NOT scoped: this path recurses back through buildFragments into
            // splitAtLineBreaks, so a scope here re-enters itself and its total double-counts
            // (measured 154.9 ms against a 30.4 ms parent). The count is the diagnostic that
            // matters anyway -- it says how often word-level breaking failed and a whole second,
            // character-level breaking pass had to run.
            //
            // Worth knowing what a high ratio means: it is a property of the TEXT, not a defect.
            // Prose breaks at word boundaries and never comes here. Text with almost no break
            // opportunities -- CJK, symbol ranges, one enormous token -- comes here on nearly every
            // line. A 3363-char string containing a single space measured 105 fallbacks across 133
            // splits and 30 ms of breakLines, while 1000 realistically-wrapped Latin labels
            // measured 0.748 ms total. Check this counter before concluding line breaking is slow.
            CgProfiler.count("lineBreak.graphemeFallbacks");
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
        int runStart = run.sourceStart();
        int runEnd = run.sourceEnd();
        String segment = sourceText.substring(runStart, runEnd);

        // Slice of the paragraph-wide boundary set, not a fresh scan of this segment -- see
        // CgReshapeContext#graphemeBoundaries for why that is equivalent and why it matters.
        int[] all;
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.graphemeCollect")) {
            all = context.graphemeBoundaries();
        }
        int from = lowerBound(all, runStart + 1); // strictly after the segment start
        int to = lowerBound(all, runEnd);         // strictly before the segment end
        CgProfiler.count("lineBreak.graphemeBoundaries", to - from);
        if (to <= from) {
            return null;
        }

        int best;
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.graphemeFind")) {
            best = findBestFittingBoundary(context, all, from, to, runStart, run, runStart, availableWidth, reshaper);
        }
        if (best <= 0) {
            best = all[from] - runStart;
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

        // When HarfBuzz has told us this boundary is safe to break at, the already-shaped glyphs
        // on either side are exactly what a fresh shape would produce -- that is precisely what
        // HB_GLYPH_FLAG_UNSAFE_TO_BREAK being absent means -- so the run can be sliced instead of
        // re-shaped. measurePrefixWidth already takes this path when *measuring* candidate
        // boundaries; this extends it to actually building the fragments, which was still paying
        // two HarfBuzz calls per split. Measured at 1000 wrapped labels, breakLines was 43% of a
        // reshape and larger than shaping itself.
        int splitGlyph;
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.safeGlyphBoundary")) {
            splitGlyph = sliceFastPathDisabled ? -1 : safeGlyphBoundary(context, run, runStart, breakPos);
        }

        // RTL glyphs are in visual order, so the logical prefix is the UPPER part of the array and
        // the suffix the lower — the mirror image of the LTR split. Getting this backwards would
        // silently render the two halves of every wrapped RTL line in the wrong places.
        boolean rtl = run.rtl();
        int glyphCount = run.glyphIds().length;
        CgShapedRun head;
        if (splitGlyph >= 0) {
            try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.sliceRun")) {
                head = rtl ? sliceRun(run, splitGlyph, glyphCount, runStart, runStart + breakPos)
                           : sliceRun(run, 0, splitGlyph, runStart, runStart + breakPos);
            }
        } else {
            // The slow half of the split, and previously unscoped -- which made it invisible and
            // let its cost be attributed to sliceRun by subtraction. A fragment reshape is a full
            // HarfBuzz call, so a handful of these outweighs thousands of array copies.
            CgProfiler.count("lineBreak.fragmentReshapes");
            try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.fragmentReshape")) {
                head = reshaper.reshape(context, run, runStart, runStart + breakPos);
            }
        }
        if (head != null) {
            fragments.add(head);
        }

        int tailStart = runStart + breakPos;
        if (tailStart < runEnd) {
            CgShapedRun tail;
            if (splitGlyph >= 0) {
                try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.sliceRun")) {
                    tail = rtl ? sliceRun(run, 0, splitGlyph, tailStart, runEnd)
                               : sliceRun(run, splitGlyph, glyphCount, tailStart, runEnd);
                }
            } else {
                CgProfiler.count("lineBreak.fragmentReshapes");
                try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.fragmentReshape")) {
                    tail = reshaper.reshape(context, run, tailStart, runEnd);
                }
            }
            if (tail != null) {
                if (tail.totalAdvance() > maxLineWidth) {
                    // maxLineWidth here IS a full, fresh line's width (the tail starts a new
                    // line), so grapheme fallback is the genuine last resort -- true.
                    List<CgShapedRun> tailFragments = splitAtLineBreaks(context, tail, maxLineWidth, maxLineWidth, reshaper, true);
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
     * Glyph index at {@code breakPos} if the run can be <em>sliced</em> there instead of
     * re-shaped, else {@code -1}.
     *
     * <p>Same conditions {@link #measurePrefixWidth} uses for its measuring fast path, and for the
     * same reasons:
     * <ul>
     *   <li><b>LTR only.</b> HarfBuzz emits LTR glyphs in source order, so a prefix of the glyph
     *       array corresponds to a prefix of the text. RTL glyph order is visual, so the same
     *       slice would take the wrong glyphs.</li>
     *   <li><b>Exact cluster boundary.</b> The break must land on a glyph's cluster start, or the
     *       split would cut a cluster in half.</li>
     *   <li><b>Marked safe.</b> If HarfBuzz flagged the glyph unsafe-to-break, shaping across the
     *       boundary is context-dependent (ligatures, kerning) and slicing would produce different
     *       glyphs than a real re-shape.</li>
     * </ul>
     */
    /**
     * Test-only escape hatch forcing every split <em>and every width measurement</em> through
     * {@code reshaper}, bypassing {@link #sliceRun} and {@link #measurePrefixWidth}'s fast path.
     *
     * <p>Exists so a test can wrap identical text both ways and assert the two produce identical
     * layouts — the only way to show these paths are correct rather than merely faster. It
     * deliberately gates both: measurement decides *which* boundary is chosen, so a wrong fast
     * measurement changes the resulting layout just as surely as a wrong slice, and a hatch that
     * covered only slicing would leave half the risk untested.
     *
     * <p>Never set outside tests.
     */
    static boolean sliceFastPathDisabled = false;

    private static int safeGlyphBoundary(CgReshapeContext context, CgShapedRun run,
                                         int runStart, int breakPos) {
        boolean[] safeToBreakBefore = run.safeToBreakBefore();
        int[] clusterIds = run.clusterIds();
        if (safeToBreakBefore == null || clusterIds == null) {
            return -1;
        }

        String runText = context.sourceText().substring(runStart, run.sourceEnd());
        int[] byteToChar = Utf8ClusterMapper.byteOffsetToCharOffset(runText);
        int glyphIndex = run.rtl()
                ? rtlGlyphIndexAtCharOffset(clusterIds, byteToChar, breakPos)
                : glyphIndexAtCharOffset(clusterIds, byteToChar, breakPos);
        if (glyphIndex <= 0 || glyphIndex >= clusterIds.length) {
            // 0 would make an empty head; past the end makes an empty tail. Both mean there is
            // nothing to split, so fall back rather than fabricate a zero-glyph fragment.
            return -1;
        }
        // HarfBuzz's unsafe-to-break flag belongs to the glyph whose cluster *starts* at the break
        // offset. Under LTR that glyph begins the tail and sits at glyphIndex. Under RTL the array
        // is visually ordered, so the glyph starting that cluster is the top of the suffix — one
        // below the split. Checking glyphIndex there tests the wrong boundary, which is what made
        // sliced RTL diverge from a real reshape by a glyph's advance.
        int flagIndex = run.rtl() ? glyphIndex - 1 : glyphIndex;
        return safeToBreakBefore[flagIndex] ? glyphIndex : -1;
    }

    /**
     * RTL counterpart of {@link #glyphIndexAtCharOffset}.
     *
     * <p>HarfBuzz emits RTL glyphs in <em>visual</em> order, so cluster ids run downward as the
     * glyph index rises: the logically-first character is the last glyph in the array. A logical
     * break therefore splits the array the other way round from LTR — everything at and above the
     * returned index is the logical <em>prefix</em>, everything below it the suffix.
     *
     * <p>Returns the lowest glyph index whose cluster is still below {@code breakPos} in byte
     * terms, which is the first glyph belonging to the logical prefix. Requires an exact cluster
     * boundary for the same reason LTR does — landing mid-cluster would cut a cluster in half.
     */
    private static int rtlGlyphIndexAtCharOffset(int[] clusterIds, int[] byteToChar, int charOffset) {
        int index = -1;
        for (int i = clusterIds.length - 1; i >= 0; i--) {
            int cluster = clusterIds[i];
            if (cluster < 0 || cluster >= byteToChar.length) {
                return -1;
            }
            int charAt = byteToChar[cluster];
            if (charAt < charOffset) {
                index = i;
            } else if (charAt == charOffset) {
                // Exact boundary: this glyph starts the suffix, so the prefix begins one above it.
                return index;
            } else {
                break;
            }
        }
        return -1;
    }

    /**
     * Builds a fragment from {@code run}'s glyphs in {@code [glyphFrom, glyphTo)} without calling
     * HarfBuzz. Only valid at a boundary {@link #safeGlyphBoundary} approved.
     *
     * <p>Carries over every field a re-shaped fragment would have — including the rich-span data
     * ({@code argbColor}, decorations, features, baseline shift, synthetic bold/italic) that
     * {@code createRunReshaper} has to copy manually after shaping. Because this slices the source
     * run it inherits them directly, and keeps the same {@code resolvedFont} that actually produced
     * these glyphs.
     *
     * <p><b>Cluster ids are rebased.</b> A shaped run's clusters are UTF-8 byte offsets relative to
     * that run's own start, which is what a re-shaped fragment produces. Slicing without
     * subtracting the split point's offset would leave the tail's clusters relative to the original
     * run instead, and everything downstream that maps glyphs back to source text would be wrong.
     */
    private static CgShapedRun sliceRun(CgShapedRun run, int glyphFrom, int glyphTo,
                                        int sourceStart, int sourceEnd) {
        int count = glyphTo - glyphFrom;
        // Counted because the recursive head/tail split makes total copied glyphs quadratic in run
        // length if the tail is re-sliced on every line: comparing this against the run's actual
        // glyph count is what distinguishes "slicing is inherently the cost" from "we are copying
        // the same tail over and over".
        CgProfiler.count("lineBreak.slicedGlyphs", count);
        CgProfiler.count("lineBreak.sliceCalls");

        // Source arrays hoisted out of the copy loop. Five of the six are copied verbatim, so they
        // go through Arrays.copyOfRange (System.arraycopy, an intrinsic) rather than an
        // element-at-a-time loop that re-invoked five accessors per glyph.
        //
        // Worth doing because this method copies far more than it looks: buildFragments recurses on
        // the tail, so a paragraph wrapped into N lines re-copies the shrinking remainder N times.
        // Measured on a 3363-char paragraph: 66,636 glyphs copied for 6,726 real ones, 9.9x
        // amplification. This does not remove that amplification — it makes each copy cheap. The
        // quadratic itself needs buildFragments to iterate over the original run with a moving
        // offset instead of slicing a fresh tail per line, which is a genuine restructure of
        // RTL-sensitive code and is tracked separately in PERFORMANCE_TODO.
        int[] srcGlyphIds = run.glyphIds();
        int[] srcClusters = run.clusterIds();
        float[] srcAdvances = run.advancesX();
        float[] srcOffsetsX = run.offsetsX();
        float[] srcOffsetsY = run.offsetsY();
        boolean[] srcSafe = run.safeToBreakBefore();

        int[] glyphIds = Arrays.copyOfRange(srcGlyphIds, glyphFrom, glyphTo);
        float[] advancesX = Arrays.copyOfRange(srcAdvances, glyphFrom, glyphTo);
        float[] offsetsX = Arrays.copyOfRange(srcOffsetsX, glyphFrom, glyphTo);
        float[] offsetsY = Arrays.copyOfRange(srcOffsetsY, glyphFrom, glyphTo);
        boolean[] safeToBreakBefore = Arrays.copyOfRange(srcSafe, glyphFrom, glyphTo);

        // Rebase by the SMALLEST cluster in the slice, which is the fragment's own logical start.
        // For LTR that is at glyphFrom, but RTL clusters descend with glyph index, so there it is
        // the last glyph in the range. Using glyphFrom unconditionally would leave every RTL tail
        // with negative cluster ids and break every glyph-to-source mapping downstream.
        int clusterBase = run.rtl() ? srcClusters[glyphTo - 1] : srcClusters[glyphFrom];
        int[] clusterIds = new int[count];
        float totalAdvance = 0f;
        for (int i = 0; i < count; i++) {
            clusterIds[i] = srcClusters[glyphFrom + i] - clusterBase;
            totalAdvance += advancesX[i];
        }

        return new CgShapedRun()
                .fontKey(run.fontKey())
                .resolvedFont(run.resolvedFont())
                .rtl(run.rtl())
                .glyphIds(glyphIds)
                .clusterIds(clusterIds)
                .advancesX(advancesX)
                .offsetsX(offsetsX)
                .offsetsY(offsetsY)
                .totalAdvance(totalAdvance)
                .sourceStart(sourceStart)
                .sourceEnd(sourceEnd)
                .safeToBreakBefore(safeToBreakBefore)
                .argbColor(run.argbColor())
                .decorations(run.decorations())
                .fontFeatures(run.fontFeatures())
                .baselineShift(run.baselineShift())
                .syntheticBold(run.syntheticBold())
                .syntheticItalic(run.syntheticItalic());
    }

    /**
     * Binary-searches {@code boundaries} (sorted ascending) for the largest offset whose
     * prefix {@code [runStart, runStart+boundary)} fits within {@code availableWidth}.
     * Assumes prefix width grows monotonically with boundary position.
     *
     * <p>Each candidate's width is normally measured by asking {@code reshaper} to actually
     * re-shape that prefix through HarfBuzz — correct, but a real re-shape call per binary-
     * search step. {@link #measurePrefixWidth} skips that call when HarfBuzz already told us
     * (via {@link CgShapedRun#safeToBreakBefore()}) that slicing the run's own already-
     * shaped glyph/advance arrays at this exact boundary is provably safe — see that
     * method's javadoc for the LTR-only scope of this fast path.</p>
     *
     * @return the largest fitting boundary, or {@code -1} if even the first one doesn't fit
     */
    /** Sums {@code advances[from, to)}. */
    private static float sumAdvances(float[] advances, int from, int to) {
        float sum = 0f;
        for (int i = from; i < to; i++) sum += advances[i];
        return sum;
    }

    /** Index of the first element of {@code sorted} that is {@code >= key}, else {@code length}. */
    private static int lowerBound(int[] sorted, int key) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Whole-array form: candidates are {@code boundaries[0..length)} already relative to
     * {@code runStart}.
     */
    private int findBestFittingBoundary(CgReshapeContext context, int[] boundaries, CgShapedRun run, int runStart,
                                         float availableWidth, RunReshaper reshaper) {
        return findBestFittingBoundary(context, boundaries, 0, boundaries.length, 0, run, runStart,
                availableWidth, reshaper);
    }

    /**
     * Sub-range form, so a caller can search a slice of a shared, paragraph-wide boundary array
     * without copying it. Candidate break positions are {@code boundaries[i] - offset}, i.e. the
     * array holds absolute offsets into the source text and {@code offset} rebases them onto the
     * run being split.
     *
     * @param from   first index to consider, inclusive
     * @param to     last index to consider, exclusive
     * @param offset subtracted from each entry to make it relative to {@code runStart}
     */
    private int findBestFittingBoundary(CgReshapeContext context, int[] boundaries, int from, int to, int offset,
                                         CgShapedRun run, int runStart,
                                         float availableWidth, RunReshaper reshaper) {
        // Built once per search, not once per step. The byte->char map depends only on
        // (sourceText, runStart, run), every one of which is fixed for the whole binary search,
        // but measurePrefixWidth used to rebuild it on each iteration -- a substring copy of the
        // entire remaining run plus a full UTF-8 scan of it, per step.
        //
        // That made line breaking superlinear in run length for no reason: ~12 steps per line and
        // one line per ~80 chars means a 3363-char paragraph rebuilt a 3363-char String and its
        // int[] map roughly 480 times. Measured on text-3d, where two such paragraphs made
        // wrap.breakLines 31.9 ms -- four times what HarfBuzz spent actually shaping the same text
        // (8.1 ms), which is backwards: breaking lines should be far cheaper than shaping them.
        // TRIED AND REVERTED: prefix-summing advancesX here to make each fast-path measurement O(1)
        // instead of O(glyphs). Asymptotically better and measurably nothing -- graphemeFind was
        // 4.83 ms before and 4.55 ms after, inside run-to-run noise -- while adding a
        // float[glyphs+1] allocation per search (~238 of them, ~13 KB each, on a cold frame).
        // The summation was never the cost; glyphIndexAtCharOffset's linear scan over clusterIds is.
        // Binary-searching that (clusterIds are monotonic, ascending for LTR and descending for RTL)
        // is the change worth making if this path ever matters.
        int[] byteToChar = null;
        if (!sliceFastPathDisabled && run.safeToBreakBefore() != null) {
            byteToChar = Utf8ClusterMapper.byteOffsetToCharOffset(
                    context.sourceText().substring(runStart, run.sourceEnd()));
        }

        int lo = from, hi = to - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int breakPos = boundaries[mid] - offset;
            float width = measurePrefixWidth(context, run, runStart, breakPos, reshaper, byteToChar);
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
     * Measures the width of {@code run}'s prefix ending {@code breakPos} chars after
     * {@code runStart}, via the unsafe-to-break fast path when possible, else by asking
     * {@code reshaper} to actually re-shape the prefix.
     *
     * <h3>Fast path — LTR only</h3>
     * <p>Applies only when {@code !run.rtl()}: HarfBuzz emits glyphs for an LTR run in the
     * same order as the source text, so summing {@code advancesX[0, glyphIndex)} for the
     * glyph exactly at the boundary gives the correct prefix width. For an RTL run, glyph
     * array order is <em>visual</em> (reversed from logical), so the same summation would
     * total the wrong subset of glyphs — RTL runs always take the re-shape path.</p>
     *
     * <p>Even for an LTR run, the fast path only applies when {@code breakPos} lands exactly
     * on a glyph boundary that HarfBuzz marked safe (via {@link CgShapedRun#safeToBreakBefore()});
     * any other case (unknown data, no exact cluster match, or the boundary marked unsafe)
     * falls back to re-shaping — so output is always identical to the non-optimized path,
     * this only ever changes how many re-shape calls that output costs.</p>
     *
     * @param byteToChar byte-offset-to-char-offset map for {@code [runStart, run.sourceEnd())},
     *                   built once per binary search by {@link #findBestFittingBoundary} — it is
     *                   invariant across the search, and rebuilding it per step is what made line
     *                   breaking superlinear in run length. {@code null} disables the fast path.
     */
    private static float measurePrefixWidth(CgReshapeContext context, CgShapedRun run, int runStart,
                                             int breakPos, RunReshaper reshaper, int[] byteToChar) {
        boolean[] safeToBreakBefore = run.safeToBreakBefore();
        if (!sliceFastPathDisabled && safeToBreakBefore != null && byteToChar != null) {
            float[] advances = run.advancesX();

            if (run.rtl()) {
                // Mirror of the LTR case, for the same reason the split itself mirrors: RTL glyphs
                // are in visual order, so the logical prefix is the TOP of the array. Without this
                // every binary-search step measuring a candidate boundary re-shaped the prefix
                // through HarfBuzz purely to learn its width — measured at 2000 extra shape calls
                // for 1000 wrapped Arabic labels, twice the shaping the text itself needed.
                int glyphIndex = rtlGlyphIndexAtCharOffset(run.clusterIds(), byteToChar, breakPos);
                int glyphCount = run.clusterIds().length;
                // glyphIndex <= 0 means no exact cluster boundary, or a prefix starting at glyph 0
                // (the whole run) — neither is a usable measurement, so fall through to a reshape.
                if (glyphIndex > 0 && safeToBreakBefore[glyphIndex - 1]) {
                    // Suffix sum [glyphIndex, glyphCount) == total - prefix[glyphIndex].
                    CgProfiler.count("lineBreak.measureFastPath");
                    return sumAdvances(advances, glyphIndex, glyphCount);
                }
            } else {
                int glyphIndex = glyphIndexAtCharOffset(run.clusterIds(), byteToChar, breakPos);
                if (glyphIndex >= 0 && (glyphIndex == 0 || safeToBreakBefore[glyphIndex])) {
                    CgProfiler.count("lineBreak.measureFastPath");
                    return sumAdvances(advances, 0, glyphIndex);
                }
            }
        }

        // Counted, not assumed: the fast path silently degrading to a HarfBuzz re-shape per
        // binary-search step is the difference between line breaking costing microseconds and
        // costing more than shaping the text did. Which of these two counters dominates decides
        // where any further work belongs.
        CgProfiler.count("lineBreak.measureReshape");
        try (CgProfiler.Scope ignored = CgProfiler.scope("lineBreak.reshape")) {
            CgShapedRun prefix = reshaper.reshape(context, run, runStart, runStart + breakPos);
            return prefix != null ? prefix.totalAdvance() : Float.MAX_VALUE;
        }
    }

    /**
     * @return the index of the glyph whose cluster maps to exactly {@code charOffset}
     *         (i.e. the first glyph of the prefix ending there), or {@code -1} if no glyph
     *         starts exactly at that char offset (no exact cluster-boundary match)
     */
    private static int glyphIndexAtCharOffset(int[] clusterIds, int[] byteToChar, int charOffset) {
        for (int i = 0; i < clusterIds.length; i++) {
            if (byteToChar[clusterIds[i]] == charOffset) {
                return i;
            }
        }
        return -1;
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
        int length = segment.length();

        // Grown int[] rather than an ArrayList<Integer>. The character-instance caller emits one
        // boundary per grapheme, so the boxed version allocated an Integer for very nearly every
        // character of every segment it scanned -- measured at 49,190 boxes across 105 grapheme
        // fallbacks on one cold frame, which is most of what made that path 10.6 ms.
        int[] result = new int[16];
        int count = 0;

        iterator.first();
        for (int boundary = iterator.next(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            // The boundary at length() is excluded deliberately -- see this method's javadoc.
            if (boundary >= length) continue;
            if (count == result.length) result = Arrays.copyOf(result, count * 2);
            result[count++] = boundary;
        }
        return count == result.length ? result : Arrays.copyOf(result, count);
    }

    /**
     * Per-thread {@link BreakIterator}s, because constructing one is not cheap (rule-based, with
     * table setup) and {@link #splitAtGraphemeBreaks} was constructing a fresh character instance
     * on every call — 105 of them on a single cold frame of {@code text-3d}.
     *
     * <p>Thread-local rather than plain fields: {@code CgTextLayoutEngine} holds one
     * {@code CgLineBreaker} in a {@code static final}, so the breaker is shared process-wide, and
     * {@code BreakIterator} is explicitly not thread-safe — it carries the text and a cursor. A
     * shared instance would corrupt concurrent layout in a way that shows up as wrong wrap points
     * under load and nowhere else.</p>
     *
     * <p>Both are stateless between uses from the caller's point of view: {@link #collectBoundaries}
     * calls {@code setText} before reading anything, which resets the cursor.</p>
     */
    private static final ThreadLocal<BreakIterator> LINE_ITERATOR =
            ThreadLocal.withInitial(() -> BreakIterator.getLineInstance(Locale.ROOT));
    private static final ThreadLocal<BreakIterator> CHARACTER_ITERATOR =
            ThreadLocal.withInitial(() -> BreakIterator.getCharacterInstance(Locale.ROOT));

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
            if (run.rtl()) {
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
            levels[i] = (byte) (logicalRuns.get(i).rtl() ? 1 : 0);
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
