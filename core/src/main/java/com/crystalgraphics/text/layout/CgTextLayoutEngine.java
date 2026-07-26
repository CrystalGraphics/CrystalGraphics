package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgParagraphKnobs;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextDecoration;
import com.crystalgraphics.api.text.CgTextDirection;
import com.crystalgraphics.api.text.CgTextLayout;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Internal base implementation of the text layout pipeline.
 *
 * <p>The concrete shaping/fallback bridge is supplied by a subclass so the real
 * algorithm can live in the {@code text} package while package-private font
 * internals remain encapsulated inside {@code api/font}.</p>
 *
 * <h3>Shape / wrap split</h3>
 * <p>{@link #shape}/{@link #shapeStyled} do the expensive, one-time part — paragraph
 * splitting, BiDi (∩ style-span) analysis, HarfBuzz shaping — and return a
 * {@link CgShapedParagraph}. {@link #wrap} does the cheap, re-runnable part — line
 * breaking and baking — and is a {@code static} method with no dependency on the
 * shaping hooks, since by that point shaping has already happened; it is what
 * {@link CgShapedParagraph#layout(float, float)} calls into. There is no public
 * {@code layout(String, ...)} entry point on this class anymore — that role now
 * belongs to {@code api.text.CgTextLayoutRequest}, which calls {@link #shape}/
 * {@link #shapeStyled} then {@link CgShapedParagraph#layout}.</p>
 */
public abstract class CgTextLayoutEngine {

    private static final CgLineBreaker LINE_BREAKER = new CgLineBreaker();

    /**
     * Shapes plain text against a single family — paragraph splitting, tab-stop expansion
     * (see {@link CgParagraphKnobs#tabStopWidth()}), BiDi analysis, and HarfBuzz shaping —
     * without line-breaking or baking. Call {@link CgShapedParagraph#layout} on the result
     * to wrap it at a width.
     */
    public CgShapedParagraph shape(String text, CgFontFamily family, CgParagraphKnobs knobs) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (knobs == null) knobs = CgParagraphKnobs.DEFAULT;

        List<CgShapedParagraph.Slice> slices = new ArrayList<>();
        for (int[] range : paragraphRanges(text)) {
            String paragraph = text.substring(range[0], range[1]);
            List<CgShapedRun> runs = paragraph.isEmpty()
                    ? new ArrayList<>()
                    : shapeParagraphText(paragraph, family, knobs.direction(), knobs.tabStopWidth());
            slices.add(new CgShapedParagraph.Slice(paragraph, runs, new CgReshapeContext(paragraph)));
        }

        RunReshaper reshaper = createRunReshaper(CgFontFamilyGroup.ofRegular(family));
        List<CgShapedRun> ellipsisRuns = shapeEllipsis(knobs.ellipsisMarker(), family, knobs.direction());

        return new CgShapedParagraph(slices, family.getLayoutMetrics(), reshaper, ellipsisRuns, knobs);
    }

    /**
     * Shapes rich text: {@code text}'s style spans are intersected with each BiDi run so a
     * bold/italic sub-range resolves against {@code group}'s corresponding face instead of
     * always the regular one, and the resulting runs carry that span's color/decoration/
     * features/baseline-shift (see {@link CgShapedRun}'s rich-span fields).
     *
     * <p>{@link CgStyleSpan#fontFamilyOverride()} is not consumed yet — only bold/italic
     * drive family resolution. {@link CgParagraphKnobs#tabStopWidth()} is not implemented
     * for this overload (v1 limitation — see that field's javadoc).</p>
     */
    public CgShapedParagraph shapeStyled(CgStyledText text, CgFontFamilyGroup group, CgParagraphKnobs knobs) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        if (knobs == null) knobs = CgParagraphKnobs.DEFAULT;

        CgFontFamily regular = group.resolve(CgFontStyle.REGULAR);
        String plainText = text.plainText();

        List<CgShapedParagraph.Slice> slices = new ArrayList<>();
        for (int[] range : paragraphRanges(plainText)) {
            String paragraph = plainText.substring(range[0], range[1]);
            List<CgShapedRun> runs;
            if (paragraph.isEmpty()) {
                runs = new ArrayList<>();
            } else {
                List<CgStyleSpan> paragraphSpans = rebaseSpans(text.spans(), range[0], range[1]);
                runs = splitAndShapeRunsStyled(paragraph, paragraphSpans, group, knobs.direction());
            }
            slices.add(new CgShapedParagraph.Slice(paragraph, runs, new CgReshapeContext(paragraph)));
        }

        RunReshaper reshaper = createRunReshaper(group);
        List<CgShapedRun> ellipsisRuns = shapeEllipsis(knobs.ellipsisMarker(), regular, knobs.direction());

        return new CgShapedParagraph(slices, regular.getLayoutMetrics(), reshaper, ellipsisRuns, knobs);
    }

    /**
     * Wraps already-{@linkplain #shape shaped}/{@linkplain #shapeStyled styled} paragraph
     * slices at {@code maxWidth}/{@code maxHeight} — line-breaking, justifiable tagging,
     * max-lines/ellipsis truncation, alignment, and baking. No shaping happens here; this
     * is exactly what {@link CgShapedParagraph#layout(float, float)} calls, and is why it
     * is {@code static} — nothing here depends on the shaping hooks.
     */
    public static CgTextLayout wrap(List<CgShapedParagraph.Slice> slices, CgFontMetrics metrics, RunReshaper reshaper,
                                     List<CgShapedRun> ellipsisRuns, float maxWidth, float maxHeight, CgParagraphKnobs knobs) {
        if (slices.isEmpty()) {
            return new CgTextLayout(new ArrayList<>(), 0, 0, metrics, CgBakedGlyphs.EMPTY);
        }

        List<List<CgShapedRun>> allLines = new ArrayList<>();
        List<boolean[]> justifiableByLine = new ArrayList<>();
        List<Float> lineWidths = new ArrayList<>();
        float totalHeight = 0.0f;
        float lineHeight = metrics.getLineHeight();

        for (CgShapedParagraph.Slice slice : slices) {
            if (maxHeight > 0 && totalHeight + lineHeight > maxHeight) {
                break;
            }

            String paragraph = slice.text();
            if (paragraph.isEmpty()) {
                allLines.add(new ArrayList<>());
                justifiableByLine.add(new boolean[0]);
                lineWidths.add(0f);
                totalHeight += lineHeight;
                continue;
            }

            float remainingHeight = maxHeight > 0 ? maxHeight - totalHeight : 0;
            List<List<CgShapedRun>> paraLines = LINE_BREAKER.breakLines(
                    slice.runs(), maxWidth, remainingHeight, metrics, slice.context(), reshaper);

            BitSet lineBreakBoundaries = collectLineBreakBoundaries(paragraph);
            for (List<CgShapedRun> line : paraLines) {
                allLines.add(line);
                justifiableByLine.add(computeJustifiable(paragraph, lineBreakBoundaries, line));
                lineWidths.add(lineWidth(line));
            }
            totalHeight += paraLines.size() * lineHeight;
        }

        boolean truncated = knobs.maxLines() > 0 && allLines.size() > knobs.maxLines();
        if (truncated) {
            while (allLines.size() > knobs.maxLines()) {
                int last = allLines.size() - 1;
                allLines.remove(last);
                justifiableByLine.remove(last);
                lineWidths.remove(last);
            }
            if (knobs.ellipsisMarker() != null && !ellipsisRuns.isEmpty() && !allLines.isEmpty()) {
                int lastIdx = allLines.size() - 1;
                List<CgShapedRun> withEllipsis = applyEllipsis(allLines.get(lastIdx), ellipsisRuns, maxWidth);
                allLines.set(lastIdx, withEllipsis);
                lineWidths.set(lastIdx, lineWidth(withEllipsis));
                justifiableByLine.set(lastIdx, new boolean[countGlyphs(withEllipsis)]);
            }
        }

        float totalWidth = 0;
        for (float w : lineWidths) {
            if (w > totalWidth) totalWidth = w;
        }

        CgBakedGlyphs baked = bakeGlyphs(allLines, justifiableByLine, metrics, knobs.lineHeightOverride());

        float boxWidth = maxWidth > 0 ? maxWidth : totalWidth;
        applyAlignment(baked, lineWidths, knobs.align(), boxWidth);

        float finalTotalHeight = 0f;
        for (float h : baked.lineHeight()) {
            finalTotalHeight += h;
        }

        return new CgTextLayout(allLines, totalWidth, finalTotalHeight, metrics, baked);
    }

    private static float lineWidth(List<CgShapedRun> line) {
        float width = 0;
        for (CgShapedRun run : line) {
            width += run.totalAdvance();
        }
        return width;
    }

    private static int countGlyphs(List<CgShapedRun> line) {
        int count = 0;
        for (CgShapedRun run : line) {
            count += run.glyphIds().length;
        }
        return count;
    }

    /**
     * Drops trailing runs from {@code lastLine} until {@code ellipsisRuns} fits within
     * {@code maxWidth} (unbounded width just appends it), then appends {@code ellipsisRuns}.
     * Trims at whole-run granularity, not per-glyph — a deliberate v1 simplification;
     * precise glyph-level trimming would need to re-shape a partial run the same way
     * {@link CgLineBreaker}'s own forced-wrap binary search does, which this intentionally
     * doesn't do here to avoid needing to track which paragraph's reshape context the
     * (possibly truncated) last line belongs to.
     */
    private static List<CgShapedRun> applyEllipsis(List<CgShapedRun> lastLine, List<CgShapedRun> ellipsisRuns, float maxWidth) {
        float ellipsisWidth = 0;
        for (CgShapedRun r : ellipsisRuns) {
            ellipsisWidth += r.totalAdvance();
        }

        List<CgShapedRun> trimmed = new ArrayList<>(lastLine);
        if (maxWidth > 0) {
            float width = lineWidth(trimmed);
            while (!trimmed.isEmpty() && width + ellipsisWidth > maxWidth) {
                CgShapedRun removed = trimmed.remove(trimmed.size() - 1);
                width -= removed.totalAdvance();
            }
        }
        trimmed.addAll(ellipsisRuns);
        return trimmed;
    }

    /**
     * Offsets every glyph on each line by {@code align}'s horizontal adjustment within
     * {@code boxWidth} (mutates {@code baked}'s {@code penX} array in place — safe, since
     * {@link #bakeGlyphs} just built these arrays fresh for this call). {@code START}/
     * {@code END} are treated as {@code LEFT}/{@code RIGHT} (see {@link CgTextAlign}'s
     * javadoc — no direction-aware swap yet).
     */
    private static void applyAlignment(CgBakedGlyphs baked, List<Float> lineWidths, CgTextAlign align, float boxWidth) {
        if (align == CgTextAlign.LEFT || align == CgTextAlign.START) {
            return;
        }

        int[] lineStart = baked.lineStart();
        float[] penX = baked.penX();
        for (int lineIndex = 0; lineIndex < lineWidths.size(); lineIndex++) {
            float offset = alignmentOffset(align, boxWidth, lineWidths.get(lineIndex));
            if (offset == 0f) continue;
            for (int i = lineStart[lineIndex]; i < lineStart[lineIndex + 1]; i++) {
                penX[i] += offset;
            }
        }
    }

    private static float alignmentOffset(CgTextAlign align, float boxWidth, float lineWidth) {
        return switch (align) {
            case RIGHT, END -> boxWidth - lineWidth;
            case CENTER -> (boxWidth - lineWidth) / 2f;
            default -> 0f;
        };
    }

    /** Splits {@code text} into paragraphs on {@code \r\n}/{@code \r}/{@code \n}. */
    protected List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (int[] range : paragraphRanges(text)) {
            paragraphs.add(text.substring(range[0], range[1]));
        }
        return paragraphs;
    }

    /** {@code [start, end)} char ranges for each paragraph, split on {@code \r\n}/{@code \r}/{@code \n}. */
    private static List<int[]> paragraphRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        int len = text.length();
        int start = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                ranges.add(new int[]{start, i});
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n') {
                ranges.add(new int[]{start, i});
                start = i + 1;
            }
        }

        ranges.add(new int[]{start, len});
        return ranges;
    }

    private static int bidiFlagFor(CgTextDirection direction) {
        return switch (direction) {
            case LTR -> Bidi.DIRECTION_LEFT_TO_RIGHT;
            case RTL -> Bidi.DIRECTION_RIGHT_TO_LEFT;
            case AUTO -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT;
        };
    }

    private List<CgShapedRun> splitAndShapeRuns(String text, CgFontFamily family, CgTextDirection direction) {
        Bidi bidi = new Bidi(text, bidiFlagFor(direction));
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

    /**
     * Like {@link #splitAndShapeRuns}, but further splits each BiDi run at any style-span
     * boundary falling inside it (sorted-interval intersection between the BiDi run's
     * {@code [start,end)} and {@code spans}), resolves {@code group.resolve(...)} for the
     * sub-range's bold/italic combination to pick the family, then calls the same
     * {@link #collectShapedRuns} hook — its signature is unchanged; the resulting runs are
     * re-wrapped afterward with the covering span's rich fields (color/decoration/features/
     * baseline-shift), if any.
     */
    private List<CgShapedRun> splitAndShapeRunsStyled(String text, List<CgStyleSpan> spans, CgFontFamilyGroup group,
                                                       CgTextDirection direction) {
        Bidi bidi = new Bidi(text, bidiFlagFor(direction));
        int runCount = bidi.getRunCount();
        List<CgShapedRun> runs = new ArrayList<>(runCount);

        for (int i = 0; i < runCount; i++) {
            int bidiStart = bidi.getRunStart(i);
            int bidiEnd = bidi.getRunLimit(i);
            int level = bidi.getRunLevel(i);
            boolean rtl = (level % 2) != 0;

            for (int[] subRange : intersectWithSpans(bidiStart, bidiEnd, spans)) {
                int subStart = subRange[0];
                int subEnd = subRange[1];
                CgStyleSpan span = spanAt(spans, subStart);
                CgFontStyle requested = styleOf(span);
                CgFontFamily resolvedFamily = group.resolve(requested);

                int before = runs.size();
                collectShapedRuns(text, subStart, subEnd, rtl, resolvedFamily, runs);
                if (span != null) {
                    for (int idx = before; idx < runs.size(); idx++) {
                        runs.set(idx, applyStyle(runs.get(idx), span, requested, resolvedFamily));
                    }
                }
            }
        }

        return runs;
    }

    /**
     * Shapes {@code marker} once (at shape time, not per-{@code layout()} call) against
     * {@code family} so {@link CgTextLayoutEngine#wrap} never needs to re-shape anything —
     * matching its own "no re-shape" contract.
     */
    private List<CgShapedRun> shapeEllipsis(String marker, CgFontFamily family, CgTextDirection direction) {
        if (marker == null || marker.isEmpty()) {
            return List.of();
        }
        return splitAndShapeRuns(marker, family, direction);
    }

    /**
     * Expands {@code \t} into a synthetic zero-glyph advance-only run reaching the next
     * tab-stop column, splitting {@code paragraph} at each tab so the surrounding text still
     * goes through the normal BiDi+shape path unchanged. The paragraph's own character
     * offsets are never altered (tabs are never replaced by literal spaces), so source
     * positions and (for the styled overload, not yet wired to this) span indices stay valid.
     *
     * <p><strong>v1 simplification:</strong> the running column is tracked assuming the
     * paragraph is not yet line-wrapped — real column position after a soft-wrap is not
     * tracked. See {@link CgParagraphKnobs#tabStopWidth()}.</p>
     */
    private List<CgShapedRun> shapeParagraphText(String paragraph, CgFontFamily family, CgTextDirection direction, float tabStopWidth) {
        if (tabStopWidth <= 0 || paragraph.indexOf('\t') < 0) {
            return splitAndShapeRuns(paragraph, family, direction);
        }

        List<CgShapedRun> result = new ArrayList<>();
        float column = 0f;
        int segmentStart = 0;
        int len = paragraph.length();

        for (int i = 0; i < len; i++) {
            if (paragraph.charAt(i) != '\t') continue;

            if (i > segmentStart) {
                String segment = paragraph.substring(segmentStart, i);
                for (CgShapedRun r : splitAndShapeRuns(segment, family, direction)) {
                    CgShapedRun offset = offsetRunSource(r, segmentStart);
                    result.add(offset);
                    column += offset.totalAdvance();
                }
            }

            float advance = tabStopWidth - (column % tabStopWidth);
            if (advance <= 0.0001f) advance = tabStopWidth;
            result.add(makeTabRun(family, i, advance));
            column += advance;
            segmentStart = i + 1;
        }

        if (segmentStart < len) {
            String segment = paragraph.substring(segmentStart, len);
            for (CgShapedRun r : splitAndShapeRuns(segment, family, direction)) {
                result.add(offsetRunSource(r, segmentStart));
            }
        }

        return result;
    }

    /** Rebuilds {@code r} with its source position shifted by {@code offset} — same shape data. */
    private static CgShapedRun offsetRunSource(CgShapedRun r, int offset) {
        if (offset == 0) return r;
        return new CgShapedRun()
                .fontKey(r.fontKey()).resolvedFont(r.resolvedFont()).rtl(r.rtl())
                .glyphIds(r.glyphIds()).clusterIds(r.clusterIds())
                .advancesX(r.advancesX()).offsetsX(r.offsetsX()).offsetsY(r.offsetsY())
                .totalAdvance(r.totalAdvance())
                .sourceStart(r.sourceStart() + offset).sourceEnd(r.sourceEnd() + offset)
                .argbColor(r.argbColor()).decorations(r.decorations())
                .fontFeatures(r.fontFeatures()).baselineShift(r.baselineShift())
                .safeToBreakBefore(r.safeToBreakBefore())
                .syntheticBold(r.syntheticBold()).syntheticItalic(r.syntheticItalic());
    }

    /** A zero-glyph run whose only purpose is to advance the pen by a tab stop's width. */
    private static CgShapedRun makeTabRun(CgFontFamily family, int sourceIndex, float advance) {
        return new CgShapedRun()
                .fontKey(family.getPrimarySource().getKey()).resolvedFont(family.getPrimaryFont()).rtl(false)
                .glyphIds(new int[0]).clusterIds(new int[0])
                .advancesX(new float[0]).offsetsX(new float[0]).offsetsY(new float[0])
                .totalAdvance(advance).sourceStart(sourceIndex).sourceEnd(sourceIndex + 1);
    }

    /**
     * Partitions {@code [start,end)} at every {@code spans} boundary falling strictly inside
     * it, so each resulting sub-range lies entirely within one span or entirely outside all
     * of them — never straddling a span boundary.
     */
    private static List<int[]> intersectWithSpans(int start, int end, List<CgStyleSpan> spans) {
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(start);
        cuts.add(end);
        for (CgStyleSpan span : spans) {
            if (span.start() > start && span.start() < end) cuts.add(span.start());
            if (span.end() > start && span.end() < end) cuts.add(span.end());
        }

        List<Integer> sorted = new ArrayList<>(cuts);
        List<int[]> result = new ArrayList<>(sorted.size() - 1);
        for (int i = 0; i < sorted.size() - 1; i++) {
            result.add(new int[]{sorted.get(i), sorted.get(i + 1)});
        }
        return result;
    }

    /** The span whose {@code [start,end)} contains {@code position}, or {@code null}. */
    private static CgStyleSpan spanAt(List<CgStyleSpan> spans, int position) {
        for (CgStyleSpan span : spans) {
            if (position >= span.start() && position < span.end()) {
                return span;
            }
        }
        return null;
    }

    private static CgFontStyle styleOf(CgStyleSpan span) {
        if (span == null) return CgFontStyle.REGULAR;
        if (span.bold() && span.italic()) return CgFontStyle.BOLD_ITALIC;
        if (span.bold()) return CgFontStyle.BOLD;
        if (span.italic()) return CgFontStyle.ITALIC;
        return CgFontStyle.REGULAR;
    }

    private static boolean hasBold(CgFontStyle style) {
        return style == CgFontStyle.BOLD || style == CgFontStyle.BOLD_ITALIC;
    }

    private static boolean hasItalic(CgFontStyle style) {
        return style == CgFontStyle.ITALIC || style == CgFontStyle.BOLD_ITALIC;
    }

    /**
     * Rebuilds {@code run} with {@code span}'s rich fields — same shape data, richer styling.
     *
     * <p>{@code requested} is the style the span asked {@code CgFontFamilyGroup#resolve} for;
     * {@code resolvedFamily} is what it actually got back. When {@code resolvedFamily} has no
     * distinct face for a requested bold/italic bit (the group fell back to
     * {@link CgFontStyle#REGULAR}), the corresponding {@code synthetic*} flag is set so the
     * rasterizer fakes it — see {@link CgShapedRun#syntheticBold()}.</p>
     */
    private static CgShapedRun applyStyle(CgShapedRun run, CgStyleSpan span, CgFontStyle requested, CgFontFamily resolvedFamily) {
        CgFontStyle actual = resolvedFamily.getPrimaryFont().getKey().getStyle();
        boolean syntheticBold = hasBold(requested) && !hasBold(actual);
        boolean syntheticItalic = hasItalic(requested) && !hasItalic(actual);

        // Advances are left exactly as HarfBuzz shaped them — matching Skia/Chromium, which
        // don't widen advances for synthetic oblique either. An earlier attempt to compensate
        // here for perceived glyph-to-glyph overlap was actually chasing a different bug (a
        // stale pre-shear glyph metrics bbox squishing the real, wider rendered bitmap into a
        // too-narrow quad — see FTFace#outlineShear); once that's fixed at the source, advances
        // don't need adjusting.
        return new CgShapedRun()
                .fontKey(run.fontKey()).resolvedFont(run.resolvedFont()).rtl(run.rtl())
                .glyphIds(run.glyphIds()).clusterIds(run.clusterIds()).advancesX(run.advancesX())
                .offsetsX(run.offsetsX()).offsetsY(run.offsetsY()).totalAdvance(run.totalAdvance())
                .sourceStart(run.sourceStart()).sourceEnd(run.sourceEnd())
                .argbColor(span.argbColor()).decorations(span.decorations())
                .fontFeatures(span.fontFeatures()).baselineShift(span.baselineShift())
                .safeToBreakBefore(run.safeToBreakBefore())
                .syntheticBold(syntheticBold).syntheticItalic(syntheticItalic);
    }

    /**
     * Clamps {@code spans} to {@code [rangeStart,rangeEnd)} and rebases surviving spans'
     * indices to be relative to {@code rangeStart} — used to slice a {@code CgStyledText}'s
     * paragraph-spanning span list down to one paragraph's local coordinates.
     */
    private static List<CgStyleSpan> rebaseSpans(List<CgStyleSpan> spans, int rangeStart, int rangeEnd) {
        List<CgStyleSpan> rebased = new ArrayList<>();
        for (CgStyleSpan span : spans) {
            int clampedStart = Math.max(span.start(), rangeStart);
            int clampedEnd = Math.min(span.end(), rangeEnd);
            if (clampedStart < clampedEnd) {
                rebased.add(CgStyleSpan.builder()
                        .start(clampedStart - rangeStart)
                        .end(clampedEnd - rangeStart)
                        .bold(span.bold())
                        .italic(span.italic())
                        .decorations(span.decorations())
                        .argbColor(span.argbColor())
                        .fontFamilyOverride(span.fontFamilyOverride())
                        .fontFeatures(span.fontFeatures())
                        .baselineShift(span.baselineShift())
                        .build());
            }
        }
        return rebased;
    }

    protected abstract void collectShapedRuns(String text, int start, int end, boolean rtl, CgFontFamily family, List<CgShapedRun> out);

    /**
     * @param group the family group to resolve each fragment's font against, by its
     *              {@link CgFontKey#getStyle()} — for the plain-family overloads this is a
     *              single-family group ({@link CgFontFamilyGroup#ofRegular}), so a bold/italic
     *              fragment simply has no non-regular entry to resolve to; for the styled
     *              overload it's the real group, so a bold run's intra-run word-wrap re-shape
     *              resolves the bold face instead of always the regular one
     */
    protected abstract RunReshaper createRunReshaper(CgFontFamilyGroup group);

    /**
     * Bakes {@code lines} (and their parallel {@code justifiableByLine} flags) into a flat
     * {@link CgBakedGlyphs} — one walk over the final line/run tree, pen positions relative
     * to the layout's own origin (the caller's own draw {@code x, y} is added later, once,
     * by whoever resolves atlas placements).
     *
     * <p>Each line's height/baseline uses the real max-over-metrics of every glyph's own
     * resolved font on that line ({@link #computeLineMetrics}) — not one uniform value —
     * so a line mixing e.g. a bold run with taller metrics than the regular face gets that
     * taller height. {@code fallbackMetrics} is used only for glyphs whose run has no
     * resolved font (hand-built fixtures in tests). {@code lineHeightOverride > 0} replaces
     * every line's height uniformly instead (see {@link CgParagraphKnobs#lineHeightOverride()}).</p>
     */
    static CgBakedGlyphs bakeGlyphs(List<List<CgShapedRun>> lines,
                                     List<boolean[]> justifiableByLine,
                                     CgFontMetrics fallbackMetrics,
                                     float lineHeightOverride) {
        int glyphCount = 0;
        for (List<CgShapedRun> line : lines) {
            for (CgShapedRun run : line) {
                glyphCount += run.glyphIds().length;
            }
        }

        CgFontKey[] fontKeys = new CgFontKey[glyphCount];
        CgFont[] fonts = new CgFont[glyphCount];
        int[] glyphIds = new int[glyphCount];
        float[] penX = new float[glyphCount];
        float[] penY = new float[glyphCount];
        float[] offsetX = new float[glyphCount];
        int[] argbColor = new int[glyphCount];
        boolean[] justifiable = new boolean[glyphCount];
        boolean[] syntheticBold = new boolean[glyphCount];
        boolean[] syntheticItalic = new boolean[glyphCount];
        float[] lineHeights = new float[lines.size()];
        int[] lineStart = new int[lines.size() + 1];
        List<CgTextDecorationRect> decorationRects = new ArrayList<>();

        int index = 0;
        float cumulativeY = 0f;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            List<CgShapedRun> line = lines.get(lineIndex);
            boolean[] lineJustifiable = justifiableByLine.get(lineIndex);
            lineStart[lineIndex] = index;

            CgFontMetrics lineMetrics = computeLineMetrics(line, fallbackMetrics);
            float thisLineHeight = lineHeightOverride > 0 ? lineHeightOverride : lineMetrics.getLineHeight();
            lineHeights[lineIndex] = thisLineHeight;

            float lineBaseline = cumulativeY + lineMetrics.getAscender();
            float penXCursor = 0;
            int glyphInLine = 0;
            for (CgShapedRun run : line) {
                CgFontKey fontKey = run.fontKey();
                CgFont font = run.resolvedFont();
                int[] ids = run.glyphIds();
                float[] advances = run.advancesX();
                float[] offsetsX = run.offsetsX();
                float[] offsetsY = run.offsetsY();
                float runStartX = penXCursor;
                for (int i = 0; i < ids.length; i++) {
                    fontKeys[index] = fontKey;
                    fonts[index] = font;
                    glyphIds[index] = ids[i];
                    penX[index] = penXCursor + offsetsX[i];
                    penY[index] = lineBaseline + offsetsY[i];
                    offsetX[index] = offsetsX[i];
                    argbColor[index] = run.argbColor();
                    justifiable[index] = lineJustifiable[glyphInLine];
                    syntheticBold[index] = run.syntheticBold();
                    syntheticItalic[index] = run.syntheticItalic();
                    penXCursor += advances[i];
                    index++;
                    glyphInLine++;
                }
                if (ids.length == 0) {
                    // Zero-glyph advance-only run (tab stop) — still moves the pen.
                    penXCursor += run.totalAdvance();
                }
                if (!run.decorations().isEmpty() && penXCursor > runStartX) {
                    for (CgTextDecoration kind : run.decorations()) {
                        decorationRects.add(buildDecorationSegment(
                                run, kind, fontKey, font, fallbackMetrics, runStartX, penXCursor, lineBaseline));
                    }
                }
            }
            cumulativeY += thisLineHeight;
        }
        lineStart[lines.size()] = glyphCount;

        return new CgBakedGlyphs(glyphCount, fontKeys, fonts, glyphIds, penX, penY, offsetX, argbColor, justifiable,
                lineHeights, lineStart, decorationRects.toArray(CgTextDecorationRect.NONE), syntheticBold, syntheticItalic);
    }

    /**
     * Builds one baked decoration rectangle for {@code run}, per Blink's fallback formulas
     * (see {@link CgTextDecorationRect} javadoc). {@code fontSizePx} comes from the run's own
     * {@link CgFontKey#getTargetPx()} — the same face/size actually used to shape this run —
     * falling back to {@code fallbackMetrics}' ascender+descender when {@code fontKey} is
     * {@code null} (hand-built test fixtures without a resolved font).
     */
    private static CgTextDecorationRect buildDecorationSegment(CgShapedRun run, CgTextDecoration kind,
                                                               CgFontKey fontKey, CgFont font,
                                                               CgFontMetrics fallbackMetrics,
                                                               float x0, float x1, float baseline) {
        CgFontMetrics metrics = font != null ? font.getMetrics() : fallbackMetrics;
        float fontSizePx = fontKey != null ? fontKey.getTargetPx() : (metrics.getAscender() + metrics.getDescender());
        float thickness = Math.max(1f, fontSizePx / 10f);
        float y;
        switch (kind) {
            // Centered on half of x-height — the visual middle of lowercase letters, not a
            // fraction of the full ascent (which overshoots into cap-height/ascender territory
            // for fonts with generous ascent headroom, confirmed visually too high in the demo).
            case STRIKETHROUGH -> y = baseline - metrics.getXHeight() / 2f;
            case OVERLINE -> y = baseline - metrics.getAscender() + thickness / 2f;
            default -> {
                float gap = Math.max(1f, (float) Math.ceil(thickness / 2f));
                y = baseline + gap;
            }
        }
        return new CgTextDecorationRect(x0, x1, y, thickness, run.argbColor(), fontKey);
    }

    /**
     * The max-over-metrics ({@link CgFontFamily#combineMetrics}) of every distinct resolved
     * font actually used by {@code line}'s runs, falling back to {@code fallbackMetrics} for
     * any run without a resolved font (and for an empty line).
     */
    private static CgFontMetrics computeLineMetrics(List<CgShapedRun> line, CgFontMetrics fallbackMetrics) {
        if (line.isEmpty()) {
            return fallbackMetrics;
        }
        List<CgFontMetrics> collected = new ArrayList<>(line.size());
        for (CgShapedRun run : line) {
            CgFont font = run.resolvedFont();
            collected.add(font != null ? font.getMetrics() : fallbackMetrics);
        }
        return CgFontFamily.combineMetrics(collected);
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
            glyphCount += run.glyphIds().length;
        }

        boolean[] result = new boolean[glyphCount];
        int index = 0;
        int lastJustifiableIndex = -1;
        for (CgShapedRun run : line) {
            int runStart = run.sourceStart();
            int[] byteToChar = Utf8ClusterMapper.byteOffsetToCharOffset(
                    paragraphText.substring(runStart, run.sourceEnd()));
            for (int cluster : run.clusterIds()) {
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
}
