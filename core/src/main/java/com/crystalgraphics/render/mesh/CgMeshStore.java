package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.render.draw.CgDrawStateCache;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshChanges;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.CgGlNames;
import com.crystalgraphics.platform.gl.CgGL;
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
 * Where meshes' GPU copies live: a {@link CgMeshPool} per vertex format, one vertex buffer and one index buffer that
 * every mesh of the format shares, and a placement per mesh that draws -- its base vertex and first index there. The
 * executor places every mesh a frame draws and uploads what changed in the frame's prologue, before its first pass;
 * each draw passes its bases in the call, and its vertex stage pulls its vertices from the format's words
 * ({@code CgVertexWords}, at {@code CgBindingPoints.VERTICES}) by {@code gl_VertexID}, with no vertex input. So any two
 * meshes of a format share every binding, and a pipeline draws them alike one call each or joined. Every draw is by
 * indices: a submesh without them is drawn by a shared run of 0, 1, 2 ..., since GL gives an array draw's base vertex as
 * 0 and Vulkan as its first vertex.
 *
 * <pre>{@code
 * CgMeshStore store = CgMeshStore.get();
 * store.place(mesh);                        // for every mesh the frame draws
 * store.upload();                           // once, before the first raster pass
 * pipeline.bind();
 * store.draw(mesh, instances, first);       // in the pass; CG_INSTANCE_ID counts from first
 *
 * // An indirect draw: its command is written on the GPU from what range() answers, before the pass
 * store.range(mesh, submesh, first, count, range);
 * store.drawIndirect(mesh, submesh, args, offset);
 *
 * // Draws of meshes that join, as one call (CgCapabilities.multiDraw()): each with its own first instance
 * if (store.join(a, 1, -1, 0, -1, firstA) && store.join(b, 1, -1, 0, -1, firstB)) {
 *     store.drawJoined();
 * } else {
 *     store.dropJoined();                   // and draw() each
 * }
 *
 * // On a device: the words its sets bind, recorded before they are made, then the draw
 * CgBoundResources.storage(CgBindingPoints.VERTICES.ssbo(), store.vertexWords(mesh), 0, 0);
 * store.drawOn(pass, cache, mesh, instances, first, -1, 0, -1);
 * }</pre>
 *
 * <ul>
 *   <li>A draw of a mesh not placed and uploaded with the frame throws: an upload inside a pass breaks it on Vulkan.</li>
 *   <li>An edit takes a new range: one a frame in flight reads is never written. The old range is freed once the
 *       last frame that drew it has retired, as is a released mesh's, and a buffer a pool outgrew.</li>
 *   <li>The bytes are copied out of the mesh when it is placed, so an edit afterwards waits for the next frame.</li>
 *   <li>An upload is a copy from the frame ring into the pool, before the pass: on Vulkan on the transfer queue where
 *       the device has one ({@code CgGL.cgBeginTransfer}). Every pool write is such a copy, so the transfer queue only
 *       ever reads what it wrote itself.</li>
 *   <li>A {@link CgMesh.Usage#FRAME} mesh takes no range: each frame it is placed, its bytes are written straight into
 *       a page of the frame ring ({@link CgMeshRing}), and it draws from there, its words the page's. It holds nothing
 *       between frames, so it needs no release, and is forgotten after a frame it is not drawn in.</li>
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
    private static final int POOL_KB = CgTrace.name("mesh.pool-kb");
    private static final int POOL_GROWS = CgTrace.name("mesh.pool-grows");
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

    /**
     * Set in a draw's first instance, the rest of it naming the one record every instance reads: an indirect
     * {@code INSTANCES} draw's, whose instance count only the GPU knows ({@code cg_env.glsl}'s {@code CG_SHARED_RECORD}).
     */
    public static final int SHARED_RECORD = 1 << 30;

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
        /** Its format's pool: where its ranges are, or the format a ring placement is drawn as. */
        CgMeshPool pool;
        /** Whether it holds ranges in its pool; false for a ring placement and for a mesh with nothing to draw. */
        boolean stored;
        int vertexNode, indexNode;
        int baseVertex, firstIndex, vertexCount, indexCount, mode;
        int revision, releases;
        long lastUse;
        /** The last frame its bytes changed, how many frames in a row they have, and whether that was reported. */
        long editedFrame;
        int editStreak;
        boolean editReported;
        /** Placed in this upload batch, its bytes not yet copied: nothing may be copied out of it, or drawn from it, yet. */
        boolean pending;
        /** A FRAME mesh on the ring: its page and offsets are this frame's, and {@link #placedFrame} says which frame. */
        boolean ring;
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
    /** The pools by {@link CgMeshPool#index}, which a recorded copy names. */
    private final ArrayList<CgMeshPool> poolList = new ArrayList<>();
    private final IdentityHashMap<CgMesh, Placement> placements = new IdentityHashMap<>();
    private final ArrayList<Placement> live = new ArrayList<>();
    private final ArrayList<Placement> retiring = new ArrayList<>();
    private final ArrayList<Placement> spare = new ArrayList<>();
    private final ArrayList<Placement> batch = new ArrayList<>();

    /** A copy's kind: what it reads and which of its pool's buffers it writes. */
    private static final int KEEP_VERTICES = 0, KEEP_INDICES = 1, STAGE_VERTICES = 2, STAGE_INDICES = 3;
    /** Per copy: kind, pool index, source and destination byte offsets, size. */
    private long[] copies = new long[5 * 32];
    private int copyCount;
    /** Per pool growth this upload: the old buffer, the new one, the bytes the old one held. */
    private long[] grown = new long[3 * 4];
    private int grownCount;
    private final CgMeshPool.GrowthCopies growth = this::grown;
    /** Buffers a pool outgrew, and the frame each was last drawn from: deleted once that frame retires. */
    private int[] retiredBuffers = new int[8];
    private long[] retiredFrames = new long[8];
    private int retiredCount;
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
    /** The vertex array a {@code CgGL} draw binds: GL's core profile draws with one bound, and it holds no format. */
    private int emptyArray;

    private final CgMeshChanges changes = new CgMeshChanges();
    private final int[] nodes = new int[2], submesh = new int[4];
    private long frame = Long.MIN_VALUE;

    private CgMeshStore() {
    }

    // ── Placing ────────────────────────────────────────────────────────────────

    /** Makes {@code mesh} drawable this frame: placed, and its bytes staged if it is new or changed. */
    public void place(CgMesh mesh) {
        beginFrame();
        if (mesh.usage() == CgMesh.Usage.FRAME) {
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
            if (p == null || changes.all || p.pending || !p.stored || p.pool != next.pool) {
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
            p.stored = false;
            p.vertexNode = p.indexNode = -1;
            placements.put(mesh, p);
            ringLive.add(p);
        }
        p.placedFrame = frame;
        p.lastUse = frame;
        if (ring == null) ring = new CgMeshRing();
        p.pool = pool(mesh.format());
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

    private CgMeshPool pool(CgVertexFormat format) {
        CgMeshPool pool = pools.get(format);
        if (pool == null) {
            pool = new CgMeshPool(format, poolList.size());
            pools.put(format, pool);
            poolList.add(pool);
        }
        return pool;
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
        p.pool = pool(mesh.format());
        p.stored = p.vertexCount > 0;
        p.vertexNode = p.indexNode = -1;
        p.baseVertex = p.firstIndex = 0;
        describe(p, mesh);
        if (p.stored) {
            p.pool.place(p.vertexCount, p.indexCount, nodes);
            p.vertexNode = nodes[0];
            p.indexNode = nodes[1];
            p.baseVertex = p.vertexNode >= 0 ? p.pool.vertices.offset(p.vertexNode) : 0;
            p.firstIndex = p.indexNode >= 0 ? p.pool.indices.offset(p.indexNode) : 0;
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
        sequenceNeed = Math.max(sequenceNeed, longest);
    }

    /** Copies what {@code from} holds into {@code to} on the GPU: the part of the mesh that did not change. One pool. */
    private void keep(Placement from, Placement to) {
        int stride = to.mesh.format().getStride();
        int vertices = Math.min(from.vertexCount, to.vertexCount), indices = Math.min(from.indexCount, to.indexCount);
        if (vertices > 0 && stride > 0) {
            copy(KEEP_VERTICES, to.pool, (long) from.baseVertex * stride, (long) to.baseVertex * stride, (long) vertices * stride);
        }
        if (indices > 0) {
            copy(KEEP_INDICES, to.pool, (long) from.firstIndex * 4, (long) to.firstIndex * 4, (long) indices * 4);
        }
    }

    /** Copies vertices and indices out of the mesh into the CPU staging, and records their uploads. */
    private void stage(Placement p, int vertexFrom, int vertexTo, int indexFrom, int indexTo) {
        if (!p.stored) return;
        int stride = p.mesh.format().getStride();
        if (vertexTo > vertexFrom && stride > 0) {
            int bytes = (vertexTo - vertexFrom) * stride;
            int at = reserve(bytes);
            p.mesh.readVertices(vertexFrom, vertexTo - vertexFrom, cpu);
            copy(STAGE_VERTICES, p.pool, at, (long) (p.baseVertex + vertexFrom) * stride, bytes);
        }
        if (indexTo > indexFrom) {
            int bytes = (indexTo - indexFrom) * 4;
            int at = reserve(bytes);
            p.mesh.readIndices(indexFrom, indexTo - indexFrom, cpu);
            copy(STAGE_INDICES, p.pool, at, (long) (p.firstIndex + indexFrom) * 4, bytes);
        }
    }

    private int reserve(int bytes) {
        if (cpu.remaining() < bytes) {
            ByteBuffer grownCpu = ByteBuffer.allocate(Math.max(cpu.capacity() * 2, cpu.position() + bytes))
                    .order(ByteOrder.nativeOrder());
            cpu.flip();
            grownCpu.put(cpu);
            cpu = grownCpu;
        }
        return cpu.position();
    }

    private void copy(int kind, CgMeshPool pool, long fromOffset, long toOffset, long size) {
        if (copyCount * 5 == copies.length) copies = Arrays.copyOf(copies, copies.length * 2);
        int at = copyCount++ * 5;
        copies[at] = kind;
        copies[at + 1] = pool.index;
        copies[at + 2] = fromOffset;
        copies[at + 3] = toOffset;
        copies[at + 4] = size;
    }

    private void grown(int from, int to, long bytes) {
        if (grownCount * 3 == grown.length) grown = Arrays.copyOf(grown, grown.length * 2);
        int at = grownCount++ * 3;
        grown[at] = from;
        grown[at + 1] = to;
        grown[at + 2] = bytes;
    }

    // ── Uploading ──────────────────────────────────────────────────────────────

    /**
     * Copies everything placed since the last call into its pool: each pool grown first where a placement passed its
     * buffers' end, then what changed meshes kept from their old ranges, then the staged bytes through the frame ring.
     * In the frame's prologue, before its first raster pass.
     */
    public void upload() {
        if (sequenceNeed > sequenceLength) growSequence();
        if (ring != null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "mesh.commitRing")) {
                ring.commit();
            }
        }
        for (int i = 0; i < poolList.size(); i++) {
            int[] old = poolList.get(i).grow(growth);
            retireBuffer(old[0]);
            retireBuffer(old[1]);
        }
        if (copyCount == 0 && grownCount == 0) {
            endBatch();
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
        // Growth first, then kept content, then staged: each a bracket of its own, since each reads what the one before
        // wrote. Every range written is one no frame in flight reads, and every range read only these copies wrote, so
        // they may run on a transfer queue.
        if (grownCount > 0) {
            CgGL.cgBeginTransfer();
            try {
                for (int g = 0; g < grownCount; g++) {
                    copyBuffer((int) grown[g * 3], (int) grown[g * 3 + 1], 0, 0, grown[g * 3 + 2]);
                }
            } finally {
                CgGL.cgEndTransfer();
            }
            CgTrace.add(CgChannels.GL, POOL_GROWS, grownCount);
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean open = false;
            try {
                for (int c = 0; c < copyCount; c++) {
                    int at = c * 5, kind = (int) copies[at];
                    boolean fromStaging = kind == STAGE_VERTICES || kind == STAGE_INDICES;
                    if (fromStaging != (pass == 1)) continue;
                    if (!open) {
                        CgGL.cgBeginTransfer();
                        open = true;
                    }
                    CgMeshPool pool = poolList.get((int) copies[at + 1]);
                    int to = kind == KEEP_VERTICES || kind == STAGE_VERTICES ? pool.vertexBuffer : pool.indexBuffer;
                    if (fromStaging) copyBuffer(stagingBuffer, to, stagingOffset + copies[at + 2], copies[at + 3], copies[at + 4]);
                    else copyBuffer(to, to, copies[at + 2], copies[at + 3], copies[at + 4]);
                }
            } finally {
                if (open) CgGL.cgEndTransfer();
            }
        }
        CgTrace.add(CgChannels.GL, UPLOADS, copyCount);
        CgTrace.add(CgChannels.GL, UPLOAD_BYTES, staged);
        copyCount = 0;
        grownCount = 0;
        cpu.clear();
        endBatch();
    }

    private static void copyBuffer(int from, int to, long fromOffset, long toOffset, long size) {
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, to);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, fromOffset, toOffset, size);
    }

    /** The batch's placements are uploaded: drawable, and their ranges a source for the next edit's kept copy. */
    private void endBatch() {
        for (int i = 0; i < batch.size(); i++) batch.get(i).pending = false;
        batch.clear();
    }

    /** Deletes {@code buffer} once the frames that may still draw from it have retired. */
    private void retireBuffer(int buffer) {
        if (buffer == 0) return;
        if (retiredCount == retiredBuffers.length) {
            retiredBuffers = Arrays.copyOf(retiredBuffers, retiredCount * 2);
            retiredFrames = Arrays.copyOf(retiredFrames, retiredCount * 2);
        }
        retiredBuffers[retiredCount] = buffer;
        retiredFrames[retiredCount++] = frame;
    }

    private void growSequence() {
        int length = Math.max(sequenceNeed, Math.max(1024, sequenceLength * 2));
        ByteBuffer indices = CgBufferUtils.createByteBuffer(length * 4);
        for (int i = 0; i < length; i++) indices.putInt(i);
        indices.flip();
        if (sequence == 0) sequence = CgGlNames.buffer();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, sequence);   // not the element binding, which a vertex array owns
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, indices, CgGL.GL_STATIC_DRAW);
        sequenceLength = length;
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    /**
     * Draws {@code mesh}, every submesh, {@code instances} times from instance {@code firstInstance} (or
     * {@link #SHARED_RECORD} with a record), with a pipeline bound. Leaves the store's vertex array bound; the caller
     * binds 0 when its draws are done.
     */
    public void draw(CgMesh mesh, int instances, int firstInstance) {
        draw(mesh, instances, firstInstance, -1, 0, -1);
    }

    /**
     * As {@link #draw(CgMesh, int, int)}, a range of it: submesh {@code submesh}'s indices from {@code first},
     * {@code count} of them (-1 to its end), or its vertices for a submesh with no indices; {@code submesh} -1 draws
     * every submesh whole. The base vertex, and so {@code CG_VERTEX_ID}, is the submesh's whatever the range.
     */
    public void draw(CgMesh mesh, int instances, int firstInstance, int submesh, int first, int count) {
        Placement p = placed(mesh);
        if (p == null) return;
        int from = submesh < 0 ? 0 : submesh, to = submesh < 0 ? p.submeshCount : Math.min(submesh + 1, p.submeshCount);
        int elements = 0;
        for (int s = from; s < to; s++) {
            int firstIndex = p.submeshes[s * 4], indexCount = p.submeshes[s * 4 + 1];
            int firstVertex = p.submeshes[s * 4 + 2], vertexCount = p.submeshes[s * 4 + 3];
            int base = p.baseVertex + firstVertex;
            boolean indexed = indexCount > 0;
            int total = indexed ? indexCount : vertexCount;
            int start = submesh < 0 ? 0 : Math.min(first, total);
            int n = submesh < 0 || count < 0 ? total - start : Math.min(count, total - start);
            if (n <= 0) continue;
            drawing += (long) n * instances;
            calls++;
            CgTrace.add(CgChannels.GL, DRAWN, (long) n * instances);
            int want = indexed ? 1 : 2;
            if (elements != want) {
                bind(p, indexed ? -1 : sequence);
                elements = want;
            }
            long offset = (long) (indexed ? p.firstIndex + firstIndex + start : start) * 4;
            CgGL.glDrawElementsInstancedBaseVertexBaseInstance(p.mode, n, CgGL.GL_UNSIGNED_INT, offset, instances, base,
                    firstInstance);
        }
    }

    /**
     * What an indirect draw of a range of {@code mesh} composes its command from, into {@code out}: where the range
     * starts in the indices it is drawn by (the pool's, or the run of 0, 1, 2 ... for a submesh without), how many it
     * holds, its base vertex, and 1. A range as {@link #draw}'s, of one submesh ({@code submesh} -1 is the first). False,
     * writing nothing, when the mesh has nothing to draw.
     */
    public boolean range(CgMesh mesh, int submesh, int first, int count, int[] out) {
        Placement p = placed(mesh);
        if (p == null || p.submeshCount == 0) return false;
        int s = Math.min(Math.max(submesh, 0), p.submeshCount - 1);
        int firstIndex = p.submeshes[s * 4], indexCount = p.submeshes[s * 4 + 1];
        int firstVertex = p.submeshes[s * 4 + 2], vertexCount = p.submeshes[s * 4 + 3];
        int total = indexCount > 0 ? indexCount : vertexCount;
        int start = Math.min(first, total);
        out[0] = indexCount > 0 ? p.firstIndex + firstIndex + start : start;
        out[1] = count < 0 ? total - start : Math.min(count, total - start);
        out[2] = p.baseVertex + firstVertex;
        out[3] = 1;
        return true;
    }

    /**
     * Draws a range of {@code mesh} by the command at byte {@code offset} in buffer {@code args}, written from what
     * {@link #range} answered for it. Leaves the store's vertex array bound, as {@link #draw} does.
     */
    public void drawIndirect(CgMesh mesh, int submesh, int args, long offset) {
        Placement p = placed(mesh);
        if (p == null || p.submeshCount == 0) return;
        int s = Math.min(Math.max(submesh, 0), p.submeshCount - 1);
        bind(p, p.submeshes[s * 4 + 1] > 0 ? -1 : sequence);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, args);
        CgGL.glDrawElementsIndirect(p.mode, CgGL.GL_UNSIGNED_INT, offset);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        calls++;
        CgTrace.add(CgChannels.GL, DRAWN_INDIRECT, 1);
    }

    /**
     * Adds a draw to the call {@link #drawJoined} makes, if its mesh joins those added since that call last ran: of the
     * same format (from the same ring page, for a FRAME mesh), in one topology, drawn by indices or without them as they
     * are. False, adding nothing, when it does not. A range and first instance as
     * {@link #draw(CgMesh, int, int, int, int, int)} takes.
     *
     * <pre>{@code
     * int n = 0;
     * while (n < meshes.length && store.join(meshes[n], 1, -1, 0, -1, firsts[n])) n++;
     * if (n > 1) {
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
     * written to the frame ring, with a pipeline bound. Leaves the store's vertex array bound, as {@link #draw} does.
     */
    public void drawJoined() {
        Placement p = joinedFrom;
        int n = joinedCount;
        dropJoined();
        if (n == 0) return;
        bind(p, p.joinsAs == BY_SEQUENCE ? sequence : -1);
        int offset = writeJoined(n);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, commands.getGlBufferId());
        CgGL.glMultiDrawElementsIndirect(p.mode, CgGL.GL_UNSIGNED_INT, offset, n, COMMAND_WORDS * 4);
        CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        calls++;
        CgTrace.add(CgChannels.GL, MULTI_DRAWS, 1);
        CgTrace.add(CgChannels.GL, MULTI_COMMANDS, n);
    }

    /** The {@code n} joined commands written to the frame ring, counted; answers where they start. */
    private int writeJoined(int n) {
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
        return commands.commit(bytes);
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
     * Draws {@code n} commands {@code stride} bytes apart from byte {@code offset} of buffer {@code args} as one
     * {@code glMultiDrawElementsIndirect}, each written from {@link #range} for a mesh that {@link #joins}
     * {@code first}, with a pipeline bound. Leaves the store's vertex array bound, as {@link #draw} does.
     *
     * <pre>{@code
     * store.range(mesh, -1, 0, -1, range);             // per command, written into args on the GPU
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

    // ── Drawing on a device ────────────────────────────────────────────────────

    /** {@code mesh}'s topology as its device pipeline describes it. */
    public static CgGraphicsPipelineDesc.Topology topology(CgMesh mesh) {
        return switch (mesh.topology()) {
            case TRIANGLES -> CgGraphicsPipelineDesc.Topology.TRIANGLES;
            case TRIANGLE_STRIP -> CgGraphicsPipelineDesc.Topology.TRIANGLE_STRIP;
            case LINES -> CgGraphicsPipelineDesc.Topology.LINES;
            case LINE_STRIP -> CgGraphicsPipelineDesc.Topology.LINE_STRIP;
            case POINTS -> CgGraphicsPipelineDesc.Topology.POINTS;
        };
    }

    /**
     * The buffer {@code mesh}'s vertex stage pulls from, as a {@code CgGL} name: its format's words, or the ring page a
     * FRAME mesh was written into. 0 for a mesh of a format with no attributes, or with nothing to draw. Whatever draws
     * it on a device binds this at {@code CgBindingPoints.VERTICES} before its sets are made.
     */
    public int vertexWords(CgMesh mesh) {
        Placement p = placed(mesh);
        return p == null ? 0 : wordsOf(p);
    }

    /**
     * {@link #draw(CgMesh, int, int, int, int, int)} on a device's render pass, its indices set through {@code cache}:
     * with the pass's pipeline for this mesh's format and topology set, and its {@link #vertexWords} bound.
     */
    public void drawOn(CgRenderPass pass, CgDrawStateCache cache, CgMesh mesh, int instances, int firstInstance, int submesh,
                       int first, int count) {
        Placement p = placed(mesh);
        if (p == null) return;
        int from = submesh < 0 ? 0 : submesh, to = submesh < 0 ? p.submeshCount : Math.min(submesh + 1, p.submeshCount);
        for (int s = from; s < to; s++) {
            int firstIndex = p.submeshes[s * 4], indexCount = p.submeshes[s * 4 + 1];
            int firstVertex = p.submeshes[s * 4 + 2], vertexCount = p.submeshes[s * 4 + 3];
            boolean indexed = indexCount > 0;
            int total = indexed ? indexCount : vertexCount;
            int start = submesh < 0 ? 0 : Math.min(first, total);
            int n = submesh < 0 || count < 0 ? total - start : Math.min(count, total - start);
            if (n <= 0) continue;
            drawing += (long) n * instances;
            calls++;
            CgTrace.add(CgChannels.GL, DRAWN, (long) n * instances);
            indicesOn(pass, cache, p, indexed);
            pass.drawIndexed(n, instances, indexed ? p.firstIndex + firstIndex + start : start, p.baseVertex + firstVertex,
                    firstInstance);
        }
    }

    /** {@link #drawIndirect} on a device's render pass: the command at byte {@code offset} of {@code args}. */
    public void drawIndirectOn(CgRenderPass pass, CgDrawStateCache cache, CgMesh mesh, int submesh, CgGpuBuffer args,
                               long offset) {
        Placement p = placed(mesh);
        if (p == null || p.submeshCount == 0) return;
        int s = Math.min(Math.max(submesh, 0), p.submeshCount - 1);
        indicesOn(pass, cache, p, p.submeshes[s * 4 + 1] > 0);
        pass.drawIndexedIndirect(args, offset, 1, COMMAND_WORDS * 4);
        calls++;
        CgTrace.add(CgChannels.GL, DRAWN_INDIRECT, 1);
    }

    /** {@link #drawJoined} on a device's render pass: the joined commands from the frame ring, one indirect call. */
    public void drawJoinedOn(CgRenderPass pass, CgDrawStateCache cache) {
        Placement p = joinedFrom;
        int n = joinedCount;
        dropJoined();
        if (n == 0) return;
        indicesOn(pass, cache, p, p.joinsAs != BY_SEQUENCE);
        int offset = writeJoined(n);
        CgGLImports imports = CgGLImports.get();
        int name = commands.getGlBufferId();
        pass.drawIndexedIndirect(imports.buffer(name), imports.bufferOffset(name) + offset, n, COMMAND_WORDS * 4);
        calls++;
        CgTrace.add(CgChannels.GL, MULTI_DRAWS, 1);
        CgTrace.add(CgChannels.GL, MULTI_COMMANDS, n);
    }

    /** {@link #drawIndirectJoined} on a device's render pass. */
    public void drawIndirectJoinedOn(CgRenderPass pass, CgDrawStateCache cache, CgMesh first, CgGpuBuffer args, long offset,
                                     int n, int stride) {
        Placement p = placed(first);
        if (p == null || p.joinsAs == 0) return;
        indicesOn(pass, cache, p, p.joinsAs != BY_SEQUENCE);
        pass.drawIndexedIndirect(args, offset, n, stride);
        calls++;
        CgTrace.add(CgChannels.GL, DRAWN_INDIRECT, n);
        CgTrace.add(CgChannels.GL, MULTI_DRAWS, 1);
        CgTrace.add(CgChannels.GL, MULTI_COMMANDS, n);
    }

    /** Sets {@code p}'s indices (the run of 0, 1, 2 ... where it draws without) on {@code pass}. */
    private void indicesOn(CgRenderPass pass, CgDrawStateCache cache, Placement p, boolean indexed) {
        int indexName = indexed ? indicesOf(p) : sequence;
        CgGLImports imports = CgGLImports.get();
        cache.indexBuffer(pass, imports.buffer(indexName), imports.bufferOffset(indexName));
    }

    /** The words {@code p} is pulled from: its pool's vertex buffer, or its ring page; 0 for a format with none. */
    private int wordsOf(Placement p) {
        if (p.mesh.format().getStride() == 0) return 0;
        if (p.ring) return p.ringPage >= 0 ? ring.page(p.ringPage).buffer.getGlBufferId() : 0;
        return p.pool.vertexBuffer;
    }

    /** The indices {@code p} is drawn by: its pool's index buffer, or its ring page. */
    private int indicesOf(Placement p) {
        if (p.ring) return p.ringPage >= 0 ? ring.page(p.ringPage).buffer.getGlBufferId() : 0;
        return p.pool.indexBuffer;
    }

    /** Whether {@code p} and {@code q} share the words, the indices and the topology a multi-draw needs. */
    private static boolean joins(Placement p, Placement q) {
        return p.joinsAs == q.joinsAs && p.mode == q.mode && p.ring == q.ring && p.pool == q.pool
                && (!p.ring || p.ringPage == q.ringPage);
    }

    /**
     * {@code mesh}'s placement this frame; null with nothing to draw. Throws for a mesh not placed and uploaded with the
     * frame, which would be an upload inside a pass.
     */
    @Nullable
    private Placement placed(CgMesh mesh) {
        beginFrame();
        Placement p = placements.get(mesh);
        if (p == null || p.releases != mesh.releases() || (p.ring && p.placedFrame != frame) || p.pending) {
            throw new IllegalStateException(mesh + " is drawn unplaced: place every mesh a frame draws, and upload, in "
                    + "its prologue (CgExecutor places a frame's before its first pass)");
        }
        if (!p.stored && !p.ring) return null;
        p.lastUse = frame;
        return p;
    }

    /**
     * Binds the store's vertex array, {@code p}'s indices or {@code elements} where that is not -1, and the words its
     * vertex stage pulls from.
     */
    private void bind(Placement p, int elements) {
        if (emptyArray == 0) emptyArray = CgGlNames.vertexArray();
        CgGL.glBindVertexArray(emptyArray);
        // LWJGL 2 checks an indexed draw's offset against the element binding it saw bound, never the vertex array's.
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, elements < 0 ? indicesOf(p) : elements);
        int words = wordsOf(p);
        if (words != 0) {
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, CgBindingPoints.VERTICES.ssbo(), words);
            CgBoundResources.storage(CgBindingPoints.VERTICES.ssbo(), words, 0, 0);
        }
    }

    // ── Frames, releases, teardown ─────────────────────────────────────────────

    /** At a frame's first call: frees the ranges and buffers whose last frame has retired, and retires released meshes. */
    private void beginFrame() {
        long now = CgFrameRing.frame();
        if (now == frame) return;
        frame = now;
        drawn = drawing;
        drawing = 0;
        long held = 0;
        for (int i = 0; i < poolList.size(); i++) held += poolList.get(i).bytes();
        CgTrace.counter(CgChannels.GL, POOL_KB, held >> 10);
        long safe = now - CgFrameRing.FRAMES;
        for (int i = retiring.size() - 1; i >= 0; i--) {
            Placement p = retiring.get(i);
            if (p.lastUse > safe) continue;
            CgFrameRing.awaitRetired(p.lastUse);
            free(p);
            retiring.set(i, retiring.get(retiring.size() - 1));
            retiring.remove(retiring.size() - 1);
        }
        for (int i = retiredCount - 1; i >= 0; i--) {
            if (retiredFrames[i] > safe) continue;
            CgFrameRing.awaitRetired(retiredFrames[i]);
            CgGL.glDeleteBuffers(retiredBuffers[i]);
            retiredCount--;
            retiredBuffers[i] = retiredBuffers[retiredCount];
            retiredFrames[i] = retiredFrames[retiredCount];
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
        if (p.stored) {
            if (p.vertexNode >= 0) p.pool.vertices.free(p.vertexNode);
            if (p.indexNode >= 0) p.pool.indices.free(p.indexNode);
        }
        p.mesh = null;
        p.pool = null;
        p.stored = false;
        p.ring = false;
        spare.add(p);
    }

    /** Frees every pool and the staging, and forgets every placement. Context teardown; the next draw places anew. */
    public void releaseAll() {
        for (CgMeshPool pool : poolList) pool.delete();
        pools.clear();
        poolList.clear();
        for (int i = 0; i < retiredCount; i++) CgGL.glDeleteBuffers(retiredBuffers[i]);
        retiredCount = 0;
        grownCount = 0;
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
        if (emptyArray != 0) CgGL.glDeleteVertexArrays(emptyArray);
        emptyArray = 0;
        frame = Long.MIN_VALUE;
    }
}
