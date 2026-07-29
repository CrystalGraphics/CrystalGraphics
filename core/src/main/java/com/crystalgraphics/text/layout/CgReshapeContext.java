package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.text.CgShapedRun;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Locale;

/**
 * Carries the source text a paragraph's {@link CgShapedRun}s were shaped from,
 * external to the runs themselves.
 *
 * <p>{@link CgLineBreaker} needs the original text to re-shape sub-ranges when
 * splitting an overflowing run at a word or grapheme boundary ({@link RunReshaper}).
 * Threading it through as its own context — one instance per paragraph — means
 * {@code CgShapedRun} no longer has to carry {@code sourceText} on every instance
 * just to serve this one internal re-shaping need.</p>
 *
 * <p>Was a record; now a class purely so it can memoise {@link #graphemeBoundaries()}. Value
 * equality on {@code sourceText} is preserved, since it is a component of the
 * {@code CgShapedParagraph.Slice} record.</p>
 */
public final class CgReshapeContext {

    private final String sourceText;

    /** Lazily computed by {@link #graphemeBoundaries()}; never mutated after assignment. */
    private int[] graphemeBoundaries;

    public CgReshapeContext(String sourceText) {
        if (sourceText == null) throw new IllegalArgumentException("sourceText must not be null");
        this.sourceText = sourceText;
    }

    public String sourceText() {
        return sourceText;
    }

    /**
     * Every grapheme-cluster boundary in {@link #sourceText}, ascending, excluding 0 and
     * {@code length()}. Computed once per paragraph and shared by every grapheme-level split.
     *
     * <h4>Why this is cached here rather than recomputed per split</h4>
     * <p>{@code CgLineBreaker.splitAtGraphemeBreaks} runs once per line that has no usable word
     * boundary, and each call used to scan the <em>entire remaining segment</em> for boundaries.
     * Across a paragraph that is quadratic in length: measured on a 3363-character run with almost
     * no spaces, 105 fallbacks collected <strong>49,190</strong> boundaries — 14.6x the 3,363 the
     * text actually has — at ~0.19 us each, which was 9.4 ms and the single largest block inside
     * {@code wrap.breakLines}.
     *
     * <h4>Why computing globally is equivalent, not an approximation</h4>
     * <p>Grapheme-cluster boundaries are <em>context-free</em> in the sense that matters here: they
     * are determined by combining marks, surrogate pairs and joiners immediately around a position,
     * never by distant text. So the boundaries of any substring are exactly this array restricted
     * to that range — provided the range starts on a cluster boundary, which it always does, since
     * every split point was itself chosen from this same set.
     *
     * <p>Deliberately <strong>not</strong> done for line-break boundaries. Those genuinely are
     * context-sensitive, and computing them over the whole paragraph instead of the segment being
     * split would change where lines break, not just how fast.
     */
    public int[] graphemeBoundaries() {
        int[] cached = graphemeBoundaries;
        if (cached != null) return cached;

        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(sourceText);
        int length = sourceText.length();
        int[] result = new int[Math.max(16, Math.min(length, 1024))];
        int count = 0;
        iterator.first();
        for (int b = iterator.next(); b != BreakIterator.DONE; b = iterator.next()) {
            if (b <= 0 || b >= length) continue;
            if (count == result.length) result = Arrays.copyOf(result, count * 2);
            result[count++] = b;
        }
        cached = count == result.length ? result : Arrays.copyOf(result, count);
        graphemeBoundaries = cached; // benign race: any thread computes an equal array
        return cached;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgReshapeContext)) return false;
        return sourceText.equals(((CgReshapeContext) o).sourceText);
    }

    @Override
    public int hashCode() {
        return sourceText.hashCode();
    }

    @Override
    public String toString() {
        return "CgReshapeContext[length=" + sourceText.length() + "]";
    }
}
