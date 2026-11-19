package com.crystalgraphics.api.vector;

import java.util.Arrays;
import java.util.Objects;

/**
 * How a {@link CgVectorPath}'s outline is drawn: width, caps, joins, dashes and a trim. SVG's stroke properties and
 * Skia's {@code SkStrokeRec}, with Lottie's trim paths for draw-on effects. A value: kept and shared.
 *
 * <pre>{@code
 * static final CgStroke PEN = CgStroke.of(2f).cap(CgStrokeCap.ROUND).join(CgStrokeJoin.ROUND);
 * static final CgStroke DASHED = CgStroke.of(1f).dash(4f, 2f);
 *
 * canvas.vector(tick).stroke(PEN, 0xFF40C060).submit();
 * canvas.vector(tick).stroke(PEN.trim(0f, progress), 0xFF40C060).submit();    // drawn on as progress goes 0 to 1
 * }</pre>
 *
 * <ul>
 *   <li>Width is in the units the path is drawn in; a stroke never draws thinner than a pixel, fading instead.</li>
 *   <li>The trim is a fraction of the whole path's length, every contour end to end; dashes are laid along what the
 *       trim keeps.</li>
 *   <li>Dashes alternate on and off lengths; an odd count repeats once, as SVG does.</li>
 * </ul>
 *
 * @param width      the stroke's full width
 * @param cap        how open contours end
 * @param join       how corners turn
 * @param miterLimit past this ratio of miter length to width, a miter is cut to a bevel
 * @param dashes     on and off lengths, empty for a solid stroke
 * @param dashOffset how far into the dash pattern the path starts
 * @param trimStart  where along the path the stroke starts, 0 to 1
 * @param trimEnd    where it ends, 0 to 1
 */
public record CgStroke(float width, CgStrokeCap cap, CgStrokeJoin join, float miterLimit, float[] dashes,
                       float dashOffset, float trimStart, float trimEnd) {

    private static final float[] SOLID = {};

    public CgStroke {
        if (!(width > 0f) || Float.isInfinite(width)) throw new IllegalArgumentException("width must be positive: " + width);
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(join, "join");
        if (!(miterLimit >= 1f)) throw new IllegalArgumentException("miterLimit must be >= 1: " + miterLimit);
        dashes = dashes.length % 2 == 1 ? doubled(dashes) : dashes.clone();
        float total = 0f;
        for (float d : dashes) {
            if (!(d >= 0f) || Float.isInfinite(d)) throw new IllegalArgumentException("dash lengths are finite and >= 0");
            total += d;
        }
        if (dashes.length > 0 && total <= 0f) throw new IllegalArgumentException("a dash pattern needs a length");
        if (!Float.isFinite(dashOffset)) throw new IllegalArgumentException("dashOffset must be finite");
        if (!(trimStart >= 0f && trimStart <= trimEnd && trimEnd <= 1f)) {
            throw new IllegalArgumentException("trim must satisfy 0 <= start <= end <= 1: " + trimStart + ", " + trimEnd);
        }
    }

    /** A solid stroke {@code width} wide, butt caps, miter joins (limit 4, SVG's default). */
    public static CgStroke of(float width) {
        return new CgStroke(width, CgStrokeCap.BUTT, CgStrokeJoin.MITER, 4f, SOLID, 0f, 0f, 1f);
    }

    public CgStroke width(float width) {
        return new CgStroke(width, cap, join, miterLimit, dashes, dashOffset, trimStart, trimEnd);
    }

    public CgStroke cap(CgStrokeCap cap) {
        return new CgStroke(width, cap, join, miterLimit, dashes, dashOffset, trimStart, trimEnd);
    }

    public CgStroke join(CgStrokeJoin join) {
        return new CgStroke(width, cap, join, miterLimit, dashes, dashOffset, trimStart, trimEnd);
    }

    public CgStroke miterLimit(float limit) {
        return new CgStroke(width, cap, join, limit, dashes, dashOffset, trimStart, trimEnd);
    }

    /** This stroke dashed: on, off, on, off... lengths, in the path's units. */
    public CgStroke dash(float... onOff) {
        return new CgStroke(width, cap, join, miterLimit, onOff, dashOffset, trimStart, trimEnd);
    }

    /** This stroke's dash pattern started {@code offset} along: animate it to march the dashes. */
    public CgStroke dashOffset(float offset) {
        return new CgStroke(width, cap, join, miterLimit, dashes, offset, trimStart, trimEnd);
    }

    /** This stroke drawn only between {@code start} and {@code end}, fractions of the path's whole length. */
    public CgStroke trim(float start, float end) {
        return new CgStroke(width, cap, join, miterLimit, dashes, dashOffset, start, end);
    }

    /** Whether it has dashes. */
    public boolean isDashed() {
        return dashes.length > 0;
    }

    /** Whether the trim keeps the whole path. */
    public boolean isUntrimmed() {
        return trimStart == 0f && trimEnd == 1f;
    }

    /** Its on and off lengths: a copy. */
    @Override
    public float[] dashes() {
        return dashes.clone();
    }

    private static float[] doubled(float[] d) {
        float[] out = Arrays.copyOf(d, d.length * 2);
        System.arraycopy(d, 0, out, d.length, d.length);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgStroke s && Float.compare(width, s.width) == 0 && cap == s.cap && join == s.join
                && Float.compare(miterLimit, s.miterLimit) == 0 && Arrays.equals(dashes, s.dashes)
                && Float.compare(dashOffset, s.dashOffset) == 0 && Float.compare(trimStart, s.trimStart) == 0
                && Float.compare(trimEnd, s.trimEnd) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(width);
        h = 31 * h + cap.hashCode();
        h = 31 * h + join.hashCode();
        h = 31 * h + Float.hashCode(miterLimit);
        h = 31 * h + Arrays.hashCode(dashes);
        h = 31 * h + Float.hashCode(dashOffset);
        h = 31 * h + Float.hashCode(trimStart);
        return 31 * h + Float.hashCode(trimEnd);
    }

    @Override
    public String toString() {
        return "CgStroke[" + width + " " + cap + " " + join + (isDashed() ? " dash " + Arrays.toString(dashes) : "")
                + (isUntrimmed() ? "" : " trim " + trimStart + ".." + trimEnd) + ']';
    }
}
