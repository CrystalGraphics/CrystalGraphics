package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeCap;
import com.crystalgraphics.api.vector.CgStrokeJoin;

/**
 * A stroke's outline as contours to fill with the nonzero rule: the stroke becomes a fill of its own outline, as Skia
 * and usvg do. Follows Skia's {@code SkStroke} and its joiners (BSD-3-Clause) for polylines: each side is the path
 * offset by the half-width; the outer side of a joint takes the join (a miter cut to a bevel past the limit, a bevel,
 * an arc); the inner side runs through the joint's own point, so the loop it makes there is covered under nonzero.
 *
 * <pre>{@code
 * List<CgPathFlattener.Contour> outline = CgPathStroker.outline(local, stroke, tolerance);
 * }</pre>
 *
 * <ul>
 *   <li>Works in the path's own units, so a non-uniform scale after it shapes the pen; {@code tolerance} is the chord
 *       error allowed in those units.</li>
 *   <li>The trim and the dashes are laid along the contours first ({@link CgPathDash}).</li>
 * </ul>
 *
 * <p>Ported from CrystalGUI's {@code SvgStroker}, its miter and bevel joins added.</p>
 */
public final class CgPathStroker {

    /** Closer than this, two points are one: a zero-length segment has no direction to offset along. */
    private static final float MIN_SEGMENT = 1e-4f;

    private CgPathStroker() {
    }

    /** {@code contours} stroked by {@code stroke}, as fill contours in the same units. */
    public static List<CgPathFlattener.Contour> outline(List<CgPathFlattener.Contour> contours, CgStroke stroke,
                                                        float tolerance) {
        return outline(CgPathDash.apply(contours, stroke), stroke.width() * 0.5f, stroke.cap(), stroke.join(),
                stroke.miterLimit(), tolerance);
    }

    /** As {@link #outline(List, CgStroke, float)}, the dashes and trim already laid; {@code halfWidth} the pen's. */
    static List<CgPathFlattener.Contour> outline(List<CgPathFlattener.Contour> contours, float halfWidth, CgStrokeCap cap,
                                                 CgStrokeJoin join, float miterLimit, float tolerance) {
        List<CgPathFlattener.Contour> out = new ArrayList<>();
        if (!(halfWidth > 0f)) return out;
        Pen pen = new Pen(halfWidth, join, miterLimit, tolerance);
        for (CgPathFlattener.Contour contour : contours) {
            List<float[]> points = distinct(contour.points(), contour.closed());
            if (points.size() < 2) {
                // A dot: a round or square cap still marks it, as SVG draws a zero-length subpath
                if (!points.isEmpty() && cap != CgStrokeCap.BUTT) dot(points.get(0), pen, cap, out);
                continue;
            }
            if (contour.closed() && points.size() >= 3) closed(points, pen, out);
            else open(points, pen, cap, out);
        }
        return out;
    }

    /** The pen: its half-width, join and how finely its arcs are cut. */
    private record Pen(float h, CgStrokeJoin join, float miterLimit, float tolerance) {

        /** Segments for an arc of {@code sweep} radians at the pen's radius, its chords within the tolerance. */
        int arcSegments(float sweep) {
            float t = Math.min(tolerance, h);
            double perSegment = 2 * Math.acos(1 - t / h);
            return Math.max(1, (int) Math.ceil(Math.abs(sweep) / Math.max(perSegment, 1e-3)));
        }
    }

    private static void open(List<float[]> p, Pen pen, CgStrokeCap cap, List<CgPathFlattener.Contour> out) {
        int n = p.size();
        float h = pen.h();
        float[][] normals = normals(p, false);
        List<float[]> left = new ArrayList<>();
        List<float[]> right = new ArrayList<>();
        left.add(offset(p.get(0), normals[0], h));
        right.add(offset(p.get(0), normals[0], -h));
        for (int i = 1; i < n - 1; i++) join(p.get(i), normals[i - 1], normals[i], pen, left, right);
        float[] last = normals[n - 2];
        left.add(offset(p.get(n - 1), last, h));
        right.add(offset(p.get(n - 1), last, -h));

        List<float[]> ring = new ArrayList<>(left);
        cap(ring, p.get(n - 1), last, pen, cap, true);
        for (int i = right.size() - 1; i >= 0; i--) ring.add(right.get(i));
        cap(ring, p.get(0), normals[0], pen, cap, false);
        out.add(new CgPathFlattener.Contour(ring, true));
    }

    private static void closed(List<float[]> p, Pen pen, List<CgPathFlattener.Contour> out) {
        int n = p.size();
        float[][] normals = normals(p, true);
        List<float[]> left = new ArrayList<>();
        List<float[]> right = new ArrayList<>();
        for (int i = 0; i < n; i++) join(p.get(i), normals[(i + n - 1) % n], normals[i], pen, left, right);
        List<float[]> reversed = new ArrayList<>(right.size());
        for (int i = right.size() - 1; i >= 0; i--) reversed.add(right.get(i));
        out.add(new CgPathFlattener.Contour(left, true));
        out.add(new CgPathFlattener.Contour(reversed, true));
    }

