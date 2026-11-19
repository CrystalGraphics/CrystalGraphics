package com.crystalgraphics.render.ui.svg;


import java.util.ArrayList;
import java.util.List;

/**
 * A stroke's outline as fill contours, for a stroke painted with a gradient: the fill path is what can
 * paint one, so the stroke becomes a fill of its own outline, as usvg and Skia do.
 *
 * <p>Follows Skia's {@code SkStroke} (BSD-3-Clause) for polylines: each side is the path offset by the
 * half-width, the outer side of a joint gets a round join and the inner side runs through the joint's own
 * point, so the loop the inner side makes there is covered under {@code nonzero}. Fill the result with
 * {@code nonzero}, never {@code evenodd}.</p>
 *
 * <pre>{@code
 * List<CgSvgPath.Polyline> outline = CgSvgStroker.outline(contours, halfWidth, CgSvgStyle.CAP_BUTT, 16);
 * CgSvgTessellator.tessellate(outline, false, gradientPaint);
 * }</pre>
 */
final class CgSvgStroker {

    /** Closer than this, two points are one: a zero-length segment has no direction to offset along. */
    private static final float MIN_SEGMENT = 1e-4f;

    private CgSvgStroker() {
    }

    /**
     * @param contours  the stroked path, already in the space the outline is wanted in
     * @param halfWidth half the stroke width, in that space
     * @param cap       a {@link CgSvgStyle} cap, for an open contour's two ends
     * @param steps     segments per quarter turn of a round join or cap, as {@link CgSvgPath#parse(String, int)}
     */
    static List<CgSvgPath.Polyline> outline(List<CgSvgPath.Polyline> contours, float halfWidth, int cap, int steps) {
        List<CgSvgPath.Polyline> out = new ArrayList<>();
        if (halfWidth <= 0f) return out;
        for (CgSvgPath.Polyline contour : contours) {
            List<float[]> points = distinct(contour.points(), contour.closed());
            if (points.size() < 2) continue;
            if (contour.closed() && points.size() >= 3) {
                closed(points, halfWidth, steps, out);
            } else {
                open(points, halfWidth, cap, steps, out);
            }
        }
        return out;
    }

    private static void open(List<float[]> p, float h, int cap, int steps, List<CgSvgPath.Polyline> out) {
        int n = p.size();
        float[][] normals = normals(p, false);
        List<float[]> left = new ArrayList<>();
        List<float[]> right = new ArrayList<>();
        left.add(offset(p.get(0), normals[0], h));
        right.add(offset(p.get(0), normals[0], -h));
        for (int i = 1; i < n - 1; i++) {
            join(p.get(i), normals[i - 1], normals[i], h, steps, left, right);
        }
        float[] last = normals[n - 2];
        left.add(offset(p.get(n - 1), last, h));
        right.add(offset(p.get(n - 1), last, -h));

        List<float[]> ring = new ArrayList<>(left);
        cap(ring, p.get(n - 1), last, h, cap, steps, true);
        for (int i = right.size() - 1; i >= 0; i--) ring.add(right.get(i));
        cap(ring, p.get(0), normals[0], h, cap, steps, false);
        out.add(new CgSvgPath.Polyline(ring, true));
    }

