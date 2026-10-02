package com.crystalgraphics.vfx.path;

/**
 * A smooth centreline through control points, as rings evenly spaced by arc length, each with a frame that does not
 * twist: what a {@link CgVfxTube} is drawn along.
 *
 * <pre>{@code
 * CgVfxPath path = new CgVfxPath();
 * path.build(points, pointCount, 0.25f);         // xyz triples relative to the effect's origin, first to last
 * for (int i = 0; i < path.count(); i++) {
 *     path.radius(i, 0.6f);                      // 1 unless set
 * }
 * int row = frame.path(path);                    // into this frame's path texture
 * }</pre>
 *
 * <ul>
 *   <li>The curve is a centripetal Catmull-Rom spline (no cusps or overshoot on uneven spacing), resampled at the
 *       spacing given, capped at {@link #MAX_RINGS}.</li>
 *   <li>Frames are rotation-minimising (Wang, Jüttler, Zheng and Liu 2008, double reflection), started from world up,
 *       so a spiral around the tube does not spin where the path bends.</li>
 *   <li>Points closer together than a thousandth of a block are merged; fewer than two left gives no rings.</li>
 * </ul>
 */
public final class CgVfxPath {

    public static final int MAX_RINGS = 256;
    /** Floats per ring: position xyz and radius, tangent xyz and arc length, normal xyz and intensity. */
    public static final int RING_FLOATS = 12;
    private static final int SUBDIVISIONS = 6;
    private static final float MERGE = 1.0e-3f;

    private final float[] rings = new float[MAX_RINGS * RING_FLOATS];
    private int count;
    private float length;

    private float[] points = new float[64 * 3];
    private float[] dense = new float[64 * 3 * SUBDIVISIONS];
    private float[] cumulative = new float[64 * SUBDIVISIONS];

    /** Builds the rings through {@code pointCount} points ({@code xyz} triples), one ring per {@code spacing} blocks. */
    public void build(float[] xyz, int pointCount, float spacing) {
        int n = merge(xyz, pointCount);
        count = 0;
        length = 0f;
        if (n < 2) return;
        int denseCount = densify(n);
        length = cumulative[denseCount - 1];
        if (length <= MERGE) return;
        count = Math.min(MAX_RINGS, Math.max(2, (int) Math.ceil(length / spacing) + 1));
        resample(denseCount);
        tangents();
        frames();
    }

    public int count() { return count; }

    public float length() { return length; }

    public float x(int ring) { return rings[ring * RING_FLOATS]; }

    public float y(int ring) { return rings[ring * RING_FLOATS + 1]; }

    public float z(int ring) { return rings[ring * RING_FLOATS + 2]; }

    public float radius(int ring) { return rings[ring * RING_FLOATS + 3]; }

    /** Arc length from the first point to this ring, in blocks. */
    public float arc(int ring) { return rings[ring * RING_FLOATS + 7]; }

    public float tangentX(int ring) { return rings[ring * RING_FLOATS + 4]; }

    public float tangentY(int ring) { return rings[ring * RING_FLOATS + 5]; }

    public float tangentZ(int ring) { return rings[ring * RING_FLOATS + 6]; }

    public void radius(int ring, float radius) { rings[ring * RING_FLOATS + 3] = radius; }

    /** A 0..1 brightness a layer may read, 1 unless set. */
    public void intensity(int ring, float intensity) { rings[ring * RING_FLOATS + 11] = intensity; }

    /** The packed rings, {@link #RING_FLOATS} each, {@link #count} of them. */
    float[] data() { return rings; }

    private int merge(float[] xyz, int pointCount) {
        if (points.length < pointCount * 3) points = new float[pointCount * 3 * 2];
        int n = 0;
        for (int i = 0; i < pointCount; i++) {
            float x = xyz[i * 3], y = xyz[i * 3 + 1], z = xyz[i * 3 + 2];
            if (n > 0) {
                float dx = x - points[n * 3 - 3], dy = y - points[n * 3 - 2], dz = z - points[n * 3 - 1];
                if (dx * dx + dy * dy + dz * dz < MERGE * MERGE) continue;
            }
            points[n * 3] = x;
            points[n * 3 + 1] = y;
            points[n * 3 + 2] = z;
            n++;
        }
        return n;
    }

