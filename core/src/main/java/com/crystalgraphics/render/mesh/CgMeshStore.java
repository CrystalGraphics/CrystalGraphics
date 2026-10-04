package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshChanges;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
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
 *
 * // An indirect draw: its command is written on the GPU from what range() answers, before the pass
 * store.range(mesh, submesh, first, count, range);
 * store.drawIndirect(mesh, pipeline, submesh, args, offset);
 *
 * // Draws of meshes that join, as one call (CgCapabilities.multiDraw()): each with its own first instance
 * if (store.join(a, 1, -1, 0, -1, firstA) && store.join(b, 1, -1, 0, -1, firstB)) {
 *     pipeline.multiDraw().bind();
 *     store.drawJoined();
 * } else {
 *     store.dropJoined();                   // and draw() each
 * }
 * }</pre>
 *
 * <ul>
 *   <li>An edit takes a new range: one a frame in flight reads is never written. The old range is freed once the
 *       last frame that drew it has retired, as is a released mesh's.</li>
 *   <li>The bytes are copied out of the mesh when it is placed, so an edit afterwards waits for the next frame.</li>
 *   <li>An upload is a copy from the frame ring into the slab: on Vulkan a transfer before the pass, never inside it.</li>
 *   <li>A {@link CgMesh.Usage#FRAME} mesh takes no slab: each frame it is placed, its bytes are written straight into
 *       a page of the frame ring ({@link CgMeshRing}), and it draws from there with base-vertex calls. It holds
 *       nothing between frames, so it needs no release, and is forgotten after a frame it is not drawn in.
 *       {@code -Dcrystalgraphics.mesh.frameRing=false} places it in a slab as any other.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public final class CgMeshStore {

    private static final CgMeshStore STORE = new CgMeshStore();
    private static final Logger LOGGER = LogManager.getLogger("CgMeshStore");
    private static final int STAGING_START = 1 << 20;

    private static final int UPLOADS = CgTrace.name("mesh.uploads");
    private static final int UPLOAD_BYTES = CgTrace.name("mesh.upload-bytes");
    private static final int PLACED = CgTrace.name("mesh.placed");
    private static final int SLAB_KB = CgTrace.name("mesh.slab-kb");
    private static final int DRAWN = CgTrace.name("mesh.drawn-vertices");
    private static final int DRAWN_INDIRECT = CgTrace.name("mesh.indirect-draws");
    private static final int RING_BYTES = CgTrace.name("mesh.ring-bytes");
    private static final int EDITED_EVERY_FRAME = CgTrace.name("mesh.edited-every-frame");
    private static final int MULTI_DRAWS = CgTrace.name("mesh.multi-draws");
    private static final int MULTI_COMMANDS = CgTrace.name("mesh.multi-draw-commands");
    /** An indexed indirect command: count, instances, first index, base vertex, first instance. */
    private static final int COMMAND_WORDS = 5;

    /** Consecutive frames of edits after which a mesh that is not FRAME is reported. */
    private static final int EVERY_FRAME = 60;

    /** {@code -Dcrystalgraphics.mesh.frameRing=false}: FRAME meshes take slab ranges, as before the ring path. */
    private static final boolean FRAME_RING = !"false".equalsIgnoreCase(System.getProperty("crystalgraphics.mesh.frameRing"));

    private long drawing, drawn, calls;
    private boolean multiDraw = true;

    public static CgMeshStore get() {
        return STORE;
    }

    /** The vertices the last whole frame drew, every instance's, an index counting as one. */
    public long drawnVertices() {
        return drawn;
    }

    /** Every draw call the store has made, for a check to difference around a frame: a multi-draw counts as one. */
    public long drawCalls() {
        return calls;
    }

    /**
     * Whether an executor joins runs of draws into multi-draw calls ({@link #join}), where
     * {@code CgCapabilities.multiDraw()} holds. On; a check turns it off to draw the same frame one call a draw.
     */
    public void multiDraw(boolean on) {
        multiDraw = on;
    }

    public boolean multiDraw() {
        return multiDraw;
    }

    /** Where one mesh's copy is, and the revision and draw facts it was made from. */
    private static final class Placement {
        CgMesh mesh;
        CgMeshPool.Slab slab;
        int vertexNode, indexNode;
        int baseVertex, firstIndex, vertexCount, indexCount, mode;
        int revision, releases;
        long lastUse;
        /** The last frame its bytes changed, how many frames in a row they have, and whether that was reported. */
        long editedFrame;
        int editStreak;
        boolean editReported;
        /** Placed in this upload batch, its bytes not yet copied: nothing may be copied out of it yet. */
        boolean pending;
        /** A FRAME mesh on the ring: its page and offsets are this frame's, and {@link #placedFrame} says which frame. */
        boolean ring;
        /** A ring placement's format's pool, which holds the page's vertex array. */
        CgMeshPool pool;
        long placedFrame;
        int ringPage;
        int[] submeshes = new int[4];
        int submeshCount;
        /** How a multi-draw joins it: {@link #BY_INDICES} or {@link #BY_SEQUENCE} for every submesh alike, else 0. */
        int joinsAs;
    }

    /** A placement drawn by its own indices, or by {@link #sequence} for one with none. */
    private static final int BY_INDICES = 1, BY_SEQUENCE = 2;

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

    /** Where FRAME meshes' bytes go, valid for the frame that wrote them. */
    private CgMeshRing ring;
    /** Ring placements, forgotten when a frame passes without them. */
    private final ArrayList<Placement> ringLive = new ArrayList<>();

    /** The commands joined since the last {@link #drawJoined}, and the placement whose buffers they draw from. */
    private int[] joined = new int[COMMAND_WORDS * 16];
    private int joinedCount;
    @Nullable
    private Placement joinedFrom;
    private CgStreamBuffer commands;
    /**
     * Indices 0, 1, 2 ...: what a joined draw of a mesh without indices draws by, so every joined draw is by indices,
     * where GL and Vulkan agree on {@code gl_BaseVertex}. Grown in {@link #upload} to the largest such submesh placed.
     */
    private int sequence, sequenceLength, sequenceNeed;

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
        if (FRAME_RING && mesh.usage() == CgMesh.Usage.FRAME) {
            placeOnRing(mesh);
            return;
        }
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
            countEdit(p, next);
            if (p == null || changes.all || p.pending || p.slab == null) {
                stage(next, 0, next.vertexCount, 0, next.indexCount);
                mesh.dropCpuCopy();   // a GPU_ONLY mesh's bytes are in the staging now
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

    /**
     * A mesh that takes a new range every frame pays for one, and for copying what it kept, every frame: counted, and
     * reported once, since {@link CgMesh.Usage#FRAME} writes it into the frame ring instead.
     */
    private void countEdit(Placement previous, Placement next) {
        next.editedFrame = frame;
        next.editStreak = previous != null && previous.editedFrame == frame - 1 ? previous.editStreak + 1 : 1;
        next.editReported = previous != null && previous.editReported;
        if (next.editStreak < EVERY_FRAME || next.mesh.usage() == CgMesh.Usage.FRAME) return;
        CgTrace.add(CgChannels.GL, EDITED_EVERY_FRAME, 1);
        if (next.editReported) return;
        next.editReported = true;
        Throwable site = next.mesh.editSite();
        LOGGER.warn("[cg-mesh] {} was edited in each of the last {} frames: give it Usage.FRAME, which writes it into "
                + "the frame ring with no range of its own.{}", next.mesh, EVERY_FRAME,
                site == null ? " -Dcrystalgraphics.mesh.editStacks=true names where." : " The last edit:", site);
    }

    /** Writes a FRAME mesh's bytes into the ring, once a frame however often it draws. */
    private void placeOnRing(CgMesh mesh) {
        Placement p = placements.get(mesh);
        if (p != null && p.placedFrame == frame) return;
        if (p == null) {
            p = spare.isEmpty() ? new Placement() : spare.remove(spare.size() - 1);
            p.mesh = mesh;
            p.ring = true;
            p.slab = null;
            p.vertexNode = p.indexNode = -1;
            placements.put(mesh, p);
            ringLive.add(p);
        }
        p.placedFrame = frame;
        p.lastUse = frame;
        if (ring == null) ring = new CgMeshRing(pools.values());
        p.pool = pools.computeIfAbsent(mesh.format(), CgMeshPool::new);
        int stride = mesh.format().getStride();
        synchronized (mesh) {
            p.releases = mesh.releases();
            p.vertexCount = mesh.vertexCount();
            p.indexCount = mesh.indexCount();
            describe(p, mesh);
            int vertexBytes = stride > 0 ? p.vertexCount * stride : 0, indexBytes = p.indexCount * 4;
            p.ringPage = -1;
            p.baseVertex = p.firstIndex = 0;
            if (vertexBytes + indexBytes > 0) {
                ByteBuffer out = ring.reserve(vertexBytes, stride, indexBytes);
                if (vertexBytes > 0) mesh.readVertices(0, p.vertexCount, out);
                out.position(ring.indexPosition);
                if (indexBytes > 0) mesh.readIndices(0, p.indexCount, out);
                p.ringPage = ring.page;
                p.baseVertex = ring.baseVertex;
                p.firstIndex = ring.firstIndex;
                CgTrace.add(CgChannels.GL, RING_BYTES, vertexBytes + indexBytes);
            }
        }
        CgTrace.add(CgChannels.GL, PLACED, 1);
    }

    /** A placement for {@code mesh} as it is now, its ranges allocated. Under the mesh's lock. */
    private Placement allocate(CgMesh mesh) {
        Placement p = spare.isEmpty() ? new Placement() : spare.remove(spare.size() - 1);
        p.mesh = mesh;
        p.ring = false;
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

    private static int glMode(CgMeshTopology topology) {
        return switch (topology) {
            case TRIANGLES -> CgGL.GL_TRIANGLES;
            case TRIANGLE_STRIP -> CgGL.GL_TRIANGLE_STRIP;
            case LINES -> CgGL.GL_LINES;
            case LINE_STRIP -> CgGL.GL_LINE_STRIP;
            case POINTS -> CgGL.GL_POINTS;
        };
    }

    private void describe(Placement p, CgMesh mesh) {
        p.mode = glMode(mesh.topology());
        p.submeshCount = mesh.submeshCount();
        if (p.submeshes.length < p.submeshCount * 4) p.submeshes = new int[p.submeshCount * 4];
        int indexed = 0, longest = 0;
        for (int s = 0; s < p.submeshCount; s++) {
            mesh.submesh(s, submesh);
            System.arraycopy(submesh, 0, p.submeshes, s * 4, 4);
            if (submesh[1] > 0) indexed++;
            else longest = Math.max(longest, submesh[3]);
        }
        p.joinsAs = p.submeshCount == 0 ? 0 : indexed == p.submeshCount ? BY_INDICES : indexed == 0 ? BY_SEQUENCE : 0;
        if (p.joinsAs == BY_SEQUENCE) sequenceNeed = Math.max(sequenceNeed, longest);
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
        if (sequenceNeed > sequenceLength) growSequence();
        if (ring != null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "mesh.commitRing")) {
                ring.commit();
            }
        }
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

    private void growSequence() {
        int length = Math.max(sequenceNeed, Math.max(1024, sequenceLength * 2));
        ByteBuffer indices = CgBufferUtils.createByteBuffer(length * 4);
        for (int i = 0; i < length; i++) indices.putInt(i);
        indices.flip();
        if (sequence == 0) sequence = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, sequence);   // not the element binding, which a vertex array owns
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, indices, CgGL.GL_STATIC_DRAW);
        sequenceLength = length;
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
        Placement p = placed(mesh);
        if (p == null) return;
        bind(p, -1);
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
            calls++;
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

    /**
     * What an indirect draw of a range of {@code mesh} composes its command from, into {@code out}: where the range
     * starts (in the indices the slab draws from, or a vertex for a mesh without), how many it holds, its base vertex,
     * and 1 when it is drawn by indices. A range as {@link #draw}'s, of one submesh ({@code submesh} -1 is the first).
     * False, writing nothing, when the mesh has nothing to draw.
     */
    public boolean range(CgMesh mesh, int submesh, int first, int count, int[] out) {
        Placement p = placed(mesh);
        if (p == null || p.submeshCount == 0) return false;
        int s = Math.min(Math.max(submesh, 0), p.submeshCount - 1);
        int firstIndex = p.submeshes[s * 4], indexCount = p.submeshes[s * 4 + 1];
        int firstVertex = p.submeshes[s * 4 + 2], vertexCount = p.submeshes[s * 4 + 3];
        int base = p.baseVertex + firstVertex;
        int total = indexCount > 0 ? indexCount : vertexCount;
        int start = Math.min(first, total);
        out[0] = indexCount > 0 ? p.firstIndex + firstIndex + start : base + start;
        out[1] = count < 0 ? total - start : Math.min(count, total - start);
        out[2] = base;
        out[3] = indexCount > 0 ? 1 : 0;
        return true;
    }

    /**
     * Draws a range of {@code mesh} by the command at byte {@code offset} in buffer {@code args}, written from what
     * {@link #range} answered for it. Sets what {@code CG_VERTEX_ID} subtracts, and leaves the slab's vertex array
     * bound, as {@link #draw} does.
     */
    public void drawIndirect(CgMesh mesh, CgPipeline pipeline, int submesh, int args, long offset) {
        Placement p = placed(mesh);
        if (p == null || p.submeshCount == 0) return;
        bind(p, -1);
        int s = Math.min(Math.max(submesh, 0), p.submeshCount - 1);
        pipeline.vertexBase(p.baseVertex + p.submeshes[s * 4 + 2]);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, args);
        if (p.submeshes[s * 4 + 1] > 0) CgGL.glDrawElementsIndirect(p.mode, CgGL.GL_UNSIGNED_INT, offset);
        else CgGL.glDrawArraysIndirect(p.mode, offset);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        calls++;
        CgTrace.add(CgChannels.GL, DRAWN_INDIRECT, 1);
    }

    /**
     * Adds a draw to the call {@link #drawJoined} makes, if its mesh joins those added since that call last ran: from
     * the same vertex array, in one topology, drawn by indices or without them as they are. False, adding nothing, when
     * it does not. A range as {@link #draw(CgMesh, CgPipeline, int, int, int, int)} takes, its instances counted from
     * {@code firstInstance} rather than {@code cg_InstanceBase}.
     *
     * <pre>{@code
     * int n = 0;
     * while (n < meshes.length && store.join(meshes[n], 1, -1, 0, -1, firsts[n])) n++;
     * if (n > 1) {
     *     pipeline.multiDraw().bind();     // CG_INSTANCE_ID and CG_VERTEX_ID from each command
     *     store.drawJoined();              // n draws, one call
     * } else {
     *     store.dropJoined();              // one draw is drawn the plain way
     * }
     * }</pre>
     */
    public boolean join(CgMesh mesh, int instances, int submesh, int first, int count, int firstInstance) {
        Placement p = placed(mesh), q = joinedFrom;
        if (p == null || p.joinsAs == 0) return false;
        if (q == null) {
            joinedFrom = p;
        } else if (!joins(p, q)) {
            return false;
        }
        int from = submesh < 0 ? 0 : submesh, to = submesh < 0 ? p.submeshCount : Math.min(submesh + 1, p.submeshCount);
        boolean indexed = p.joinsAs == BY_INDICES;
        for (int s = from; s < to; s++) {
            int total = p.submeshes[s * 4 + (indexed ? 1 : 3)];
            int start = submesh < 0 ? 0 : Math.min(first, total);
            int n = submesh < 0 || count < 0 ? total - start : Math.min(count, total - start);
            if (n <= 0) continue;
            if ((joinedCount + 1) * COMMAND_WORDS > joined.length) joined = Arrays.copyOf(joined, joined.length * 2);
            int at = joinedCount++ * COMMAND_WORDS;
            joined[at] = n;
            joined[at + 1] = instances;
            joined[at + 2] = indexed ? p.firstIndex + p.submeshes[s * 4] + start : start;   // into the sequence
            joined[at + 3] = p.baseVertex + p.submeshes[s * 4 + 2];
            joined[at + 4] = firstInstance;
        }
        return true;
    }

    /** Forgets what {@link #join} added since {@link #drawJoined} last ran, drawing none of it. */
    public void dropJoined() {
        joinedFrom = null;
        joinedCount = 0;
    }

    /**
     * Draws everything {@link #join}ed since the last call as one {@code glMultiDrawElementsIndirect}, its commands
     * written to the frame ring, with a {@link CgPipeline#multiDraw()} pipeline bound. Leaves the vertex array bound,
     * as {@link #draw} does.
     */
    public void drawJoined() {
        Placement p = joinedFrom;
        int n = joinedCount;
        dropJoined();
        if (n == 0) return;
        bind(p, p.joinsAs == BY_SEQUENCE ? sequence : -1);
        if (commands == null) commands = CgStreamBuffer.create(CgGL.GL_DRAW_INDIRECT_BUFFER, 64 << 10);
        int bytes = n * COMMAND_WORDS * 4;
        ByteBuffer out = commands.map(bytes).order(ByteOrder.nativeOrder());
        long vertices = 0;
        for (int i = 0; i < n * COMMAND_WORDS; i += COMMAND_WORDS) {
            out.putInt(joined[i]).putInt(joined[i + 1]).putInt(joined[i + 2]).putInt(joined[i + 3]).putInt(joined[i + 4]);
            vertices += (long) joined[i] * joined[i + 1];
        }
        drawing += vertices;
        CgTrace.add(CgChannels.GL, DRAWN, vertices);
        int offset = commands.commit(bytes);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, commands.getGlBufferId());
        CgGL.glMultiDrawElementsIndirect(p.mode, CgGL.GL_UNSIGNED_INT, offset, n, COMMAND_WORDS * 4);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        calls++;
        CgTrace.add(CgChannels.GL, MULTI_DRAWS, 1);
        CgTrace.add(CgChannels.GL, MULTI_COMMANDS, n);
    }

    /**
     * Whether draws of {@code a} and {@code b} can be one multi-draw call: what {@link #join} asks of a run, for a
     * caller writing the commands itself. Both must have something to draw.
     */
    public boolean joins(CgMesh a, CgMesh b) {
        Placement p = placed(a), q = placed(b);
        return p != null && q != null && p.joinsAs != 0 && joins(p, q);
    }

    /**
     * What a command of {@link #drawIndirectJoined} draws a range of {@code mesh} from, into {@code out}, in
     * {@link #range}'s form: always by indices, a mesh without them by the shared run of 0, 1, 2 ... False, writing
     * nothing, when the mesh has nothing to draw or never joins.
     */
    public boolean joinedRange(CgMesh mesh, int submesh, int first, int count, int[] out) {
        Placement p = placed(mesh);
        if (p == null || p.joinsAs == 0 || !range(mesh, submesh, first, count, out)) return false;
        if (p.joinsAs == BY_SEQUENCE) {
            out[0] -= out[2];   // the first vertex, counted from the base: an index into the run
            out[3] = 1;
        }
        return true;
    }

    /**
     * Draws {@code n} commands {@code stride} bytes apart from byte {@code offset} of buffer {@code args} as one
     * {@code glMultiDrawElementsIndirect}, each written from {@link #joinedRange} for a mesh that {@link #joins}
     * {@code first}, with a {@link CgPipeline#multiDraw()} pipeline bound. Leaves the vertex array bound, as
     * {@link #draw} does.
     *
     * <pre>{@code
     * store.joinedRange(mesh, -1, 0, -1, range);       // per command, written into args on the GPU
     * pipeline.multiDraw().bind();
     * store.drawIndirectJoined(firstMesh, args, offset, n, stride);
     * }</pre>
     */
    public void drawIndirectJoined(CgMesh first, int args, long offset, int n, int stride) {
        Placement p = placed(first);
        if (p == null || p.joinsAs == 0) return;
        bind(p, p.joinsAs == BY_SEQUENCE ? sequence : -1);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, args);
        CgGL.glMultiDrawElementsIndirect(p.mode, CgGL.GL_UNSIGNED_INT, offset, n, stride);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        calls++;
        CgTrace.add(CgChannels.GL, DRAWN_INDIRECT, n);
        CgTrace.add(CgChannels.GL, MULTI_DRAWS, 1);
        CgTrace.add(CgChannels.GL, MULTI_COMMANDS, n);
    }

    /** Whether {@code p} and {@code q} share the vertex array, the indices and the topology a multi-draw needs. */
    private static boolean joins(Placement p, Placement q) {
        return p.joinsAs == q.joinsAs && p.mode == q.mode && p.ring == q.ring
                && (p.ring ? p.ringPage == q.ringPage && p.pool == q.pool : p.slab == q.slab);
    }

    /** {@code mesh}'s placement this frame, placed now if it was not placed with the frame; null with nothing to draw. */
    @Nullable
    private Placement placed(CgMesh mesh) {
        beginFrame();   // a frame executed again draws in a later frame than it was placed in
        Placement p = placements.get(mesh);
        if (p == null || p.releases != mesh.releases() || (p.ring && p.placedFrame != frame)) {
            // Not placed with the frame: placed now, which on Vulkan breaks the pass for its upload.
            place(mesh);
            upload();
            p = placements.get(mesh);
        }
        if (p == null || (p.slab == null && !p.ring)) return null;
        p.lastUse = frame;
        return p;
    }

    /** Binds the vertex array {@code p} draws from, and its indices or {@code elements} where that is not -1. */
    private void bind(Placement p, int elements) {
        int vao;
        if (p.ring) {
            CgStreamBuffer page = p.ringPage >= 0 ? ring.page(p.ringPage).buffer : null;
            vao = p.pool.ringVertexArray(Math.max(p.ringPage, 0), page);
            if (elements < 0) elements = page != null ? page.getGlBufferId() : 0;
        } else {
            vao = p.slab.vao;
            if (elements < 0) elements = p.slab.indexBuffer;
        }
        CgGL.glBindVertexArray(vao);
        // LWJGL 2 checks an indexed draw's offset against the element binding it saw bound, never the vertex array's.
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, elements);
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
        for (int i = ringLive.size() - 1; i >= 0; i--) {
            Placement p = ringLive.get(i);
            if (p.placedFrame >= now - 1) continue;
            if (placements.get(p.mesh) == p) placements.remove(p.mesh);
            ringLive.set(i, ringLive.get(ringLive.size() - 1));
            ringLive.remove(ringLive.size() - 1);
            free(p);
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
        p.pool = null;
        p.ring = false;
        spare.add(p);
    }

    /** Frees every slab and the staging, and forgets every placement. Context teardown; the next draw places anew. */
    public void releaseAll() {
        for (CgMeshPool pool : pools.values()) pool.delete();
        pools.clear();
        CgMeshPool.forgetVertexArrays();
        placements.clear();
        live.clear();
        retiring.clear();
        batch.clear();
        copyCount = 0;
        cpu.clear();
        if (staging != null) staging.delete();
        staging = null;
        if (ring != null) ring.delete();
        ring = null;
        ringLive.clear();
        if (commands != null) commands.delete();
        commands = null;
        joinedFrom = null;
        joinedCount = 0;
        if (sequence != 0) CgGL.glDeleteBuffers(sequence);
        sequence = sequenceLength = sequenceNeed = 0;
        slabBytes = 0;
        frame = Long.MIN_VALUE;
    }
}