    private static void closed(List<float[]> p, float h, int steps, List<CgSvgPath.Polyline> out) {
        int n = p.size();
        float[][] normals = normals(p, true);
        List<float[]> left = new ArrayList<>();
        List<float[]> right = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            join(p.get(i), normals[(i + n - 1) % n], normals[i], h, steps, left, right);
        }
        List<float[]> reversed = new ArrayList<>(right.size());
        for (int i = right.size() - 1; i >= 0; i--) reversed.add(right.get(i));
        out.add(new CgSvgPath.Polyline(left, true));
        out.add(new CgSvgPath.Polyline(reversed, true));
    }

    /** One joint, into both sides: a round join on the outer side, the pivot on the inner. */
    private static void join(float[] v, float[] na, float[] nb, float h, int steps,
                             List<float[]> left, List<float[]> right) {
        // The turn's sign: the side whose normal points into the turn is the inner one.
        float cross = na[0] * nb[1] - na[1] * nb[0];
        float dot = na[0] * nb[0] + na[1] * nb[1];
        if (Math.abs(cross) < 1e-6f && dot > 0f) {
            left.add(offset(v, nb, h));
            right.add(offset(v, nb, -h));
            return;
        }
        boolean leftOuter = cross < 0f || (Math.abs(cross) < 1e-6f);
        side(v, na, nb, h, steps, left, leftOuter, 1f);
        side(v, na, nb, h, steps, right, !leftOuter, -1f);
    }

    private static void side(float[] v, float[] na, float[] nb, float h, int steps,
                             List<float[]> into, boolean outer, float sign) {
        into.add(offset(v, na, sign * h));
        if (outer) {
            float ux = sign * na[0], uy = sign * na[1];
            float wx = sign * nb[0], wy = sign * nb[1];
            float sweep = (float) Math.atan2(ux * wy - uy * wx, ux * wx + uy * wy);
            arc(into, v, ux * h, uy * h, sweep, steps);
        } else {
            into.add(new float[]{v[0], v[1]});
            into.add(offset(v, nb, sign * h));
        }
    }

    /**
     * An end's cap, appended to the ring between the two sides: at the end, from the left side to the
     * right; at the start, from the right side to the left.
     */
    private static void cap(List<float[]> ring, float[] at, float[] normal, float h, int cap, int steps,
                            boolean end) {
        // The direction the stroke leaves this end in.
        float dx = normal[1], dy = -normal[0];
        if (!end) {
            dx = -dx;
            dy = -dy;
        }
        float from = end ? h : -h;
        if (cap == CgSvgStyle.CAP_SQUARE) {
            ring.add(new float[]{at[0] + normal[0] * from + dx * h, at[1] + normal[1] * from + dy * h});
            ring.add(new float[]{at[0] - normal[0] * from + dx * h, at[1] - normal[1] * from + dy * h});
        } else if (cap == CgSvgStyle.CAP_ROUND) {
            float ux = normal[0] * from, uy = normal[1] * from;
            float sweep = (float) (Math.signum(ux * dy - uy * dx) * Math.PI);
            arc(ring, at, ux, uy, sweep, steps);
            ring.remove(ring.size() - 1);     // the far side's own first point follows
        }
    }

    /** Points on the circle about {@code c} from {@code c + (ux, uy)}, turning {@code sweep}; the start excluded. */
    private static void arc(List<float[]> into, float[] c, float ux, float uy, float sweep, int steps) {
        int count = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2) * Math.max(1, steps)));
        for (int i = 1; i <= count; i++) {
            double a = sweep * i / count;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            into.add(new float[]{c[0] + ux * cos - uy * sin, c[1] + ux * sin + uy * cos});
        }
    }

    /** Each segment's unit left normal; a closed contour has one more, for its closing segment. */
    private static float[][] normals(List<float[]> p, boolean closed) {
        int n = p.size();
        int count = closed ? n : n - 1;
        float[][] normals = new float[count][];
        for (int i = 0; i < count; i++) {
            float[] a = p.get(i), b = p.get((i + 1) % n);
            float dx = b[0] - a[0], dy = b[1] - a[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            normals[i] = new float[]{-dy / len, dx / len};
        }
        return normals;
    }

    private static float[] offset(float[] p, float[] normal, float distance) {
        return new float[]{p[0] + normal[0] * distance, p[1] + normal[1] * distance};
    }

    private static List<float[]> distinct(List<float[]> points, boolean closed) {
        List<float[]> out = new ArrayList<>(points.size());
        for (float[] p : points) {
            if (!out.isEmpty() && near(out.get(out.size() - 1), p)) continue;
            out.add(p);
        }
        if (closed && out.size() > 1 && near(out.get(0), out.get(out.size() - 1))) out.remove(out.size() - 1);
        return out;
    }

    private static boolean near(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) < MIN_SEGMENT && Math.abs(a[1] - b[1]) < MIN_SEGMENT;
    }
}