    /** The spline as a dense polyline with cumulative length; answers its point count. */
    private int densify(int n) {
        int denseCount = (n - 1) * SUBDIVISIONS + 1;
        if (dense.length < denseCount * 3) {
            dense = new float[denseCount * 3 * 2];
            cumulative = new float[denseCount * 2];
        }
        int d = 0;
        for (int i = 0; i < n - 1; i++) {
            for (int k = 0; k < SUBDIVISIONS; k++) {
                catmullRom(n, i, (float) k / SUBDIVISIONS, d++);
            }
        }
        System.arraycopy(points, (n - 1) * 3, dense, d * 3, 3);
        d++;
        cumulative[0] = 0f;
        for (int i = 1; i < d; i++) {
            float dx = dense[i * 3] - dense[i * 3 - 3];
            float dy = dense[i * 3 + 1] - dense[i * 3 - 2];
            float dz = dense[i * 3 + 2] - dense[i * 3 - 1];
            cumulative[i] = cumulative[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return d;
    }

    /** Barry and Goldman's pyramid for the centripetal spline on segment {@code i}, at {@code u} of it. */
    private void catmullRom(int n, int i, float u, int out) {
        for (int c = 0; c < 3; c++) {
            float p1 = points[i * 3 + c], p2 = points[(i + 1) * 3 + c];
            float p0 = i > 0 ? points[(i - 1) * 3 + c] : 2f * p1 - p2;
            float p3 = i + 2 < n ? points[(i + 2) * 3 + c] : 2f * p2 - p1;
            scratch[c * 4] = p0;
            scratch[c * 4 + 1] = p1;
            scratch[c * 4 + 2] = p2;
            scratch[c * 4 + 3] = p3;
        }
        float t0 = 0f;
        float t1 = t0 + knot(0, 1);
        float t2 = t1 + knot(1, 2);
        float t3 = t2 + knot(2, 3);
        float t = t1 + (t2 - t1) * u;
        for (int c = 0; c < 3; c++) {
            float p0 = scratch[c * 4], p1 = scratch[c * 4 + 1], p2 = scratch[c * 4 + 2], p3 = scratch[c * 4 + 3];
            float a1 = lerp(p0, p1, t0, t1, t), a2 = lerp(p1, p2, t1, t2, t), a3 = lerp(p2, p3, t2, t3, t);
            float b1 = lerp(a1, a2, t0, t2, t), b2 = lerp(a2, a3, t1, t3, t);
            dense[out * 3 + c] = lerp(b1, b2, t1, t2, t);
        }
    }

    private final float[] scratch = new float[12];

    /** The centripetal knot step between two of the four points: the square root of their distance. */
    private float knot(int a, int b) {
        float dx = scratch[b] - scratch[a];
        float dy = scratch[4 + b] - scratch[4 + a];
        float dz = scratch[8 + b] - scratch[8 + a];
        return Math.max((float) Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)), 1.0e-4f);
    }

    private static float lerp(float a, float b, float ta, float tb, float t) {
        return a + (b - a) * (t - ta) / (tb - ta);
    }

    private void resample(int denseCount) {
        float step = length / (count - 1);
        int j = 0;
        for (int i = 0; i < count; i++) {
            float s = Math.min(i * step, length);
            while (j < denseCount - 2 && cumulative[j + 1] < s) j++;
            float span = cumulative[j + 1] - cumulative[j];
            float f = span > 0f ? (s - cumulative[j]) / span : 0f;
            int o = i * RING_FLOATS;
            for (int c = 0; c < 3; c++) {
                rings[o + c] = dense[j * 3 + c] + (dense[(j + 1) * 3 + c] - dense[j * 3 + c]) * f;
            }
            rings[o + 3] = 1f;
            rings[o + 7] = s;
            rings[o + 11] = 1f;
        }
    }

    private void tangents() {
        for (int i = 0; i < count; i++) {
            int a = Math.max(i - 1, 0), b = Math.min(i + 1, count - 1);
            float tx = rings[b * RING_FLOATS] - rings[a * RING_FLOATS];
            float ty = rings[b * RING_FLOATS + 1] - rings[a * RING_FLOATS + 1];
            float tz = rings[b * RING_FLOATS + 2] - rings[a * RING_FLOATS + 2];
            float inv = 1f / Math.max((float) Math.sqrt(tx * tx + ty * ty + tz * tz), 1.0e-6f);
            int o = i * RING_FLOATS;
            rings[o + 4] = tx * inv;
            rings[o + 5] = ty * inv;
            rings[o + 6] = tz * inv;
        }
    }

    /** Double reflection: each normal is the last one reflected across the chord, then across the tangents' bisector. */
    private void frames() {
        float tx = rings[4], ty = rings[5], tz = rings[6];
        // World up, made perpendicular to the first tangent; x when the path starts straight up or down.
        float rx = -ty * tx, ry = 1f - ty * ty, rz = -ty * tz;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1.0e-3f) {
            rx = 1f - tx * tx;
            ry = -tx * ty;
            rz = -tx * tz;
            len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        }
        rings[8] = rx / len;
        rings[9] = ry / len;
        rings[10] = rz / len;
        for (int i = 0; i < count - 1; i++) {
            int o = i * RING_FLOATS, p = o + RING_FLOATS;
            float v1x = rings[p] - rings[o], v1y = rings[p + 1] - rings[o + 1], v1z = rings[p + 2] - rings[o + 2];
            float c1 = v1x * v1x + v1y * v1y + v1z * v1z;
            float nx = rings[o + 8], ny = rings[o + 9], nz = rings[o + 10];
            float ax = rings[o + 4], ay = rings[o + 5], az = rings[o + 6];
            if (c1 > 1.0e-12f) {
                float k = 2f / c1 * (v1x * nx + v1y * ny + v1z * nz);
                nx -= k * v1x;
                ny -= k * v1y;
                nz -= k * v1z;
                k = 2f / c1 * (v1x * ax + v1y * ay + v1z * az);
                ax -= k * v1x;
                ay -= k * v1y;
                az -= k * v1z;
            }
            float v2x = rings[p + 4] - ax, v2y = rings[p + 5] - ay, v2z = rings[p + 6] - az;
            float c2 = v2x * v2x + v2y * v2y + v2z * v2z;
            if (c2 > 1.0e-12f) {
                float k = 2f / c2 * (v2x * nx + v2y * ny + v2z * nz);
                nx -= k * v2x;
                ny -= k * v2y;
                nz -= k * v2z;
            }
            float inv = 1f / Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0e-6f);
            rings[p + 8] = nx * inv;
            rings[p + 9] = ny * inv;
            rings[p + 10] = nz * inv;
        }
    }
}
