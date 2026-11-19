package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.vector.CgStroke;

/**
 * A stroke's trim and dashes laid along flattened contours, before they are outlined. The trim keeps a fraction of
 * the whole path's length, every contour end to end (Skia's {@code SkTrimPathEffect}, Lottie's trim paths); the
 * dashes then cut what it kept, the pattern starting again at each contour's start, shifted by the offset, as SVG and
 * Skia's {@code SkDashPath} do.
 *
 * <pre>{@code
 * List<CgPathFlattener.Contour> pieces = CgPathDash.apply(contours, CgStroke.of(2f).dash(4f, 2f).trim(0f, 0.5f));
 * }</pre>
 *
 * <ul>
 *   <li>A solid, untrimmed stroke answers its contours as given: closed ones stay closed, so their joints all join.</li>
 *   <li>Anything cut answers open pieces; a closed contour is walked from its first point round to it again.</li>
 * </ul>
 */
public final class CgPathDash {

    private CgPathDash() {
    }

    /** {@code contours} trimmed and dashed by {@code stroke}. */
    public static List<CgPathFlattener.Contour> apply(List<CgPathFlattener.Contour> contours, CgStroke stroke) {
        boolean trimmed = stroke.trimStart() > 0f || stroke.trimEnd() < 1f;
        if (!trimmed && !stroke.isDashed()) return contours;
        List<CgPathFlattener.Contour> pieces = contours;
        if (trimmed) pieces = trim(contours, stroke.trimStart(), stroke.trimEnd());
        if (stroke.isDashed()) pieces = dash(pieces, stroke.dashes(), stroke.dashOffset());
        return pieces;
    }

    /** The stretch from {@code start} to {@code end} of the whole length, as open pieces. */
    static List<CgPathFlattener.Contour> trim(List<CgPathFlattener.Contour> contours, float start, float end) {
        float total = 0f;
        for (CgPathFlattener.Contour c : contours) total += length(c);
        List<CgPathFlattener.Contour> out = new ArrayList<>();
        if (!(total > 0f) || end <= start) return out;
        float from = start * total, to = end * total, walked = 0f;
        for (CgPathFlattener.Contour c : contours) {
            float len = length(c);
            float a = Math.max(from - walked, 0f), b = Math.min(to - walked, len);
            if (b > a) out.add(new CgPathFlattener.Contour(cut(c, a, b), false));
            walked += len;
        }
        return out;
    }

    /** {@code contours} cut by the on and off lengths of {@code dashes}, starting {@code offset} into the pattern. */
    static List<CgPathFlattener.Contour> dash(List<CgPathFlattener.Contour> contours, float[] dashes, float offset) {
        float period = 0f;
        for (float d : dashes) period += d;
        List<CgPathFlattener.Contour> out = new ArrayList<>();
        for (CgPathFlattener.Contour c : contours) {
            float len = length(c);
            // Where along the contour the pattern's current dash began, and which dash it is
            float phase = ((offset % period) + period) % period;
            int i = 0;
            // Strictly past: a phase landing on a zero-length dash keeps it, so a dotted line starts with its dot
            while (phase > dashes[i]) {
                phase -= dashes[i];
                i = (i + 1) % dashes.length;
            }
            float at = -phase;
            while (at < len) {
                float next = at + dashes[i];
                if (i % 2 == 0 && dashes[i] == 0f) {
                    // A zero-length dash: a point, which a round or square cap draws as a dot
                    if (at >= 0f) {
                        List<float[]> dot = new ArrayList<>();
                        dot.add(pointAt(c, at));
                        out.add(new CgPathFlattener.Contour(dot, false));
                    }
                } else if (i % 2 == 0 && next > 0f) {
                    float a = Math.max(at, 0f), b = Math.min(next, len);
                    if (b > a) out.add(new CgPathFlattener.Contour(cut(c, a, b), false));
                }
                at = next;
                i = (i + 1) % dashes.length;
            }
        }
        return out;
    }

    /** The contour's length, its closing segment counted when it is closed. */
    static float length(CgPathFlattener.Contour c) {
        List<float[]> p = c.points();
        float len = 0f;
        for (int i = 1; i < p.size(); i++) len += distance(p.get(i - 1), p.get(i));
        if (c.closed() && p.size() > 1) len += distance(p.get(p.size() - 1), p.get(0));
        return len;
    }

    /** The points between {@code a} and {@code b} along the contour, ends interpolated. */
    private static List<float[]> cut(CgPathFlattener.Contour c, float a, float b) {
        List<float[]> p = c.points();
        int n = p.size(), segments = c.closed() ? n : n - 1;
        List<float[]> out = new ArrayList<>();
        float walked = 0f;
        for (int s = 0; s < segments; s++) {
            float[] p0 = p.get(s), p1 = p.get((s + 1) % n);
            float len = distance(p0, p1);
            float s0 = walked, s1 = walked + len;
            if (s1 >= a && s0 <= b && len > 0f) {
                if (out.isEmpty()) out.add(lerp(p0, p1, (Math.max(a, s0) - s0) / len));
                out.add(lerp(p0, p1, (Math.min(b, s1) - s0) / len));
            }
            walked = s1;
            if (walked > b) break;
        }
        return out;
    }

    /** The point {@code at} along the contour. */
    private static float[] pointAt(CgPathFlattener.Contour c, float at) {
        List<float[]> piece = cut(c, at, at);
        return piece.isEmpty() ? c.points().get(0) : piece.get(0);
    }

    private static float[] lerp(float[] p0, float[] p1, float t) {
        return new float[] {p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t};
    }

    private static float distance(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
