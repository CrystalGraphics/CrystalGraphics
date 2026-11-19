package com.crystalgraphics.api.paint.gradient;

import java.util.Arrays;

import com.crystalgraphics.api.paint.CgPaint;

/**
 * A colour ramp over a shape, as CSS and Skia draw one: linear, radial, conic or between two points, with any number of
 * stops, interpolated premultiplied and dithered. A value: made once, kept, shared on any thread, compared by value.
 * Anything that takes a {@link CgPaint} takes one: canvas fills, borders and text, a scene quad, a material property.
 *
 * <pre>{@code
 * static final CgGradient SKY = CgGradient.linear(180f)                    // CSS angle: 0 up, clockwise
 *         .stop(0f, 0xFF203040).stop(1f, 0xFF101820);
 * static final CgGradient GLOW = CgGradient.radial(0.5f, 1f, 0.5f, 1f)     // centre and radii, fractions of the box
 *         .stop(0f, 0xFFFFE080).stop(1f, 0x00FFE080);
 * static final CgGradient HUE = CgGradient.conic(0.5f, 0.5f, 0f)
 *         .stop(0f, 0xFFFF0000).stop(0.5f, 0xFF00FFFF).stop(1f, 0xFFFF0000);
 * static final CgGradient STRIPES = CgGradient.linear(45f).repeat()       // a hard stop: two at one position
 *         .stop(0f, 0xFFFFFFFF).stop(0.1f, 0xFFFFFFFF).stop(0.1f, 0x00000000).stop(0.2f, 0x00000000);
 *
 * canvas.rect(x, y, w, h).radius(8).fill(SKY).submit();
 * }</pre>
 *
 * <p>From parsed data (a stylesheet, an SVG), the stops in one call:</p>
 *
 * <pre>{@code
 * CgGradient g = CgGradient.between(0f, 0f, 1f, 0f).stops(positions, colours);
 * }</pre>
 *
 * <ul>
 *   <li>Every geometry is in fractions of the box it fills, 0 to 1 on each axis, top-left at (0, 0): the same value fits
 *       any size. A circle on a box that is not square needs radii scaled by its aspect.</li>
 *   <li>Stops ascend: a position below the one before is raised to it, as CSS does; two at one position are a hard
 *       stop. Positions are clamped to 0..1. Colours are straight {@code 0xAARRGGBB}.</li>
 *   <li>It needs at least two stops to draw; one with fewer fails where it is drawn, by name.</li>
 *   <li>Each {@code stop} answers a new gradient: build it once, never per frame.</li>
 * </ul>
 */
public final class CgGradient implements CgPaint {

    /** Its geometry. */
    public enum Shape {
        /** Along CSS's gradient line at an angle: through the centre, 0 and 1 on the corners. */
        LINEAR,
        /** Outward from a centre over an ellipse's radii. */
        RADIAL,
        /** Around a centre, one clockwise turn. */
        CONIC,
        /** Along the segment between two points, Skia's linear gradient. */
        TWO_POINT
    }

    private static final float[] NO_POSITIONS = {};
    private static final int[] NO_COLOURS = {};

    private final Shape shape;
    private final float a, b, c, d;
    private final CgSpread spread;
    private final boolean dither;
    private final float[] positions;
    private final int[] colours;

    private CgGradient(Shape shape, float a, float b, float c, float d, CgSpread spread, boolean dither,
                       float[] positions, int[] colours) {
        this.shape = shape;
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.spread = spread;
        this.dither = dither;
        this.positions = positions;
        this.colours = colours;
    }

    /** Along CSS's gradient line at {@code degrees}: 0 points up, 90 right, turning clockwise. */
    public static CgGradient linear(float degrees) {
        return new CgGradient(Shape.LINEAR, finite(degrees, "degrees"), 0f, 0f, 0f, CgSpread.PAD, true,
                NO_POSITIONS, NO_COLOURS);
    }