    /** One joint, into both sides: the join on the outer side, the pivot on the inner. */
    private static void join(float[] v, float[] na, float[] nb, Pen pen, List<float[]> left, List<float[]> right) {
        float h = pen.h();
        // The turn's sign: the side whose normal points into the turn is the inner one
        float cross = na[0] * nb[1] - na[1] * nb[0];
        float dot = na[0] * nb[0] + na[1] * nb[1];
        if (Math.abs(cross) < 1e-6f && dot > 0f) {
            left.add(offset(v, nb, h));
            right.add(offset(v, nb, -h));
            return;
        }
        boolean leftOuter = cross < 0f || Math.abs(cross) < 1e-6f;
        side(v, na, nb, pen, left, leftOuter, 1f);
        side(v, na, nb, pen, right, !leftOuter, -1f);
    }

    private static void side(float[] v, float[] na, float[] nb, Pen pen, List<float[]> into, boolean outer, float sign) {
        float h = pen.h();
        into.add(offset(v, na, sign * h));
        if (!outer) {
            into.add(new float[] {v[0], v[1]});
            into.add(offset(v, nb, sign * h));
            return;
        }
        float ux = sign * na[0], uy = sign * na[1];
        float wx = sign * nb[0], wy = sign * nb[1];
        switch (pen.join()) {
            case ROUND -> arc(into, v, ux * h, uy * h, (float) Math.atan2(ux * wy - uy * wx, ux * wx + uy * wy), pen);
            case MITER -> {
                // Skia's MiterJoiner: the point where the two offset sides meet, unless the ratio of its distance
                // from the joint to the half-width passes the limit, which bevels
                float mx = ux + wx, my = uy + wy;
                float len2 = mx * mx + my * my;
                float ratio = len2 > 1e-12f ? 2f / (float) Math.sqrt(len2) : Float.POSITIVE_INFINITY;
                if (ratio <= pen.miterLimit()) into.add(new float[] {v[0] + mx * 2f * h / len2, v[1] + my * 2f * h / len2});
                into.add(offset(v, nb, sign * h));
            }
            case BEVEL -> into.add(offset(v, nb, sign * h));
        }
    }

    /**
     * An end's cap, appended to the ring between the two sides: at the end, from the left side to the right; at the
     * start, from the right side to the left.
     */
    private static void cap(List<float[]> ring, float[] at, float[] normal, Pen pen, CgStrokeCap cap, boolean end) {
        float h = pen.h();
        // The direction the stroke leaves this end in
        float dx = normal[1], dy = -normal[0];
        if (!end) {
            dx = -dx;
            dy = -dy;
        }
        float from = end ? h : -h;
        if (cap == CgStrokeCap.SQUARE) {
            ring.add(new float[] {at[0] + normal[0] * from + dx * h, at[1] + normal[1] * from + dy * h});
            ring.add(new float[] {at[0] - normal[0] * from + dx * h, at[1] - normal[1] * from + dy * h});
        } else if (cap == CgStrokeCap.ROUND) {
            float ux = normal[0] * from, uy = normal[1] * from;
            float sweep = (float) (Math.signum(ux * dy - uy * dx) * Math.PI);
            arc(ring, at, ux, uy, sweep, pen);
            ring.remove(ring.size() - 1);     // the far side's own first point follows
        }
    }

    /** A zero-length subpath: a disc for a round cap, a square for a square one. */
    private static void dot(float[] at, Pen pen, CgStrokeCap cap, List<CgPathFlattener.Contour> out) {
        float h = pen.h();
        List<float[]> ring = new ArrayList<>();
        if (cap == CgStrokeCap.SQUARE) {
            ring.add(new float[] {at[0] - h, at[1] - h});
            ring.add(new float[] {at[0] + h, at[1] - h});
            ring.add(new float[] {at[0] + h, at[1] + h});
            ring.add(new float[] {at[0] - h, at[1] + h});
        } else {
            ring.add(new float[] {at[0] + h, at[1]});
            arc(ring, at, h, 0f, (float) (2 * Math.PI), pen);
            ring.remove(ring.size() - 1);
        }
        out.add(new CgPathFlattener.Contour(ring, true));
    }

    /** Points on the circle about {@code c} from {@code c + (ux, uy)}, turning {@code sweep}; the start excluded. */
    private static void arc(List<float[]> into, float[] c, float ux, float uy, float sweep, Pen pen) {
        int count = pen.arcSegments(sweep);
        for (int i = 1; i <= count; i++) {
            double a = sweep * i / count;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            into.add(new float[] {c[0] + ux * cos - uy * sin, c[1] + ux * sin + uy * cos});
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
            normals[i] = new float[] {-dy / len, dx / len};
        }
        return normals;
    }

    private static float[] offset(float[] p, float[] normal, float distance) {
        return new float[] {p[0] + normal[0] * distance, p[1] + normal[1] * distance};
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
