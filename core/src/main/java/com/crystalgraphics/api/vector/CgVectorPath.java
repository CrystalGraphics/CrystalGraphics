package com.crystalgraphics.api.vector;

import java.util.Arrays;
import java.util.Objects;

/**
 * A vector shape: contours of lines and curves, filled by a {@link CgFillRule} or outlined by a {@link CgStroke}. Skia's
 * {@code SkPath}, SVG's path data. A value: built once, kept, shared on any thread, compared by value.
 *
 * <pre>{@code
 * static final CgVectorPath TICK = CgVectorPath.builder()
 *         .moveTo(2, 8).lineTo(6, 12).lineTo(14, 4)
 *         .build();
 * static final CgVectorPath BADGE = CgVectorPath.builder()
 *         .addRoundRect(0, 0, 24, 16, CgCornerRadii.of(4))
 *         .addCircle(20, 4, 3)
 *         .fillRule(CgFillRule.EVEN_ODD)                                 // the circle cuts a hole
 *         .build();
 * static final CgVectorPath WAVE = CgVectorPath.builder()
 *         .moveTo(0, 8).cubicTo(4, 0, 12, 16, 16, 8).build();
 *
 * canvas.fill(BADGE, 0xFF3070F0);
 * canvas.stroke(TICK, CgStroke.of(2f).cap(CgStrokeCap.ROUND), 0xFFFFFFFF);
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are in the units it is drawn in, y down.</li>
 *   <li>Arcs are stored as cubic Béziers, a quarter turn or less each: exact to well under a pixel at any size an
 *       interface draws.</li>
 *   <li>A stroke reads open contours as open; {@code close()} joins a contour's end to its start.</li>
 * </ul>
 */
public final class CgVectorPath {

    /** One step of a contour. */
    public enum Verb {
        /** Starts a contour at one point. */
        MOVE(1),
        /** A straight line to one point. */
        LINE(1),
        /** A quadratic Bézier: a control point, then its end. */
        QUAD(2),
        /** A cubic Bézier: two control points, then its end. */
        CUBIC(3),
        /** Joins the contour's end to its start. */
        CLOSE(0);

        /** How many points it takes. */
        public final int points;

        Verb(int points) {
            this.points = points;
        }
    }

    /** What {@link #forEach} hands each step to. */
    public interface Visitor {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void quadTo(float cx, float cy, float x, float y);

        void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

        void close();
    }

    private static final Verb[] VERBS = Verb.values();

    /** Nothing: draws nothing. */
    public static final CgVectorPath EMPTY = builder().build();

    private final byte[] verbs;
    private final float[] points;
    private final CgFillRule fillRule;
    private final float minX, minY, maxX, maxY;

    private CgVectorPath(byte[] verbs, float[] points, CgFillRule fillRule) {
        this.verbs = verbs;
        this.points = points;
        this.fillRule = fillRule;
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 2) {
            x0 = Math.min(x0, points[i]);
            y0 = Math.min(y0, points[i + 1]);
            x1 = Math.max(x1, points[i]);
            y1 = Math.max(y1, points[i + 1]);
        }
        boolean empty = points.length == 0;
        this.minX = empty ? 0f : x0;
        this.minY = empty ? 0f : y0;
        this.maxX = empty ? 0f : x1;
        this.maxY = empty ? 0f : y1;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Walks its steps in order. */
    public void forEach(Visitor v) {
        int p = 0;
        for (byte b : verbs) {
            switch (VERBS[b]) {
                case MOVE -> v.moveTo(points[p], points[p + 1]);
                case LINE -> v.lineTo(points[p], points[p + 1]);
                case QUAD -> v.quadTo(points[p], points[p + 1], points[p + 2], points[p + 3]);
                case CUBIC -> v.cubicTo(points[p], points[p + 1], points[p + 2], points[p + 3], points[p + 4],
                        points[p + 5]);
                case CLOSE -> v.close();
            }
            p += VERBS[b].points * 2;
        }
    }

    public CgFillRule fillRule() {
        return fillRule;
    }

    /** This shape under {@code rule}. */
    public CgVectorPath fillRule(CgFillRule rule) {
        return rule == fillRule ? this : new CgVectorPath(verbs, points, Objects.requireNonNull(rule, "rule"));
    }

    /** How many steps it has. */
    public int verbCount() {
        return verbs.length;
    }

    public Verb verb(int i) {
        return VERBS[verbs[i]];
    }

    public boolean isEmpty() {
        return verbs.length == 0;
    }

    /** Its control points' bounds, which hold the curves: left. */
    public float minX() {
        return minX;
    }

    public float minY() {
        return minY;
    }

    public float maxX() {
        return maxX;
    }

    public float maxY() {
        return maxY;
    }

    /** This shape moved by ({@code dx}, {@code dy}) and scaled about the origin by ({@code sx}, {@code sy}) first. */
    public CgVectorPath transformed(float sx, float sy, float dx, float dy) {
        float[] p = new float[points.length];
        for (int i = 0; i < p.length; i += 2) {
            p[i] = points[i] * sx + dx;
            p[i + 1] = points[i + 1] * sy + dy;
        }
        return new CgVectorPath(verbs, p, fillRule);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgVectorPath p && fillRule == p.fillRule && Arrays.equals(verbs, p.verbs)
                && Arrays.equals(points, p.points);
    }

