package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshChanges;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where meshes' GPU copies live: a {@link CgMeshPool} per vertex format, and a placement per mesh that draws -- its
 * slab, base vertex and first index. The executor places every mesh a frame draws, uploads what changed before the
 * frame's first pass, and draws each with a base-vertex call, so meshes in one slab share every binding.
 *
 * <pre>{@code
 * CgMeshStore store = CgMeshStore.get();
 * store.place(mesh);                        // for every mesh the frame draws
 * store.upload();                           // once, before the first raster pass
 * pipeline.bind();
 * store.draw(mesh, pipeline, instances);    // in the pass
 * }</pre>
 *
 * <ul>
 *   <li>An edit takes a new range: one a frame in flight reads is never written. The old range is freed once the
 *       last frame that drew it has retired, as is a released mesh's.</li>
 *   <li>The bytes are copied out of the mesh when it is placed, so an edit afterwards waits for the next frame.</li>
 *   <li>An upload is a copy from the frame ring into the slab: on Vulkan a transfer before the pass, never inside it.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public final class CgMeshStore {

    private static final CgMeshStore STORE = new CgMeshStore();
    private static final int STAGING_START = 1 << 20;

    private static final int UPLOADS = CgTrace.name("mesh.uploads");
    private static final int UPLOAD_BYTES = CgTrace.name("mesh.upload-bytes");
    private static final int PLACED = CgTrace.name("mesh.placed");
    private static final int SLAB_KB = CgTrace.name("mesh.slab-kb");
    private static final int DRAWN = CgTrace.name("mesh.drawn-vertices");

    private long drawing, drawn;

    public static CgMeshStore get() {
        return STORE;
    }

    /** The vertices the last whole frame drew, every instance's, an index counting as one. */
    public long drawnVertices() {
        return drawn;
    }

    /** Where one mesh's copy is, and the revision and draw facts it was made from. */
    private static final class Placement {
        CgMesh mesh;
        CgMeshPool.Slab slab;
        int vertexNode, indexNode;
        int baseVertex, firstIndex, vertexCount, indexCount, mode;
        int revision, releases;
        long lastUse;
        /** Placed in this upload batch, its bytes not yet copied: nothing may be copied out of it yet. */
        boolean pending;
        int[] submeshes = new int[4];
        int submeshCount;
    }

    private final Map<CgVertexFormat, CgMeshPool> pools = new HashMap<>();
    private final IdentityHashMap<CgMesh, Placement> placements = new IdentityHashMap<>();
    private final ArrayList<Placement> live = new ArrayList<>();
    private final ArrayList<Placement> retiring = new ArrayList<>();
    private final ArrayList<Placement> spare = new ArrayList<>();
    private final ArrayList<Placement> batch = new ArrayList<>();

    /** Per copy: source buffer (0 for staging), destination buffer, source and destination byte offsets, size. */
    private long[] copies = new long[5 * 32];
    private int copyCount;
    private ByteBuffer cpu = ByteBuffer.allocate(64 << 10).order(ByteOrder.nativeOrder());
    private CgStreamBuffer staging;

    private final CgMeshChanges changes = new CgMeshChanges();
    private final int[] nodes = new int[2], submesh = new int[4];
    private long frame = Long.MIN_VALUE;
    private long slabBytes;

    private CgMeshStore() {
    }

    // ── Placing ────────────────────────────────────────────────────────────────

    /** Makes {@code mesh} drawable this frame: placed, and its bytes staged if it is new or changed. */
    public void place(CgMesh mesh) {
        beginFrame();
        Placement p = placements.get(mesh);
        if (p != null && p.releases != mesh.releases()) {
            retire(p);
            p = null;
        }
        synchronized (mesh) {
            if (p != null) {
                if (!mesh.changesSince(p.revision, changes)) {
                    p.lastUse = frame;
                    return;
                }
                boolean bytes = changes.all || changes.vertexTo > changes.vertexFrom || changes.indexTo > changes.indexFrom;
                if (!bytes && mesh.vertexCount() == p.vertexCount && mesh.indexCount() == p.indexCount) {
                    describe(p, mesh);
                    p.revision = changes.revision;
                    p.lastUse = frame;
                    return;
                }
            } else {
                mesh.changesSince(0, changes);
            }
            Placement next = allocate(mesh);
            if (p == null || changes.all || p.pending || p.slab == null) {
                stage(next, 0, next.vertexCount, 0, next.indexCount);
            } else {
                keep(p, next);
                stage(next, Math.min(changes.vertexFrom, next.vertexCount), Math.min(changes.vertexTo, next.vertexCount),
                        Math.min(changes.indexFrom, next.indexCount), Math.min(changes.indexTo, next.indexCount));
            }
            if (p != null) retire(p);
            placements.put(mesh, next);
            live.add(next);
        }
        CgTrace.add(CgChannels.GL, PLACED, 1);
    }

    /** A placement for {@code mesh} as it is now, its ranges allocated. Under the mesh's lock. */
    private Placement allocate(CgMesh mesh) {
        Placement p = spare.isEmpty() ? new Placement() : spare.remove(spare.size() - 1);
        p.mesh = mesh;
        p.vertexCount = mesh.vertexCount();
        p.indexCount = mesh.indexCount();
        p.revision = changes.revision;
        p.releases = mesh.releases();
        p.lastUse = frame;
        p.slab = null;
        p.vertexNode = p.indexNode = -1;
        p.baseVertex = p.firstIndex = 0;
        describe(p, mesh);
        if (p.vertexCount > 0) {
            CgMeshPool pool = pools.computeIfAbsent(mesh.format(), CgMeshPool::new);
            int slabs = pool.slabs.size();
            p.slab = pool.place(p.vertexCount, p.indexCount, nodes);
            if (pool.slabs.size() != slabs) slabBytes += p.slab.bytes;
            p.vertexNode = nodes[0];
            p.indexNode = nodes[1];
            p.baseVertex = p.vertexNode >= 0 ? p.slab.vertices.offset(p.vertexNode) : 0;
            p.firstIndex = p.indexNode >= 0 ? p.slab.indices.offset(p.indexNode) : 0;
        }
        p.pending = true;
        batch.add(p);
        return p;
    }

    private void describe(Placement p, CgMesh mesh) {
        p.mode = mesh.topology().getGlMode();
        p.submeshCount = mesh.submeshCount();
        if (p.submeshes.length < p.submeshCount * 4) p.submeshes = new int[p.submeshCount * 4];
        for (int s = 0; s < p.submeshCount; s++) {
            mesh.submesh(s, submesh);
            System.arraycopy(submesh, 0, p.submeshes, s * 4, 4);
        }
    }

    /** Copies what {@code from} holds into {@code to} on the GPU: the part of the mesh that did not change. */
    private void keep(Placement from, Placement to) {
        int stride = to.mesh.format().getStride();
        int vertices = Math.min(from.vertexCount, to.vertexCount), indices = Math.min(from.indexCount, to.indexCount);
        if (vertices > 0 && stride > 0) {
            copy(from.slab.vertexBuffer, to.slab.vertexBuffer, (long) from.baseVertex * stride, (long) to.baseVertex * stride,
                    (long) vertices * stride);
        }
        if (indices > 0) {
            copy(from.slab.indexBuffer, to.slab.indexBuffer, (long) from.firstIndex * 4, (long) to.firstIndex * 4,
                    (long) indices * 4);
        }
    }

    /** Copies vertices and indices out of the mesh into the CPU staging, and records their uploads. */
    private void stage(Placement p, int vertexFrom, int vertexTo, int indexFrom, int indexTo) {
        if (p.slab == null) return;
        int stride = p.mesh.format().getStride();
        if (vertexTo > vertexFrom && stride > 0) {
            int bytes = (vertexTo - vertexFrom) * stride;
            int at = reserve(bytes);
            p.mesh.readVertices(vertexFrom, vertexTo - vertexFrom, cpu);
            copy(0, p.slab.vertexBuffer, at, (long) (p.baseVertex + vertexFrom) * stride, bytes);
        }
        if (indexTo > indexFrom) {
            int bytes = (indexTo - indexFrom) * 4;
            int at = reserve(bytes);
            p.mesh.readIndices(indexFrom, indexTo - indexFrom, cpu);
            copy(0, p.slab.indexBuffer, at, (long) (p.firstIndex + indexFrom) * 4, bytes);
        }
    }

    private int reserve(int bytes) {
        if (cpu.remaining() < bytes) {
            ByteBuffer grown = ByteBuffer.allocate(Math.max(cpu.capacity() * 2, cpu.position() + bytes))
                    .order(ByteOrder.nativeOrder());
            cpu.flip();
            grown.put(cpu);
            cpu = grown;
        }
        return cpu.position();
    }

    private void copy(long from, long to, long fromOffset, long toOffset, long size) {
        if (copyCount * 5 == copies.length) copies = Arrays.copyOf(copies, copies.length * 2);
        int at = copyCount++ * 5;
        copies[at] = from;
        copies[at + 1] = to;
        copies[at + 2] = fromOffset;
        copies[at + 3] = toOffset;
        copies[at + 4] = size;
    }

    // ── Uploading ──────────────────────────────────────────────────────────────

    /**
     * Copies everything placed since the last call into its slabs: the staged bytes through the frame ring, after
     * what changed meshes kept from their old ranges. Before the frame's first raster pass.
     */
    public void upload() {
        if (copyCount == 0) {
            batch.clear();
            return;
        }
        int staged = cpu.position(), stagingBuffer = 0;
        long stagingOffset = 0;
        if (staged > 0) {
            if (staging == null) staging = CgStreamBuffer.create(CgGL.GL_COPY_READ_BUFFER, Math.max(STAGING_START, staged));
            ByteBuffer out = staging.map(staged);
            cpu.flip();
            out.put(cpu);
            stagingOffset = staging.commit(staged);
            stagingBuffer = staging.getGlBufferId();
        }
        // Kept content first: a staged change lands over it.
        for (int pass = 0; pass < 2; pass++) {
            for (int c = 0; c < copyCount; c++) {
                int at = c * 5;
                boolean fromStaging = copies[at] == 0;
                if (fromStaging != (pass == 1)) continue;
                int from = fromStaging ? stagingBuffer : (int) copies[at];
                long fromOffset = fromStaging ? stagingOffset + copies[at + 2] : copies[at + 2];
                CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
                CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, (int) copies[at + 1]);
                CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, fromOffset,
                        copies[at + 3], copies[at + 4]);
            }
        }
        CgTrace.add(CgChannels.GL, UPLOADS, copyCount);
        CgTrace.add(CgChannels.GL, UPLOAD_BYTES, staged);
        copyCount = 0;
        cpu.clear();
        for (int i = 0; i < batch.size(); i++) batch.get(i).pending = false;
        batch.clear();
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    /**
     * Draws {@code mesh}, every submesh, {@code instances} times, with {@code pipeline} bound: what
     * {@code CG_VERTEX_ID} subtracts is set per submesh. Leaves the slab's vertex array bound; the caller binds 0
     * when its draws are done.
     */
    public void draw(CgMesh mesh, CgPipeline pipeline, int instances) {
        draw(mesh, pipeline, instances, -1, 0, -1);
    }

    /**
     * As {@link #draw(CgMesh, CgPipeline, int)}, a range of it: submesh {@code submesh}'s indices from {@code first},
     * {@code count} of them (-1 to its end), or its vertices for a submesh with no indices; {@code submesh} -1 draws
     * every submesh whole. The base vertex, and so {@code CG_VERTEX_ID}, is the submesh's whatever the range.
     */
    public void draw(CgMesh mesh, CgPipeline pipeline, int instances, int submesh, int first, int count) {
        Placement p = placements.get(mesh);
        if (p == null || p.releases != mesh.releases()) {
            // Not placed with the frame: placed now, which on Vulkan breaks the pass for its upload.
            place(mesh);
            upload();
            p = placements.get(mesh);
        }
        if (p == null || p.slab == null) return;
        p.lastUse = frame;
        CgVertexArray.bind(p.slab.vao);
        // LWJGL 2 checks an indexed draw's offset against the element binding it saw bound, never the vertex array's.
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, p.slab.indexBuffer);
        int from = submesh < 0 ? 0 : submesh, to = submesh < 0 ? p.submeshCount : Math.min(submesh + 1, p.submeshCount);
        for (int s = from; s < to; s++) {
            int firstIndex = p.submeshes[s * 4], indexCount = p.submeshes[s * 4 + 1];
            int firstVertex = p.submeshes[s * 4 + 2], vertexCount = p.submeshes[s * 4 + 3];
            int base = p.baseVertex + firstVertex;
            int total = indexCount > 0 ? indexCount : vertexCount;
            int start = submesh < 0 ? 0 : Math.min(first, total);
            int n = submesh < 0 || count < 0 ? total - start : Math.min(count, total - start);
            if (n <= 0) continue;
            drawing += (long) n * instances;
            CgTrace.add(CgChannels.GL, DRAWN, (long) n * instances);
            pipeline.vertexBase(base);
            if (indexCount > 0) {
                CgGL.glDrawElementsInstancedBaseVertex(p.mode, n, CgGL.GL_UNSIGNED_INT,
                        (long) (p.firstIndex + firstIndex + start) * 4, instances, base);
            } else {
                CgGL.glDrawArraysInstanced(p.mode, base + start, n, instances);
            }
        }
    }

    // ── Frames, releases, teardown ─────────────────────────────────────────────

    /** At a frame's first call: frees the ranges whose last frame has retired, and retires released meshes. */
    private void beginFrame() {
        long now = CgFrameRing.frame();
        if (now == frame) return;
        frame = now;
        drawn = drawing;
        drawing = 0;
        CgTrace.counter(CgChannels.GL, SLAB_KB, slabBytes >> 10);
        long safe = now - CgFrameRing.FRAMES;
        for (int i = retiring.size() - 1; i >= 0; i--) {
            Placement p = retiring.get(i);
            if (p.lastUse > safe) continue;
            CgFrameRing.awaitRetired(p.lastUse);
            free(p);
            retiring.set(i, retiring.get(retiring.size() - 1));
            retiring.remove(retiring.size() - 1);
        }
        for (int i = live.size() - 1; i >= 0; i--) {
            Placement p = live.get(i);
            if (p.releases != p.mesh.releases()) retire(p);
        }
    }

    private void retire(Placement p) {
        if (placements.get(p.mesh) == p) placements.remove(p.mesh);
        int at = live.indexOf(p);
        if (at >= 0) {
            live.set(at, live.get(live.size() - 1));
            live.remove(live.size() - 1);
        }
        retiring.add(p);
    }

    private void free(Placement p) {
        if (p.slab != null) {
            if (p.vertexNode >= 0) p.slab.vertices.free(p.vertexNode);
            if (p.indexNode >= 0) p.slab.indices.free(p.indexNode);
        }
        p.mesh = null;
        p.slab = null;
        spare.add(p);
    }

    /** Frees every slab and the staging, and forgets every placement. Context teardown; the next draw places anew. */
    public void releaseAll() {
        for (CgMeshPool pool : pools.values()) pool.delete();
        pools.clear();
        placements.clear();
        live.clear();
        retiring.clear();
        batch.clear();
        copyCount = 0;
        cpu.clear();
        if (staging != null) staging.delete();
        staging = null;
        slabBytes = 0;
        frame = Long.MIN_VALUE;
    }
}
