package com.crystalgraphics.render.debug;

import java.util.Arrays;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTrace;

/**
 * A frame's world-space segments, or its triangles, as floats from the first one's start, which the draw stands at in
 * doubles. Triangles blend over one another and write no depth, so they draw as two meshes, the back faces before
 * the lines and the front faces after. Each set goes piece by piece far to near, the back faces by a piece's far
 * side and the front by its near: one shape inside another draws between its back and its front.
 */
public final class CgDebugBatch implements CgLineSink {

    private static final int ZONE_WRITE = CgTrace.name("debug.write");
    /** Points a record: two a segment, three a triangle. */
    final int corners;
    float[] points;
    int[] colours = new int[512];
    int count;
    double ox, oy, oz;
    /** Whether {@link #order} holds the draw order: triangles, sorted far to near. */
    boolean sorted;
    int[] order = new int[0];
    /** The records the mesh being written takes, in draw order. */
    int from, to;
    private int[] keys = new int[0], nearer = new int[0], orderScratch = new int[0];
    private final int[] counts = new int[256];
    /** Each triangle's piece ({@link CgLineSink#piece}), and each piece's middle and reach. */
    int[] pieceOf = new int[512];
    private double[] pieceX = new double[0], pieceY = new double[0], pieceZ = new double[0];
    private double[] pieceR = new double[0];
    private int[] pieceN = new int[0];
    private int pieces, piece = -1;
    private boolean pieceNext;
    /** The lines, or the back faces; and the front faces. */
    private CgMesh mesh, frontMesh;
    private CgMaterialInterface material;
    private int group;

    /** The colour the next records take, ARGB. */
    private int colour = 0xFFFFFFFF;

    /** @param corners 2 for segments, 3 for triangles */
    public CgDebugBatch(int corners) {
        if (corners != 2 && corners != 3) throw new IllegalArgumentException("corners " + corners + ": 2 or 3");
        this.corners = corners;
        points = new float[corners * 3 * 512];
    }

    @Override
    public void line(double ax, double ay, double az, double bx, double by, double bz) {
        int at = record(ax, ay, az);
        point(at, ax, ay, az);
        point(at + 3, bx, by, bz);
    }

    @Override
    public void piece() {
        pieceNext = true;
    }