    @Override
    public int hashCode() {
        // Kept: a path is a cache key looked up every frame it draws
        int h = hash;
        if (h == 0) hash = h = 31 * (31 * fillRule.hashCode() + Arrays.hashCode(verbs)) + Arrays.hashCode(points);
        return h;
    }

    private int hash;

    @Override
    public String toString() {
        return "CgVectorPath[" + verbs.length + " steps, " + fillRule + ", " + minX + "," + minY + " .. " + maxX + ","
                + maxY + ']';
    }

    /** Builds a {@link CgVectorPath}. Not thread-safe; the path it builds is. */
    public static final class Builder {

        private byte[] verbs = new byte[16];
        private float[] points = new float[32];
        private int verbCount, pointCount;
        private CgFillRule fillRule = CgFillRule.NON_ZERO;
        private boolean open;
        private float startX, startY, lastX, lastY;

        private Builder() {
        }

        /** Starts a contour at ({@code x}, {@code y}). */
        public Builder moveTo(float x, float y) {
            verb(Verb.MOVE);
            point(x, y);
            open = true;
            startX = lastX = x;
            startY = lastY = y;
            return this;
        }

        /** A line to ({@code x}, {@code y}). */
        public Builder lineTo(float x, float y) {
            ensureOpen();
            verb(Verb.LINE);
            point(x, y);
            return this;
        }

        /** A quadratic Bézier through control ({@code cx}, {@code cy}) to ({@code x}, {@code y}). */
        public Builder quadTo(float cx, float cy, float x, float y) {
            ensureOpen();
            verb(Verb.QUAD);
            point(cx, cy);
            point(x, y);
            return this;
        }

        /** A cubic Bézier through controls ({@code c1x}, {@code c1y}) and ({@code c2x}, {@code c2y}) to ({@code x}, {@code y}). */
        public Builder cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
            ensureOpen();
            verb(Verb.CUBIC);
            point(c1x, c1y);
            point(c2x, c2y);
            point(x, y);
            return this;
        }

        /**
         * SVG's elliptical arc to ({@code x}, {@code y}): radii, the ellipse's rotation in degrees, which of the four
         * arcs ({@code largeArc}, {@code sweep}). Radii too small to reach are grown, as SVG does; a zero radius is a line.
         */
        public Builder arcTo(float rx, float ry, float rotationDegrees, boolean largeArc, boolean sweep, float x, float y) {
            ensureOpen();
            float x0 = lastX, y0 = lastY;
            if (x0 == x && y0 == y) return this;
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            if (rx == 0f || ry == 0f) return lineTo(x, y);
            double phi = Math.toRadians(rotationDegrees), cos = Math.cos(phi), sin = Math.sin(phi);
            // SVG 1.1 appendix F.6.5: the endpoint form to the centre form.
            double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
            double x1p = cos * dx2 + sin * dy2, y1p = -sin * dx2 + cos * dy2;
            double lambda = (x1p * x1p) / ((double) rx * rx) + (y1p * y1p) / ((double) ry * ry);
            double rxs = rx, rys = ry;
            if (lambda > 1) {
                rxs *= Math.sqrt(lambda);
                rys *= Math.sqrt(lambda);
            }
            double num = rxs * rxs * rys * rys - rxs * rxs * y1p * y1p - rys * rys * x1p * x1p;
            double den = rxs * rxs * y1p * y1p + rys * rys * x1p * x1p;
            double coef = (largeArc == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
            double cxp = coef * rxs * y1p / rys, cyp = -coef * rys * x1p / rxs;
            double cx = cos * cxp - sin * cyp + (x0 + x) / 2.0, cy = sin * cxp + cos * cyp + (y0 + y) / 2.0;
            double theta = angle(1, 0, (x1p - cxp) / rxs, (y1p - cyp) / rys);
            double delta = angle((x1p - cxp) / rxs, (y1p - cyp) / rys, (-x1p - cxp) / rxs, (-y1p - cyp) / rys);
            if (!sweep && delta > 0) delta -= 2 * Math.PI;
            if (sweep && delta < 0) delta += 2 * Math.PI;
            int segments = Math.max(1, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9));
            double step = delta / segments;
            double k = 4.0 / 3.0 * Math.tan(step / 4);
            double t = theta;
            for (int i = 0; i < segments; i++) {
                double t2 = t + step;
                double c1 = Math.cos(t), s1 = Math.sin(t), c2 = Math.cos(t2), s2 = Math.sin(t2);
                double p1x = c1 - k * s1, p1y = s1 + k * c1, p2x = c2 + k * s2, p2y = s2 - k * c2;
                boolean last = i == segments - 1;
                cubicTo(ex(cx, rxs, rys, cos, sin, p1x, p1y), ey(cy, rxs, rys, cos, sin, p1x, p1y),
                        ex(cx, rxs, rys, cos, sin, p2x, p2y), ey(cy, rxs, rys, cos, sin, p2x, p2y),
                        last ? x : ex(cx, rxs, rys, cos, sin, c2, s2), last ? y : ey(cy, rxs, rys, cos, sin, c2, s2));
                t = t2;
            }
            return this;
        }

