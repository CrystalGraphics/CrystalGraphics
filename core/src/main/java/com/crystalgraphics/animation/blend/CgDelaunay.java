package com.crystalgraphics.animation.blend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Delaunay triangulation of 2D points by Bowyer-Watson: each point added inside a bounding triangle, the triangles
 * whose circumcircles hold it replaced by a fan to it. Ported from Godot's {@code Delaunay2D}
 * ({@code core/math/delaunay_2d.h}, MIT, Copyright (c) 2014-present Godot Engine contributors); a blend space bakes its
 * lookup from it at load.
 *
 * <pre>{@code
 * int[] triangles = CgDelaunay.triangulate(new float[] {0, 0, 1, 0, 0, 1, 1, 1});   // 3 point indices each
 * }</pre>
 *
 * <p>Two points or fewer give no triangles. Where four points share a circle (a square) the diagonal is whichever the
 * insertion order leaves.</p>
 */
public final class CgDelaunay {

    private CgDelaunay() {
    }

    private record Triangle(int a, int b, int c, float cx, float cy, float r2) {
    }

    /** Triangles of {@code xy} (x, y per point), as point indices, three each. */
    public static int[] triangulate(float[] xy) {
        int count = xy.length / 2;
        if (count <= 2) return new int[0];
        float[] p = new float[(count + 3) * 2];
        System.arraycopy(xy, 0, p, 0, count * 2);
        float minX = xy[0], maxX = xy[0], minY = xy[1], maxY = xy[1];
        for (int i = 1; i < count; i++) {
            minX = Math.min(minX, xy[i * 2]);
            maxX = Math.max(maxX, xy[i * 2]);
            minY = Math.min(minY, xy[i * 2 + 1]);
            maxY = Math.max(maxY, xy[i * 2 + 1]);
        }
        float delta = Math.max(maxX - minX, maxY - minY), cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
        // A bounding triangle around them all
        p[count * 2] = cx - delta * 16;
        p[count * 2 + 1] = cy - delta;
        p[count * 2 + 2] = cx;
        p[count * 2 + 3] = cy + delta * 16;
        p[count * 2 + 4] = cx + delta * 16;
        p[count * 2 + 5] = cy - delta;
        List<Triangle> triangles = new ArrayList<>();
        triangles.add(triangle(p, count, count + 1, count + 2));
        List<int[]> polygon = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            polygon.clear();
            float x = p[i * 2], y = p[i * 2 + 1];
            for (int j = triangles.size() - 1; j >= 0; j--) {
                Triangle t = triangles.get(j);
                float dx = x - t.cx, dy = y - t.cy;
                if (dx * dx + dy * dy < t.r2) {
                    polygon.add(edge(t.a, t.b));
                    polygon.add(edge(t.b, t.c));
                    polygon.add(edge(t.c, t.a));
                    triangles.remove(j);
                }
            }
            // Edges shared by two removed triangles are inside the hole; the rest bound it
            boolean[] bad = new boolean[polygon.size()];
            for (int j = 0; j < polygon.size(); j++) {
                if (bad[j]) continue;
                int[] e = polygon.get(j);
                for (int k = j + 1; k < polygon.size(); k++) {
                    int[] f = polygon.get(k);
                    if (f[0] == e[0] && f[1] == e[1]) {
                        bad[j] = bad[k] = true;
                        break;
                    }
                }
                if (!bad[j]) triangles.add(triangle(p, e[0], e[1], i));
            }
        }
        int kept = 0;
        int[] out = new int[triangles.size() * 3];
        for (Triangle t : triangles) {
            if (t.a >= count || t.b >= count || t.c >= count) continue;
            out[kept++] = t.a;
            out[kept++] = t.b;
            out[kept++] = t.c;
        }
        return Arrays.copyOf(out, kept);
    }

    private static int[] edge(int a, int b) {
        return a > b ? new int[] {b, a} : new int[] {a, b};
    }

    private static Triangle triangle(float[] p, int a, int b, int c) {
        float ax = p[b * 2] - p[a * 2], ay = p[b * 2 + 1] - p[a * 2 + 1];
        float bx = p[c * 2] - p[a * 2], by = p[c * 2 + 1] - p[a * 2 + 1];
        float al = ax * ax + ay * ay, bl = bx * bx + by * by, cross = ax * by - ay * bx;
        // (b·|a|² − a·|b|²) turned to (y, −x), over twice the cross product: the circumcentre from a
        float vx = bx * al - ax * bl, vy = by * al - ay * bl;
        float ox = vy / (cross * 2f), oy = -vx / (cross * 2f);
        return new Triangle(a, b, c, ox + p[a * 2], oy + p[a * 2 + 1], ox * ox + oy * oy);
    }
}