    /** Outward from ({@code centreX}, {@code centreY}): 0 at the centre, 1 on the ellipse of radii ({@code radiusX}, {@code radiusY}). */
    public static CgGradient radial(float centreX, float centreY, float radiusX, float radiusY) {
        return new CgGradient(Shape.RADIAL, finite(centreX, "centreX"), finite(centreY, "centreY"),
                positive(radiusX, "radiusX"), positive(radiusY, "radiusY"), CgSpread.PAD, true, NO_POSITIONS,
                NO_COLOURS);
    }

    /** One clockwise turn about ({@code centreX}, {@code centreY}), starting {@code fromDegrees} clockwise from up. */
    public static CgGradient conic(float centreX, float centreY, float fromDegrees) {
        return new CgGradient(Shape.CONIC, finite(centreX, "centreX"), finite(centreY, "centreY"),
                finite(fromDegrees, "fromDegrees"), 0f, CgSpread.PAD, true, NO_POSITIONS, NO_COLOURS);
    }

    /** Along the segment from ({@code x0}, {@code y0}) at 0 to ({@code x1}, {@code y1}) at 1. */
    public static CgGradient between(float x0, float y0, float x1, float y1) {
        if (finite(x0, "x0") == finite(x1, "x1") && finite(y0, "y0") == finite(y1, "y1")) {
            throw new IllegalArgumentException("a two-point gradient needs two different points");
        }
        return new CgGradient(Shape.TWO_POINT, x0, y0, x1, y1, CgSpread.PAD, true, NO_POSITIONS, NO_COLOURS);
    }

    /** This gradient with one more stop, at {@code position} along it, straight {@code 0xAARRGGBB}. */
    public CgGradient stop(float position, int argb) {
        int n = positions.length;
        float[] p = Arrays.copyOf(positions, n + 1);
        int[] col = Arrays.copyOf(colours, n + 1);
        p[n] = ascend(finite(position, "position"), n == 0 ? 0f : positions[n - 1]);
        col[n] = argb;
        return new CgGradient(shape, a, b, c, d, spread, dither, p, col);
    }

    /** This gradient with {@code positions} and {@code argb} as its stops, replacing any it had. */
    public CgGradient stops(float[] positions, int[] argb) {
        if (positions.length != argb.length) {
            throw new IllegalArgumentException(positions.length + " positions for " + argb.length + " colours");
        }
        float[] p = new float[positions.length];
        for (int i = 0; i < p.length; i++) p[i] = ascend(finite(positions[i], "position"), i == 0 ? 0f : p[i - 1]);
        return new CgGradient(shape, a, b, c, d, spread, dither, p, argb.clone());
    }

    /** This gradient repeating past its stops (CSS's repeating gradients). */
    public CgGradient repeat() {
        return spread(CgSpread.REPEAT);
    }

    /** This gradient repeating past its stops, every other copy reversed. */
    public CgGradient reflect() {
        return spread(CgSpread.REFLECT);
    }

    /** This gradient with {@code spread} past its stops. */
    public CgGradient spread(CgSpread spread) {
        if (spread == null) throw new NullPointerException("spread");
        return new CgGradient(shape, a, b, c, d, spread, dither, positions, colours);
    }

    /**
     * This gradient dithered or not: on by default, since an 8-bit target bands a shallow ramp. Off for a ramp a
     * shader reads as data rather than shows.
     */
    public CgGradient dither(boolean dither) {
        return new CgGradient(shape, a, b, c, d, spread, dither, positions, colours);
    }

    public Shape shape() {
        return shape;
    }

    public CgSpread spread() {
        return spread;
    }

    public boolean dithered() {
        return dither;
    }

    /** How many stops it has. */
    public int stopCount() {
        return positions.length;
    }

    /** Stop {@code i}'s position, 0 to 1, never below the stop before it. */
    public float position(int i) {
        return positions[i];
    }

    /** Stop {@code i}'s colour, straight {@code 0xAARRGGBB}. */
    public int color(int i) {
        return colours[i];
    }

