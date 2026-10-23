package com.crystalgraphics.render.world;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.mesh.CgOffsetAllocator;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our meshes' signed distance fields, Unreal's mesh distance fields (MeshDistanceFieldUtilities.cpp): what the world
 * lights' shadow rays trace for our objects, on screen or off. A field covers a mesh's bounds grown by a voxel, at
 * {@link #DENSITY} voxels a block up to {@link #MOST} an axis; each voxel holds the distance to the nearest triangle in
 * the mesh's own units, negative inside, clamped to a band of {@link #BAND} voxels.
 *
 * <p>Inside is Unreal's vote: more than a quarter of the rays from the voxel hit a back face first, so a mesh with holes
 * still has an inside. A field is made once a mesh (and index range) on a worker from its CPU copy and kept for the
 * session in one R32F atlas {@link #ROW} floats wide, whole rows a field, float i of a field at (i % ROW, i / ROW) from
 * its first; none for a mesh with no CPU copy, no triangles, or rewritten every frame. A texture rather than a buffer:
 * a material reads at most four buffers, and the lights' passes use them.</p>
 *
 * <pre>{@code
 * CgMeshDistanceFields.Entry field = fields.field(mesh, submesh, first, count);   // render thread; null until ready
 * fields.upload(recording);                                                        // each firing, before a pass reads it
 * material.applyProperties(b -> b.sampler("_MeshFields", unit, fields.atlas()));
 * }</pre>
 */
final class CgMeshDistanceFields {

    /** Voxels a block: Unreal's r.DistanceFields.DefaultVoxelDensity, 0.2 a centimetre. */
    static final int DENSITY = 16;
    /** The most voxels an axis: a large mesh is coarser rather than larger. */
    static final int MOST = 48;
    /** Voxels the stored distance reaches before it is clamped: Unreal's BandSizeInVoxels. */
    static final int BAND = 4;
    /** Floats the fields share: 8M, 32 MB. */
    private static final CgVar.Int CAPACITY = CgVar.integer("crystalgraphics.world.meshFieldFloats", 8 << 20, 1 << 16,
            1 << 26, CgVar.DEV | CgVar.RESTART, "Floats our meshes' distance fields share on the GPU; the least recently"
                    + " used are dropped past it.");
    /** Floats a row of the atlas: a shader reads float i of a field at (i % ROW, i / ROW) from the field's first. */
    static final int ROW = 1024;
    private static final int ROWS = Math.max(64, CAPACITY.get() / ROW);
    /** Unreal's vote: directions round the sphere, the 26 of a cube's neighbours. */
    private static final float[] DIRECTIONS = directions();
    private static final int CELL = 4;

    /** A field made: {@code n} voxels each way from {@code origin}, {@code voxel} apart, x fastest. */
    record Field(int nx, int ny, int nz, float ox, float oy, float oz, float voxel, float[] distances) {
    }

    /** A mesh's field as the cache keeps it. */
    static final class Entry {
        final CgMesh mesh;
        final int submesh, first, count, revision;
        volatile Field made;
        volatile boolean failed;
        int node = CgOffsetAllocator.NO_SPACE;
        long used;

        Entry(CgMesh mesh, int submesh, int first, int count, int revision) {
            this.mesh = mesh;
            this.submesh = submesh;
            this.first = first;
            this.count = count;
            this.revision = revision;
        }

        /** Its first float in the atlas, a row's start; valid once {@link CgMeshDistanceFields#field} returned it. */
        int offset(CgMeshDistanceFields owner) {
            return owner.alloc.offset(node) * ROW;
        }
    }

    private final Map<CgMesh, List<Entry>> entries = new IdentityHashMap<>();
    private final List<Entry> pending = new ArrayList<>();
    /** Rows of the atlas. */
    private CgOffsetAllocator alloc;
    private CgGraphTexture atlas;

    /** The atlas every field lives in; null until the first field is uploaded. */
    CgGraphTexture atlas() {
        return atlas;
    }

    /**
     * The field of what a draw of {@code mesh} draws: {@code submesh}'s indices from {@code first}, {@code count} of them
     * (-1 to its end), or the whole mesh for submesh -1; uploaded, else null while it is made or where it cannot be.
     * Render thread.
     */
    Entry field(CgMesh mesh, int submesh, int first, int count) {
        List<Entry> list = entries.get(mesh);
        int revision = mesh.revision();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (e.submesh != submesh || e.first != first || e.count != count) continue;
                if (e.revision != revision) {
                    drop(e);
                    list.remove(i);
                    break;
                }
                e.used = CgFrameRing.frame();
                return e.node != CgOffsetAllocator.NO_SPACE ? e : null;
            }
        }
        if (mesh.usage() == CgMesh.Usage.FRAME || mesh.format() == CgVertexFormat.NONE
                || mesh.topology() != CgMeshTopology.TRIANGLES) {
            return null;
        }
        Entry e = new Entry(mesh, submesh, first, count, revision);
        e.used = CgFrameRing.frame();
        entries.computeIfAbsent(mesh, m -> new ArrayList<>()).add(e);
        float[] positions;
        int[] triangles;
        try {
            positions = positions(mesh);
            triangles = triangles(mesh, submesh, first, count);
        } catch (RuntimeException noCopy) {
            e.failed = true;
            return null;
        }
        pending.add(e);
        CgJob.run(() -> {
            try {
                e.made = generate(positions, triangles);
            } catch (RuntimeException ex) {
                e.failed = true;
            }
        });
        return null;
    }

    /** Uploads the fields made since the last firing; drops the least recently used where they do not fit. */
    void upload(CgRecording recording) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Entry e = pending.get(i);
            if (e.failed) {
                pending.remove(i);
                continue;
            }
            Field f = e.made;
            if (f == null) continue;
            pending.remove(i);
            if (f.distances.length == 0) {
                e.failed = true;
                continue;
            }
            if (atlas == null) {
                alloc = new CgOffsetAllocator(ROWS);
                atlas = CgGraphTexture.requested("cg_world_lights.mesh-fields", new CgTextureDesc(ROW, ROWS,
                        CgFrameBufferFormat.builder("cg_world_lights.mesh-fields").color(0, CgTextureType.R32F).build()));
            }
            int rows = (f.distances.length + ROW - 1) / ROW;
            int node = alloc.allocate(rows);
            while (node == CgOffsetAllocator.NO_SPACE && evictOldest()) node = alloc.allocate(rows);
            if (node == CgOffsetAllocator.NO_SPACE) {
                e.failed = true;
                continue;
            }
            e.node = node;
            ByteBuffer bytes = ByteBuffer.allocateDirect(rows * ROW * 4).order(ByteOrder.nativeOrder());
            bytes.asFloatBuffer().put(f.distances);
            recording.update(atlas, 0, 0, alloc.offset(node), ROW, rows, bytes);
            CgTrace.add(CgChannels.WORLD, "world.mesh-fields-made", 1);
        }
    }

    private boolean evictOldest() {
        Entry oldest = null;
        for (List<Entry> list : entries.values()) {
            for (Entry e : list) {
                if (e.node != CgOffsetAllocator.NO_SPACE && e.used < CgFrameRing.frame() - 1
                        && (oldest == null || e.used < oldest.used)) {
                    oldest = e;
                }
            }
        }
        if (oldest == null) return false;
        List<Entry> list = entries.get(oldest.mesh);
        list.remove(oldest);
        if (list.isEmpty()) entries.remove(oldest.mesh);
        drop(oldest);
        return true;
    }

    private void drop(Entry e) {
        if (e.node != CgOffsetAllocator.NO_SPACE) alloc.free(e.node);
        e.node = CgOffsetAllocator.NO_SPACE;
        pending.remove(e);
    }

    /** The mesh's positions, three floats a vertex. */
    private static float[] positions(CgMesh mesh) {
        CgVertexFormat format = mesh.format();
        int at = format.indexOf("cg_Position");
        if (at < 0) throw new IllegalStateException("no position");
        CgVertexAttribute position = format.getAttribute(at);
        int n = mesh.vertexCount(), stride = format.getStride();
        ByteBuffer bytes = ByteBuffer.allocate(n * stride).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, n, bytes);
        float[] out = new float[n * 3];
        int components = Math.min(3, position.getComponents());
        for (int v = 0; v < n; v++) {
            for (int c = 0; c < components; c++) out[v * 3 + c] = position.read(bytes, v * stride, c);
        }
        return out;
    }

    /** The vertex indices a draw's range covers, three a triangle, counted from the mesh's first vertex. */
    private static int[] triangles(CgMesh mesh, int submesh, int first, int count) {
        int[] s = submesh < 0 ? new int[]{0, mesh.indexCount(), 0, mesh.vertexCount()} : mesh.submesh(submesh, new int[4]);
        if (submesh < 0) first = 0;
        if (!mesh.isIndexed()) {
            int n = (count < 0 ? s[3] - first : Math.min(count, s[3] - first)) / 3 * 3;
            int[] out = new int[Math.max(0, n)];
            for (int i = 0; i < out.length; i++) out[i] = s[2] + first + i;
            return out;
        }
        int n = (count < 0 || submesh < 0 ? s[1] - first : Math.min(count, s[1] - first)) / 3 * 3;
        int[] out = new int[Math.max(0, n)];
        mesh.readIndices(s[0] + first, out.length, out, 0);
        for (int i = 0; i < out.length; i++) out[i] += s[2];
        return out;
    }

    /** A field for all of {@code mesh}'s triangles, made here: what a test checks. */
    static Field generate(CgMesh mesh) {
        return generate(positions(mesh), triangles(mesh, -1, 0, -1));
    }

    /**
     * A field for triangles {@code triangles} (three vertex indices each) over {@code positions} (three floats each).
     * Any thread; empty for no triangles.
     */
    static Field generate(float[] positions, int[] triangles) {
        triangles = withArea(positions, triangles);
        int tris = triangles.length / 3;
        if (tris == 0) return new Field(0, 0, 0, 0f, 0f, 0f, 0f, new float[0]);
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int index : triangles) {
            for (int c = 0; c < 3; c++) {
                lo[c] = Math.min(lo[c], positions[index * 3 + c]);
                hi[c] = Math.max(hi[c], positions[index * 3 + c]);
            }
        }
        float most = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        float voxel = Math.max(most / (MOST - 3), 1f / DENSITY);
        int[] n = new int[3];
        float[] origin = new float[3];
        for (int c = 0; c < 3; c++) {
            n[c] = Math.min(MOST, (int) Math.ceil((hi[c] - lo[c]) / voxel) + 3);
            origin[c] = lo[c] - voxel;
        }
        Grid grid = new Grid(positions, triangles, origin, voxel * CELL, n);
        float band = BAND * voxel;
        float[] out = new float[n[0] * n[1] * n[2]];
        // A slice a task, across the workers: the grid is read only, each slice's scratch its own.
        CgJob.forEach(n[2], z -> {
            float[] q = new float[3], scratch = new float[3];
            for (int y = 0; y < n[1]; y++) {
                for (int x = 0; x < n[0]; x++) {
                    q[0] = origin[0] + x * voxel;
                    q[1] = origin[1] + y * voxel;
                    q[2] = origin[2] + z * voxel;
                    float d = Math.min(grid.closest(q, band, scratch), band);
                    out[(z * n[1] + y) * n[0] + x] = grid.inside(q) ? -d : d;
                }
            }
        });
        return new Field(n[0], n[1], n[2], origin[0], origin[1], origin[2], voxel, out);
    }

    /** Only the triangles with an area: a sphere's poles have none, and the nearest point on one divides by zero. */
    private static int[] withArea(float[] p, int[] tri) {
        int[] out = new int[tri.length];
        int n = 0;
        for (int t = 0; t + 2 < tri.length; t += 3) {
            int a = tri[t] * 3, b = tri[t + 1] * 3, c = tri[t + 2] * 3;
            float e1x = p[b] - p[a], e1y = p[b + 1] - p[a + 1], e1z = p[b + 2] - p[a + 2];
            float e2x = p[c] - p[a], e2y = p[c + 1] - p[a + 1], e2z = p[c + 2] - p[a + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            if (nx * nx + ny * ny + nz * nz < 1e-14f) continue;
            out[n++] = tri[t];
            out[n++] = tri[t + 1];
            out[n++] = tri[t + 2];
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /** The triangles binned into cells of a few voxels, for the nearest triangle and the first a ray hits. */
    private static final class Grid {
        final float[] p;
        final int[] tri;
        final float[] origin;
        final float cell;
        final int gx, gy, gz;
        final int[] start, items;
        final float reach;

        Grid(float[] p, int[] tri, float[] origin, float cell, int[] voxels) {
            this.p = p;
            this.tri = tri;
            this.origin = origin;
            this.cell = cell;
            gx = Math.max(1, (voxels[0] + CELL - 1) / CELL);
            gy = Math.max(1, (voxels[1] + CELL - 1) / CELL);
            gz = Math.max(1, (voxels[2] + CELL - 1) / CELL);
            reach = (float) Math.sqrt((double) gx * gx + gy * gy + gz * gz) * cell;
            int cells = gx * gy * gz, tris = tri.length / 3;
            int[] counts = new int[cells + 1];
            int[] box = new int[6];
            for (int t = 0; t < tris; t++) {
                cellsOf(t, box);
                for (int z = box[2]; z <= box[5]; z++) for (int y = box[1]; y <= box[4]; y++) for (int x = box[0]; x <= box[3]; x++) counts[(z * gy + y) * gx + x + 1]++;
            }
            for (int i = 0; i < cells; i++) counts[i + 1] += counts[i];
            start = counts.clone();
            items = new int[counts[cells]];
            int[] fill = counts.clone();
            for (int t = 0; t < tris; t++) {
                cellsOf(t, box);
                for (int z = box[2]; z <= box[5]; z++) for (int y = box[1]; y <= box[4]; y++) for (int x = box[0]; x <= box[3]; x++) items[fill[(z * gy + y) * gx + x]++] = t;
            }
        }

        private void cellsOf(int t, int[] box) {
            for (int c = 0; c < 3; c++) {
                float a = p[tri[t * 3] * 3 + c], b = p[tri[t * 3 + 1] * 3 + c], d = p[tri[t * 3 + 2] * 3 + c];
                int most = c == 0 ? gx : c == 1 ? gy : gz;
                box[c] = clamp((int) Math.floor((Math.min(a, Math.min(b, d)) - origin[c]) / cell), most);
                box[c + 3] = clamp((int) Math.floor((Math.max(a, Math.max(b, d)) - origin[c]) / cell), most);
            }
        }

        private static int clamp(int v, int most) {
            return Math.max(0, Math.min(most - 1, v));
        }

        /** The distance from q to the nearest triangle within {@code limit}, else {@code limit}. */
        float closest(float[] q, float limit, float[] scratch) {
            int x0 = clamp((int) Math.floor((q[0] - limit - origin[0]) / cell), gx), x1 = clamp((int) Math.floor((q[0] + limit - origin[0]) / cell), gx);
            int y0 = clamp((int) Math.floor((q[1] - limit - origin[1]) / cell), gy), y1 = clamp((int) Math.floor((q[1] + limit - origin[1]) / cell), gy);
            int z0 = clamp((int) Math.floor((q[2] - limit - origin[2]) / cell), gz), z1 = clamp((int) Math.floor((q[2] + limit - origin[2]) / cell), gz);
            float best = limit * limit;
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int c = (z * gy + y) * gx + x;
                        for (int k = start[c]; k < start[c + 1]; k++) best = Math.min(best, distanceSq(q, items[k], scratch));
                    }
                }
            }
            return (float) Math.sqrt(best);
        }

        /** Squared distance from q to triangle t: Ericson's closest point on a triangle (Real-Time Collision Detection 5.1.5). */
        private float distanceSq(float[] q, int t, float[] r) {
            int ia = tri[t * 3] * 3, ib = tri[t * 3 + 1] * 3, ic = tri[t * 3 + 2] * 3;
            float ax = p[ia], ay = p[ia + 1], az = p[ia + 2];
            float abx = p[ib] - ax, aby = p[ib + 1] - ay, abz = p[ib + 2] - az;
            float acx = p[ic] - ax, acy = p[ic + 1] - ay, acz = p[ic + 2] - az;
            float apx = q[0] - ax, apy = q[1] - ay, apz = q[2] - az;
            float d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
            if (d1 <= 0f && d2 <= 0f) return sq(apx, apy, apz);
            float bpx = q[0] - p[ib], bpy = q[1] - p[ib + 1], bpz = q[2] - p[ib + 2];
            float d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
            if (d3 >= 0f && d4 <= d3) return sq(bpx, bpy, bpz);
            float vc = d1 * d4 - d3 * d2;
            if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
                float v = d1 / (d1 - d3);
                return sq(apx - v * abx, apy - v * aby, apz - v * abz);
            }
            float cpx = q[0] - p[ic], cpy = q[1] - p[ic + 1], cpz = q[2] - p[ic + 2];
            float d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
            if (d6 >= 0f && d5 <= d6) return sq(cpx, cpy, cpz);
            float vb = d5 * d2 - d1 * d6;
            if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
                float w = d2 / (d2 - d6);
                return sq(apx - w * acx, apy - w * acy, apz - w * acz);
            }
            float va = d3 * d6 - d5 * d4;
            if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
                float w = (d4 - d3) / (d4 - d3 + d5 - d6);
                float bcx = p[ic] - p[ib], bcy = p[ic + 1] - p[ib + 1], bcz = p[ic + 2] - p[ib + 2];
                return sq(bpx - w * bcx, bpy - w * bcy, bpz - w * bcz);
            }
            float denom = 1f / (va + vb + vc), v = vb * denom, w = vc * denom;
            return sq(apx - abx * v - acx * w, apy - aby * v - acy * w, apz - abz * v - acz * w);
        }

        private static float sq(float x, float y, float z) {
            return x * x + y * y + z * z;
        }

        /** Unreal's vote: inside where more than a quarter of the rays from q hit a back face first. */
        boolean inside(float[] q) {
            int backs = 0, dirs = DIRECTIONS.length / 3;
            for (int k = 0; k < dirs; k++) {
                if (firstHitIsBack(q, DIRECTIONS[k * 3], DIRECTIONS[k * 3 + 1], DIRECTIONS[k * 3 + 2])) backs++;
            }
            return backs > dirs / 4;
        }

        /** Whether the first triangle the ray from q along d hits faces away from it: Amanatides and Woo's walk. */
        private boolean firstHitIsBack(float[] q, float dx, float dy, float dz) {
            float[] d = {dx, dy, dz};
            int[] at = new int[3], step = new int[3], most = {gx, gy, gz};
            float[] next = new float[3], delta = new float[3];
            for (int c = 0; c < 3; c++) {
                float rel = (q[c] - origin[c]) / cell;
                at[c] = clamp((int) Math.floor(rel), most[c]);
                step[c] = d[c] > 0f ? 1 : d[c] < 0f ? -1 : 0;
                delta[c] = d[c] != 0f ? Math.abs(cell / d[c]) : Float.MAX_VALUE;
                float edge = origin[c] + (at[c] + (step[c] > 0 ? 1 : 0)) * cell;
                next[c] = d[c] != 0f ? (edge - q[c]) / d[c] : Float.MAX_VALUE;
            }
            float best = Float.MAX_VALUE;
            boolean back = false;
            while (true) {
                int c0 = (at[2] * gy + at[1]) * gx + at[0];
                for (int k = start[c0]; k < start[c0 + 1]; k++) {
                    int t = items[k];
                    float hit = intersect(q, d, t);
                    if (hit > 0f && hit < best) {
                        best = hit;
                        back = facing(d, t) > 0f;
                    }
                }
                int axis = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
                if (best <= next[axis] || next[axis] > reach) return best < Float.MAX_VALUE && back;
                at[axis] += step[axis];
                if (at[axis] < 0 || at[axis] >= most[axis]) return best < Float.MAX_VALUE && back;
                next[axis] += delta[axis];
            }
        }

        /** The ray's distance to triangle t, Moller and Trumbore's; 0 for none. */
        private float intersect(float[] q, float[] d, int t) {
            int ia = tri[t * 3] * 3, ib = tri[t * 3 + 1] * 3, ic = tri[t * 3 + 2] * 3;
            float e1x = p[ib] - p[ia], e1y = p[ib + 1] - p[ia + 1], e1z = p[ib + 2] - p[ia + 2];
            float e2x = p[ic] - p[ia], e2y = p[ic + 1] - p[ia + 1], e2z = p[ic + 2] - p[ia + 2];
            float hx = d[1] * e2z - d[2] * e2y, hy = d[2] * e2x - d[0] * e2z, hz = d[0] * e2y - d[1] * e2x;
            float det = e1x * hx + e1y * hy + e1z * hz;
            if (Math.abs(det) < 1e-12f) return 0f;
            float inv = 1f / det;
            float sx = q[0] - p[ia], sy = q[1] - p[ia + 1], sz = q[2] - p[ia + 2];
            float u = (sx * hx + sy * hy + sz * hz) * inv;
            if (u < 0f || u > 1f) return 0f;
            float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            float v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
            if (v < 0f || u + v > 1f) return 0f;
            float hit = (e2x * qx + e2y * qy + e2z * qz) * inv;
            return hit > 1e-6f ? hit : 0f;
        }

        /** The ray's direction against triangle t's face normal: positive where it meets the back. */
        private float facing(float[] d, int t) {
            int ia = tri[t * 3] * 3, ib = tri[t * 3 + 1] * 3, ic = tri[t * 3 + 2] * 3;
            float e1x = p[ib] - p[ia], e1y = p[ib + 1] - p[ia + 1], e1z = p[ib + 2] - p[ia + 2];
            float e2x = p[ic] - p[ia], e2y = p[ic + 1] - p[ia + 1], e2z = p[ic + 2] - p[ia + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            return d[0] * nx + d[1] * ny + d[2] * nz;
        }
    }

    /** The 26 directions from a cube's centre to its neighbours, normalised and turned off the axes a little. */
    private static float[] directions() {
        float[] out = new float[26 * 3];
        int k = 0;
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    // Off the axes, so a ray along a box's face or edge does not graze it.
                    float dx = x + 0.0131f, dy = y + 0.0097f, dz = z + 0.0071f;
                    float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    out[k++] = dx / len;
                    out[k++] = dy / len;
                    out[k++] = dz / len;
                }
            }
        }
        return out;
    }
}
