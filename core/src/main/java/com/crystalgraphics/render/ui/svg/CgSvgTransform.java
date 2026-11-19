package com.crystalgraphics.render.ui.svg;

/**
 * A 2D affine transform, in SVG's own {@code matrix(a b c d e f)} order.
 *
 * <p>Six floats rather than a {@code Matrix4f}: SVG states every transform in exactly this form —
 * {@code translate}, {@code scale}, {@code rotate}, {@code skewX} and {@code skewY} are all defined as
 * matrices of it — so working in the same terms means the parser transcribes rather than converts, and a
 * mistake is visible as a wrong letter instead of a wrong basis.</p>
 *
 * <pre>
 *   x' = a·x + c·y + e
 *   y' = b·x + d·y + f
 * </pre>
 */
public record CgSvgTransform(float a, float b, float c, float d, float e, float f) {

    public static final CgSvgTransform IDENTITY = new CgSvgTransform(1, 0, 0, 1, 0, 0);

    /** {@code this} then {@code next} — the order a nested {@code <g>} composes in. */
    public CgSvgTransform then(CgSvgTransform next) {
        return new CgSvgTransform(
                a * next.a + b * next.c,
                a * next.b + b * next.d,
                c * next.a + d * next.c,
                c * next.b + d * next.d,
                e * next.a + f * next.c + next.e,
                e * next.b + f * next.d + next.f);
    }

    public float applyX(float x, float y) {
        return a * x + c * y + e;
    }

    public float applyY(float x, float y) {
        return b * x + d * y + f;
    }

    /**
     * Maps a <b>gradient covector</b> through this transform — the inverse transpose of the linear part.
     *
     * <h3>Why a linear gradient's direction does not transform like a direction</h3>
     *
     * <p>A linear gradient is an affine scalar function of position: {@code t(p) = dot(g, p - origin)}.
     * Composing it with this transform's inverse — which is what "state the same ramp in the space this
     * maps to" means — gives {@code t(q) = dot(A⁻ᵀ g, q - T(origin))}. So the ramp stays exactly
     * expressible as an origin and a direction in the new space, but the direction is {@code A⁻ᵀ g} and
     * <b>not</b> the transformed axis.</p>
     *
     * <p>The two agree for any similarity — rotation, uniform scale, translation — which is every
     * transform real icon artwork uses, and that is why taking the transformed axis instead looks correct
     * almost always. They diverge under a skew or a non-uniform scale, where the ramp's iso-lines are no
     * longer perpendicular to the axis joining its endpoints. Using this keeps the ramp exact for every
     * affine transform at the cost of one division, so there is no case left to get wrong.</p>
     *
     * <p>Degenerate transforms (a collapsed determinant) return the input unchanged rather than dividing
     * by zero: the shape has no area in that space, so nothing will be sampled from the ramp anyway.</p>
     *
     * @return a fresh {@code [x, y]}
     */
    public float[] mapCovector(float gx, float gy) {
        float det = a * d - b * c;
        if (Math.abs(det) < 1e-12f) return new float[]{gx, gy};
        return new float[]{(d * gx - b * gy) / det, (-c * gx + a * gy) / det};
    }

    /**
     * How much this scales lengths, for scaling a stroke width with its shape.
     *
     * <p>The geometric mean of the two axis scales — {@code sqrt(|ad - bc|)}, the square root of the
     * determinant. A non-uniform scale genuinely makes a stroke elliptical, which a single width cannot
     * express; SVG itself approximates here, and so does every renderer that does not build the stroke as
     * a filled outline.</p>
     */
    public float lengthScale() {
        return (float) Math.sqrt(Math.abs(a * d - b * c));
    }

    /**
     * Parses a {@code transform} attribute — a whitespace-separated list whose <b>rightmost</b> function
     * reaches the point first.
     *
     * <p>That is the spec's matrix product: {@code translate(a) scale(b)} is {@code T·S}, and a point is
     * {@code T(S(p))} — scaled, then moved. It used to be read the other way round, applying the LEFTMOST
     * first, and nothing in the shipped set could tell: every icon in it carries at most one function.
     * The idiom that can tell is scaling about a point — {@code translate(36 32) scale(0.42)
     * translate(-32 -32)}, which is what Illustrator and Inkscape write for a nested group — and read
     * leftmost-first it put the group at {@code (-3, -5)}, off the canvas, with nothing to say why.</p>
     *
     * <p>{@code translate}, {@code scale}, {@code rotate} (with and without a centre), {@code matrix},
     * {@code skewX} and {@code skewY}. That is the whole of SVG's transform vocabulary.</p>
     */
    public static CgSvgTransform parse(String raw) {
        CgSvgTransform result = IDENTITY;
        if (raw == null || raw.isBlank()) return result;

        int at = 0;
        while (at < raw.length()) {
            int open = raw.indexOf('(', at);
            if (open < 0) break;
            int close = raw.indexOf(')', open);
            if (close < 0) break;

            String name = raw.substring(at, open).trim();
            // A leading comma or whitespace from the previous function is part of the separator, not the
            // name -- `translate(1 2) scale(3)` would otherwise read the second name as " scale".
            int nameStart = 0;
            while (nameStart < name.length() && !Character.isLetter(name.charAt(nameStart))) nameStart++;
            name = name.substring(nameStart);

            float[] args = numbers(raw.substring(open + 1, close));
            // This function is applied BEFORE everything already parsed to its left -- see the javadoc.
            result = build(name, args).then(result);
            at = close + 1;
        }
        return result;
    }

    private static CgSvgTransform build(String name, float[] v) {
        switch (name) {
            case "translate":
                return new CgSvgTransform(1, 0, 0, 1, at(v, 0), at(v, 1));
            case "scale": {
                float sx = v.length > 0 ? v[0] : 1f;
                // scale(2) means both axes, not "x only" -- a one-argument scale that only scaled x would
                // squash every icon using the shorthand.
                float sy = v.length > 1 ? v[1] : sx;
                return new CgSvgTransform(sx, 0, 0, sy, 0, 0);
            }
            case "rotate": {
                double angle = Math.toRadians(at(v, 0));
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                CgSvgTransform rotation = new CgSvgTransform(cos, sin, -sin, cos, 0, 0);
                if (v.length < 3) return rotation;
                // rotate(a cx cy) is a rotation ABOUT a point: translate there, rotate, translate back.
                CgSvgTransform toOrigin = new CgSvgTransform(1, 0, 0, 1, -v[1], -v[2]);
                CgSvgTransform back = new CgSvgTransform(1, 0, 0, 1, v[1], v[2]);
                return toOrigin.then(rotation).then(back);
            }
            case "matrix":
                return v.length >= 6 ? new CgSvgTransform(v[0], v[1], v[2], v[3], v[4], v[5]) : IDENTITY;
            case "skewX":
                return new CgSvgTransform(1, 0, (float) Math.tan(Math.toRadians(at(v, 0))), 1, 0, 0);
            case "skewY":
                return new CgSvgTransform(1, (float) Math.tan(Math.toRadians(at(v, 0))), 0, 1, 0, 0);
            default:
                return IDENTITY;
        }
    }

    private static float at(float[] values, int index) {
        return index < values.length ? values[index] : 0f;
    }

    private static float[] numbers(String raw) {
        String[] parts = raw.trim().split("[\\s,]+");
        float[] out = new float[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                out[count++] = Float.parseFloat(part);
            } catch (NumberFormatException skip) {
                // A malformed argument loses one number, not the whole transform.
            }
        }
        float[] trimmed = new float[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }
}