    /**
     * Its geometry's four numbers, by shape: LINEAR the angle in degrees; RADIAL centre x, y and radii x, y; CONIC centre
     * x, y and the start in degrees; TWO_POINT x0, y0, x1, y1. Fractions of the box.
     */
    public float geometry(int i) {
        return switch (i) {
            case 0 -> a;
            case 1 -> b;
            case 2 -> c;
            case 3 -> d;
            default -> throw new IndexOutOfBoundsException("geometry " + i + " of 4");
        };
    }

    /** Whether it can draw: two stops or more. */
    public boolean isComplete() {
        return positions.length >= 2;
    }

    /**
     * The colour at {@code t} along it, its spread applied, straight {@code 0xAARRGGBB}: interpolated premultiplied, as
     * the GPU paints it. For a test, a swatch or a CPU fallback; the GPU never asks.
     */
    public int colorAt(float t) {
        if (!isComplete()) throw new IllegalStateException("a gradient needs at least two stops, has " + positions.length);
        float first = positions[0], last = positions[positions.length - 1];
        t = spreadT(t, first, last);
        if (t <= first) return colours[0];
        int k = 1;
        while (k < positions.length && positions[k] <= t) k++;
        if (k == positions.length) return colours[k - 1];
        float span = Math.max(positions[k] - positions[k - 1], 1e-5f);
        return mixPremultiplied(colours[k - 1], colours[k], (t - positions[k - 1]) / span);
    }

    private float spreadT(float t, float first, float last) {
        float period = last - first;
        if (spread == CgSpread.PAD || period <= 0f) return t;
        float u = (t - first) / period;
        if (spread == CgSpread.REPEAT) return first + (u - (float) Math.floor(u)) * period;
        float m = u - 2f * (float) Math.floor(u * 0.5f);
        return first + (m > 1f ? 2f - m : m) * period;
    }

    private static int mixPremultiplied(int from, int to, float f) {
        float a0 = (from >>> 24) / 255f, a1 = (to >>> 24) / 255f;
        float alpha = a0 + (a1 - a0) * f;
        if (alpha <= 0f) return 0;
        int out = Math.round(alpha * 255f) << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float c0 = ((from >>> shift) & 0xFF) * a0, c1 = ((to >>> shift) & 0xFF) * a1;
            out |= Math.min(255, Math.round((c0 + (c1 - c0) * f) / alpha)) << shift;
        }
        return out;
    }

    private static float ascend(float position, float previous) {
        return Math.max(Math.max(0f, Math.min(1f, position)), previous);
    }

    private static float finite(float v, String name) {
        if (!Float.isFinite(v)) throw new IllegalArgumentException(name + " must be finite: " + v);
        return v;
    }

    private static float positive(float v, String name) {
        if (!(v > 0f) || Float.isInfinite(v)) throw new IllegalArgumentException(name + " must be positive: " + v);
        return v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgGradient g)) return false;
        return shape == g.shape && Float.compare(a, g.a) == 0 && Float.compare(b, g.b) == 0
                && Float.compare(c, g.c) == 0 && Float.compare(d, g.d) == 0 && spread == g.spread
                && dither == g.dither && Arrays.equals(positions, g.positions) && Arrays.equals(colours, g.colours);
    }

    @Override
    public int hashCode() {
        int h = shape.hashCode();
        h = 31 * h + Float.hashCode(a);
        h = 31 * h + Float.hashCode(b);
        h = 31 * h + Float.hashCode(c);
        h = 31 * h + Float.hashCode(d);
        h = 31 * h + spread.hashCode();
        h = 31 * h + Boolean.hashCode(dither);
        h = 31 * h + Arrays.hashCode(positions);
        return 31 * h + Arrays.hashCode(colours);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("CgGradient[").append(shape).append(' ')
                .append(a).append(", ").append(b).append(", ").append(c).append(", ").append(d);
        if (spread != CgSpread.PAD) s.append(' ').append(spread);
        for (int i = 0; i < positions.length; i++) {
            s.append(i == 0 ? "; " : ", ").append(positions[i]).append(String.format(" #%08X", colours[i]));
        }
        return s.append(']').toString();
    }
}