        /** Joins the contour's end to its start. */
        public Builder close() {
            if (open) {
                verb(Verb.CLOSE);
                open = false;
                lastX = startX;
                lastY = startY;
            }
            return this;
        }

        /** A closed rectangle, clockwise from its top-left. */
        public Builder addRect(float x, float y, float width, float height) {
            return moveTo(x, y).lineTo(x + width, y).lineTo(x + width, y + height).lineTo(x, y + height).close();
        }

        /** A closed rounded rectangle, its radii fitted to the box as CSS does. */
        public Builder addRoundRect(float x, float y, float width, float height, CgCornerRadii radii) {
            CgCornerRadii r = radii.fitted(width, height);
            if (r.isNone()) return addRect(x, y, width, height);
            float x1 = x + width, y1 = y + height;
            moveTo(x + r.topLeftX(), y);
            lineTo(x1 - r.topRightX(), y);
            corner(x1 - r.topRightX(), y, x1, y + r.topRightY(), x1, y);
            lineTo(x1, y1 - r.bottomRightY());
            corner(x1, y1 - r.bottomRightY(), x1 - r.bottomRightX(), y1, x1, y1);
            lineTo(x + r.bottomLeftX(), y1);
            corner(x + r.bottomLeftX(), y1, x, y1 - r.bottomLeftY(), x, y1);
            lineTo(x, y + r.topLeftY());
            corner(x, y + r.topLeftY(), x + r.topLeftX(), y, x, y);
            return close();
        }

        /** A closed ellipse filling the box. */
        public Builder addOval(float x, float y, float width, float height) {
            float rx = width / 2f, ry = height / 2f;
            return addRoundRect(x, y, width, height, CgCornerRadii.elliptical(rx, ry));
        }

        /** A closed circle. */
        public Builder addCircle(float cx, float cy, float radius) {
            return addOval(cx - radius, cy - radius, radius * 2f, radius * 2f);
        }

        /** Every contour of {@code path}, appended. */
        public Builder add(CgVectorPath path) {
            path.forEach(new Visitor() {
                @Override
                public void moveTo(float x, float y) {
                    Builder.this.moveTo(x, y);
                }

                @Override
                public void lineTo(float x, float y) {
                    Builder.this.lineTo(x, y);
                }

                @Override
                public void quadTo(float cx, float cy, float x, float y) {
                    Builder.this.quadTo(cx, cy, x, y);
                }

                @Override
                public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                    Builder.this.cubicTo(c1x, c1y, c2x, c2y, x, y);
                }

                @Override
                public void close() {
                    Builder.this.close();
                }
            });
            return this;
        }

        public Builder fillRule(CgFillRule rule) {
            this.fillRule = Objects.requireNonNull(rule, "rule");
            return this;
        }

        public CgVectorPath build() {
            return new CgVectorPath(Arrays.copyOf(verbs, verbCount), Arrays.copyOf(points, pointCount), fillRule);
        }

        /** A quarter ellipse from the current point to ({@code x}, {@code y}) bulging toward ({@code kx}, {@code ky}). */
        private void corner(float x0, float y0, float x, float y, float kx, float ky) {
            if (x0 == x || y0 == y) {
                lineTo(x, y);
                return;
            }
            // A quarter of an ellipse as one cubic: control points 0.5523 of the way to the corner.
            float k = 0.5522848f;
            cubicTo(x0 + (kx - x0) * k, y0 + (ky - y0) * k, x + (kx - x) * k, y + (ky - y) * k, x, y);
        }

        private void ensureOpen() {
            if (!open) {
                if (verbCount == 0) throw new IllegalStateException("a contour starts with moveTo");
                moveTo(lastX, lastY);
            }
        }

        private void verb(Verb v) {
            if (verbCount == verbs.length) verbs = Arrays.copyOf(verbs, verbCount * 2);
            verbs[verbCount++] = (byte) v.ordinal();
        }

        private void point(float x, float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("points must be finite: " + x + ", " + y);
            if (pointCount + 2 > points.length) points = Arrays.copyOf(points, points.length * 2);
            points[pointCount++] = x;
            points[pointCount++] = y;
            lastX = x;
            lastY = y;
        }

        private static double angle(double ux, double uy, double vx, double vy) {
            double a = Math.atan2(uy, ux), b = Math.atan2(vy, vx), d = b - a;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            return d;
        }

        private static float ex(double cx, double rx, double ry, double cos, double sin, double ux, double uy) {
            return (float) (cx + cos * rx * ux - sin * ry * uy);
        }

        private static float ey(double cy, double rx, double ry, double cos, double sin, double ux, double uy) {
            return (float) (cy + sin * rx * ux + cos * ry * uy);
        }
    }
}