    @Override
    public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz) {
        if (pieceNext || piece < 0) {
            piece = pieces++;
            pieceNext = false;
        }
        int at = record(ax, ay, az);
        pieceOf[count - 1] = piece;
        point(at, ax, ay, az);
        point(at + 3, bx, by, bz);
        point(at + 6, cx, cy, cz);
    }

    /** Books the next record in the colour being drawn, the first one's start the origin; where its points go. */
    private int record(double x, double y, double z) {
        if (count == 0) {
            ox = x;
            oy = y;
            oz = z;
        }
        if (count == colours.length) {
            points = Arrays.copyOf(points, points.length * 2);
            colours = Arrays.copyOf(colours, colours.length * 2);
            pieceOf = Arrays.copyOf(pieceOf, colours.length);
        }
        colours[count] = colour;
        return count++ * corners * 3;
    }

    private void point(int at, double x, double y, double z) {
        points[at] = (float) (x - ox);
        points[at + 1] = (float) (y - oy);
        points[at + 2] = (float) (z - oz);
    }

    /** The colour the records after this take, ARGB. */
    public CgDebugBatch colour(int argb) {
        this.colour = argb;
        return this;
    }

    /** Whether nothing is queued. */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Submits what the frame queued, seen from the eye, drawn with {@code material} over the world's transparent draws
     * at {@code group} of its sort, and empties it. Render thread.
     */
    public void draw(double ex, double ey, double ez, CgMaterialInterface material, int group) {
        if (count == 0) return;
        this.material = material;
        this.group = group;
        sorted = corners == 3;
        if (sorted) {
            int backs = sortFrom(ex, ey, ez);
            mesh = submit(mesh, 0, backs, 0, ex, ey, ez);
            frontMesh = submit(frontMesh, backs, count, 2, ex, ey, ez);
        } else {
            mesh = submit(mesh, 0, count, 1, ex, ey, ez);
        }
        count = pieces = 0;
        piece = -1;
    }

    /** Writes records {@code from} to {@code to} into {@code into} and draws it at {@code order} of the group. */
    private CgMesh submit(CgMesh into, int from, int to, int order, double ex, double ey, double ez) {
        if (from == to) return into;
        this.from = from;
        this.to = to;
        CgTrace.add(CgChannels.MISC, "debug.vertices", (to - from) * corners);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE_WRITE)) {
            if (into == null) {
                into = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> write(m, this));
            } else {
                into.edit(this, CgDebugBatch::write);
            }
        }
        // one group at the eye, so back faces, lines and front faces draw in that order whatever their distances
        CgScene.client().once().mesh(into, material).at(ox, oy, oz).layer(CgSortLayer.OVERLAY).sortAt(ex, ey, ez)
                .sortPriority(order).add();
        return into;
    }

    /**
     * {@link #order}: back faces then front faces, piece by piece far to near, a piece's back faces by its far side
     * and its front by its near, and within a piece by each triangle's middle; how many are back faces. Two
     * stable LSD radix sorts on float bits, the lesser key first.
     */
    int sortFrom(double ex, double ey, double ez) {
        if (order.length < count) {
            order = new int[colours.length];
            keys = new int[colours.length];
            nearer = new int[colours.length];
            orderScratch = new int[colours.length];
        }
        if (pieceN.length < pieces) {
            int size = Math.max(pieces, 2 * pieceN.length);
            pieceX = new double[size];
            pieceY = new double[size];
            pieceZ = new double[size];
            pieceR = new double[size];
            pieceN = new int[size];
        }
        Arrays.fill(pieceX, 0, pieces, 0);
        Arrays.fill(pieceY, 0, pieces, 0);
        Arrays.fill(pieceZ, 0, pieces, 0);
        Arrays.fill(pieceR, 0, pieces, 0);
        Arrays.fill(pieceN, 0, pieces, 0);
        double cx = ox - ex, cy = oy - ey, cz = oz - ez;
        // each piece's middle, from the eye: its corners' mean; then its reach, its farthest corner from that
        for (int i = 0, at = 0; i < count; i++, at += 9) {
            int g = pieceOf[i];
            pieceX[g] += points[at] + points[at + 3] + points[at + 6] + 3 * cx;
            pieceY[g] += points[at + 1] + points[at + 4] + points[at + 7] + 3 * cy;
            pieceZ[g] += points[at + 2] + points[at + 5] + points[at + 8] + 3 * cz;
            pieceN[g] += 3;
        }
        for (int g = 0; g < pieces; g++) {
            pieceX[g] /= pieceN[g];
            pieceY[g] /= pieceN[g];
            pieceZ[g] /= pieceN[g];
        }
        for (int i = 0, at = 0; i < count; i++, at += 9) {
            int g = pieceOf[i];
            for (int c = 0; c < 9; c += 3) {
                double dx = points[at + c] + cx - pieceX[g], dy = points[at + c + 1] + cy - pieceY[g];
                double dz = points[at + c + 2] + cz - pieceZ[g];
                pieceR[g] = Math.max(pieceR[g], dx * dx + dy * dy + dz * dz);
            }
        }
        int backs = 0;
        for (int i = 0, at = 0; i < count; i++, at += 9) {
            double dx = (points[at] + points[at + 3] + points[at + 6]) / 3 + cx;
            double dy = (points[at + 1] + points[at + 4] + points[at + 7]) / 3 + cy;
            double dz = (points[at + 2] + points[at + 5] + points[at + 8]) / 3 + cz;
            // faces wind outward (CgShapeLines), so a back face's normal points away from the eye
            double ux = points[at + 3] - points[at], uy = points[at + 4] - points[at + 1];
            double uz = points[at + 5] - points[at + 2], vx = points[at + 6] - points[at];
            double vy = points[at + 7] - points[at + 1], vz = points[at + 8] - points[at + 2];
            double facing = (uy * vz - uz * vy) * dx + (uz * vx - ux * vz) * dy + (ux * vy - uy * vx) * dz;
            boolean front = facing < 0;
            if (!front) backs++;
            int g = pieceOf[i];
            double middle = Math.sqrt(pieceX[g] * pieceX[g] + pieceY[g] * pieceY[g] + pieceZ[g] * pieceZ[g]);
            double reach = Math.sqrt(pieceR[g]), side = front ? Math.max(0, middle - reach) : middle + reach;
            // a non-negative float's bits order as it does; flipped, ascending is far to near
            keys[i] = (front ? Integer.MIN_VALUE : 0) | Integer.MAX_VALUE - Float.floatToRawIntBits((float) side);
            nearer[i] = Integer.MAX_VALUE - Float.floatToRawIntBits((float) (dx * dx + dy * dy + dz * dz));
            order[i] = i;
        }
        radix(nearer);
        radix(keys);
        return backs;
    }

    /** Orders {@link #order} by {@code key} of each triangle, keeping the order of equals. */
    private void radix(int[] key) {
        for (int shift = 0; shift < 32; shift += 8) {
            Arrays.fill(counts, 0);
            for (int i = 0; i < count; i++) counts[key[order[i]] >>> shift & 0xFF]++;
            for (int b = 0, sum = 0; b < 256; b++) {
                int n = counts[b];
                counts[b] = sum;
                sum += n;
            }
            for (int i = 0; i < count; i++) orderScratch[counts[key[order[i]] >>> shift & 0xFF]++] = order[i];
            int[] o = order;
            order = orderScratch;
            orderScratch = o;
        }
    }

    /** Frees its meshes: at the context's teardown. */
    public void release() {
        if (mesh != null) mesh.release();
        if (frontMesh != null) frontMesh.release();
        mesh = frontMesh = null;
        count = 0;
    }

    private static void write(CgMeshWriter m, CgDebugBatch batch) {
        m.topology(batch.corners == 2 ? CgMeshTopology.LINES : CgMeshTopology.TRIANGLES);
        float[] p = batch.points;
        for (int k = batch.from; k < batch.to; k++) {
            int i = batch.sorted ? batch.order[k] : k, at = i * batch.corners * 3;
            int argb = batch.colours[i];
            float r = (argb >>> 16 & 0xFF) / 255f, g = (argb >>> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = (argb >>> 24) / 255f;
            // the colour rides in the normal, its alpha in u: debug/lines.shader reads them back
            for (int c = 0; c < batch.corners; c++, at += 3) {
                m.vertex().position(p[at], p[at + 1], p[at + 2]).uv(a, 0f).normal(r, g, b).tangent(1f, 0f, 0f, 1f).end();
            }
        }
    }
}
