package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.vector.CgVectorPath;

/**
 * A path flattened into polylines in its own units, as finely as it is drawn: each curve takes as many segments as
 * Wang's formula says keep it within {@link #TOLERANCE} device pixels of the true curve under the canvas's affine,
 * the count Skia's {@code wangs_formula} (BSD-3-Clause) computes. A stroke outlines the local points, so a
 * non-uniform scale shapes its pen; {@link #transform} then takes either to device pixels.
 *
 * <pre>{@code
 * List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(path, a, b, c, d, tx, ty);
 * List<CgPathFlattener.Contour> device = CgPathFlattener.transform(local, a, b, c, d, tx, ty);
 * }</pre>
 */
public final class CgPathFlattener {

    /** Device pixels a flattened curve may stray from the true one: Skia's default precision, a quarter pixel. */
    public static final float TOLERANCE = 0.25f;

    /** The most segments one curve takes, whatever it asks. */
    private static final int MAX_SEGMENTS = 1024;

    /** One subpath's points; {@code closed} when it said so. */
    public record Contour(List<float[]> points, boolean closed) {
    }

    private CgPathFlattener() {
    }

    /** {@code path}'s contours in its own units, flattened for drawing under {@code x' = a x + c y + tx, y' = b x + d y + ty}. */
    public static List<Contour> flatten(CgVectorPath path, float a, float b, float c, float d, float tx, float ty) {
        Flattening f = new Flattening(a, b, c, d, tx, ty);
        path.forEach(f);
        f.end(false);
        return f.out;
    }

    /** {@code contours} under the affine, as new points. */
    public static List<Contour> transform(List<Contour> contours, float a, float b, float c, float d, float tx, float ty) {
        List<Contour> out = new ArrayList<>(contours.size());
        for (Contour contour : contours) {
            List<float[]> points = new ArrayList<>(contour.points().size());
            for (float[] q : contour.points()) points.add(new float[] {a * q[0] + c * q[1] + tx, b * q[0] + d * q[1] + ty});
            out.add(new Contour(points, contour.closed()));
        }
        return out;
    }

    /** Segments a quadratic with these device points needs: Wang's formula, degree 2. */
    static int quadSegments(float x0, float y0, float x1, float y1, float x2, float y2) {
        float dx = x0 - 2 * x1 + x2, dy = y0 - 2 * y1 + y2;
        return segments(0.25f * (float) Math.sqrt(dx * dx + dy * dy));
    }

    /** Segments a cubic with these device points needs: Wang's formula, degree 3. */
    static int cubicSegments(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        float ax = x0 - 2 * x1 + x2, ay = y0 - 2 * y1 + y2;
        float bx = x1 - 2 * x2 + x3, by = y1 - 2 * y2 + y3;
        float m = (float) Math.sqrt(Math.max(ax * ax + ay * ay, bx * bx + by * by));
        return segments(0.75f * m);
    }

    /** {@code ceil(sqrt(k / tolerance))}, clamped: k is the degree's constant times the second differences' norm. */
    private static int segments(float k) {
        float n = (float) Math.ceil(Math.sqrt(k / TOLERANCE));
        return n < 1f ? 1 : (int) Math.min(n, MAX_SEGMENTS);
    }

    private static final class Flattening implements CgVectorPath.Visitor {

        private final float a, b, c, d, tx, ty;
        final List<Contour> out = new ArrayList<>();
        private List<float[]> points = new ArrayList<>();
        private float lastX, lastY, startX, startY;

        Flattening(float a, float b, float c, float d, float tx, float ty) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.tx = tx;
            this.ty = ty;
        }

        private float x(float px, float py) {
            return a * px + c * py + tx;
        }

        private float y(float px, float py) {
            return b * px + d * py + ty;
        }

        @Override
        public void moveTo(float px, float py) {
            end(false);
            lastX = startX = px;
            lastY = startY = py;
            points.add(new float[] {lastX, lastY});
        }

        @Override
        public void lineTo(float px, float py) {
            add(px, py);
        }

        @Override
        public void quadTo(float x1, float y1, float x2, float y2) {
            float x0 = lastX, y0 = lastY;
            // Counted on the device points, evaluated on the local ones: Wang's count is affine-invariant once mapped
            int n = quadSegments(x(x0, y0), y(x0, y0), x(x1, y1), y(x1, y1), x(x2, y2), y(x2, y2));
            for (int i = 1; i <= n; i++) {
                float t = i / (float) n, u = 1 - t;
                add(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2);
            }
        }

        @Override
        public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            float x0 = lastX, y0 = lastY;
            int n = cubicSegments(x(x0, y0), y(x0, y0), x(x1, y1), y(x1, y1), x(x2, y2), y(x2, y2), x(x3, y3), y(x3, y3));
            for (int i = 1; i <= n; i++) {
                float t = i / (float) n, u = 1 - t;
                float w0 = u * u * u, w1 = 3 * u * u * t, w2 = 3 * u * t * t, w3 = t * t * t;
                add(w0 * x0 + w1 * x1 + w2 * x2 + w3 * x3, w0 * y0 + w1 * y1 + w2 * y2 + w3 * y3);
            }
        }

        @Override
        public void close() {
            end(true);
            // A path drawing on after a close starts again where the closed subpath began
            lastX = startX;
            lastY = startY;
        }

        private void add(float px, float py) {
            if (points.isEmpty()) points.add(new float[] {lastX, lastY});
            points.add(new float[] {px, py});
            lastX = px;
            lastY = py;
        }

        void end(boolean closed) {
            if (points.size() >= 2 || closed && !points.isEmpty()) out.add(new Contour(points, closed));
            points = new ArrayList<>();
        }
    }
}
