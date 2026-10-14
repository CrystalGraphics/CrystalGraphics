package com.crystalgraphics.collision.debug;

import com.crystalgraphics.shape.primitive.CgConvex;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link CgConvex#hull}'s own faces and edges, in its space, for drawing: every plane through three of its points
 * that all the rest lie behind is a face, its points' outline fanned into triangles. Built once a shape, so only for
 * the few dozen points a hull is meant to have.
 */
final class HullMesh {

    /** Past this many points a hull is drawn by its support instead: building is cubic in them. */
    static final int MAX_POINTS = 64;

    /** Nine a triangle: its three corners. */
    final double[] triangles;
    /** Six an edge, each once. */
    final double[] edges;

    private HullMesh(double[] triangles, double[] edges) {
        this.triangles = triangles;
        this.edges = edges;
    }

    /** The hull's mesh; null when it has no points, too many, or no face (a point or a segment). */
    static HullMesh of(CgConvex hull) {
        int count = hull.pointCount();
        if (count < 3 || count > MAX_POINTS) return null;
        double[] p = new double[count * 3];
        Vector3d at = new Vector3d();
        double scale = 0;
        for (int i = 0; i < count; i++) {
            hull.point(i, at);
            p[i * 3] = at.x;
            p[i * 3 + 1] = at.y;
            p[i * 3 + 2] = at.z;
            scale = Math.max(scale, Math.max(Math.abs(at.x), Math.max(Math.abs(at.y), Math.abs(at.z))));
        }
        double eps = 1e-9 * Math.max(1, scale);
        List<double[]> planes = new ArrayList<>();
        List<Double> triangles = new ArrayList<>();
        Set<Long> edges = new HashSet<>();
        List<Double> edgeList = new ArrayList<>();
        int[] on = new int[count];
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                for (int k = j + 1; k < count; k++) {
                    double ux = p[j * 3] - p[i * 3], uy = p[j * 3 + 1] - p[i * 3 + 1], uz = p[j * 3 + 2] - p[i * 3 + 2];
                    double vx = p[k * 3] - p[i * 3], vy = p[k * 3 + 1] - p[i * 3 + 1], vz = p[k * 3 + 2] - p[i * 3 + 2];
                    double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                    double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len <= eps * Math.max(1, scale)) continue;
                    nx /= len;
                    ny /= len;
                    nz /= len;
                    double d = nx * p[i * 3] + ny * p[i * 3 + 1] + nz * p[i * 3 + 2];
                    int front = 0, behind = 0, onCount = 0;
                    for (int m = 0; m < count; m++) {
                        double s = nx * p[m * 3] + ny * p[m * 3 + 1] + nz * p[m * 3 + 2] - d;
                        if (s > eps) front++;
                        else if (s < -eps) behind++;
                        else on[onCount++] = m;
                    }
                    if (front > 0 && behind > 0) continue;
                    if (front > 0) {
                        nx = -nx;
                        ny = -ny;
                        nz = -nz;
                        d = -d;
                    }
                    if (known(planes, nx, ny, nz, d, eps)) continue;
                    planes.add(new double[]{nx, ny, nz, d});
                    face(p, on, onCount, nx, ny, nz, eps, triangles, edges, edgeList);
                }
            }
        }
        if (triangles.isEmpty()) return null;
        return new HullMesh(unbox(triangles), unbox(edgeList));
    }

    /** Whether a face lies in this plane already, either way round: a flat hull's two sides are one face. */
    private static boolean known(List<double[]> planes, double nx, double ny, double nz, double d, double eps) {
        for (double[] q : planes) {
            double dot = q[0] * nx + q[1] * ny + q[2] * nz;
            if (dot > 1 - 1e-9 && Math.abs(q[3] - d) <= eps) return true;
            if (dot < -1 + 1e-9 && Math.abs(q[3] + d) <= eps) return true;
        }
        return false;
    }

    /** The outline of the points on a face, by Andrew's monotone chain in the face's plane, fanned from its first. */
    private static void face(double[] p, int[] on, int onCount, double nx, double ny, double nz, double eps,
                             List<Double> triangles, Set<Long> edges, List<Double> edgeList) {
        // two axes in the plane
        double ux, uy, uz;
        if (Math.abs(nx) < 0.9) {
            ux = 0;
            uy = -nz;
            uz = ny;
        } else {
            ux = nz;
            uy = 0;
            uz = -nx;
        }
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        Integer[] order = new Integer[onCount];
        double[] s = new double[p.length / 3], t = new double[p.length / 3];
        for (int n = 0; n < onCount; n++) {
            int m = on[n];
            order[n] = m;
            s[m] = p[m * 3] * ux + p[m * 3 + 1] * uy + p[m * 3 + 2] * uz;
            t[m] = p[m * 3] * vx + p[m * 3 + 1] * vy + p[m * 3 + 2] * vz;
        }
        Arrays.sort(order, (a, b) -> s[a] != s[b] ? Double.compare(s[a], s[b]) : Double.compare(t[a], t[b]));
        int[] chain = new int[2 * onCount];
        int size = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = size;
            for (int n = 0; n < onCount; n++) {
                int m = order[pass == 0 ? n : onCount - 1 - n];
                while (size - start >= 2 && turn(s, t, chain[size - 2], chain[size - 1], m) <= eps) size--;
                chain[size++] = m;
            }
            size--;
        }
        for (int n = 1; n + 1 < size; n++) {
            add(triangles, p, chain[0]);
            add(triangles, p, chain[n]);
            add(triangles, p, chain[n + 1]);
        }
        for (int n = 0; n < size; n++) {
            int a = chain[n], b = chain[(n + 1) % size];
            if (edges.add(Math.min(a, b) * 65536L + Math.max(a, b))) {
                add(edgeList, p, a);
                add(edgeList, p, b);
            }
        }
    }

    private static double turn(double[] s, double[] t, int a, int b, int c) {
        return (s[b] - s[a]) * (t[c] - t[a]) - (t[b] - t[a]) * (s[c] - s[a]);
    }

    private static void add(List<Double> out, double[] p, int m) {
        out.add(p[m * 3]);
        out.add(p[m * 3 + 1]);
        out.add(p[m * 3 + 2]);
    }

    private static double[] unbox(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
