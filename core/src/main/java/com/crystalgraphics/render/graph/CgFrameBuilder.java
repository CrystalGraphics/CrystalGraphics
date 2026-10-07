package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.property.CgPropertyValues;
import com.crystalgraphics.render.property.CgSpatialTree;
import com.crystalgraphics.render.draw.CgBatcher;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Turns a {@link CgFrameGraph} into a {@link CgFrame}: orders the passes by what they read and write, culls those
 * nobody reads, plans when each transient texture and buffer lives, lists what each pass accesses for the barriers
 * the executor derives, batches every raster pass, and packs every instance of a kind into one array and every
 * binding snapshot into one table. No GL, so it runs wherever the frame is built — a
 * builder thread, or inline on the render thread.
 *
 * <pre>{@code
 * CgFrameBuilder builder = new CgFrameBuilder();     // one per building thread; it keeps its scratch
 * CgFrame frame = builder.build(graph);
 * renderThread.post(() -> { CgExecutor.execute(frame); builder.recycle(frame); });
 * }</pre>
 *
 * <ul>
 *   <li>A steady frame allocates nothing: the scratch is kept, and a recycled frame's storage is reused.</li>
 *   <li>Not thread-safe: one thread builds with one builder. {@link #recycle} may be called from another.</li>
 *   <li>A cycle among the passes — two passes each reading what the other writes — is a recording error and
 *       throws, naming them.</li>
 *   <li>An {@code async()} compute pass runs as early as what it reads allows, and whatever reads its results as late
 *       as the graph allows, so the passes recorded around it overlap it. Otherwise creation order breaks ties.</li>
 * </ul>
 */
public final class CgFrameBuilder {

    private static final int KINDS = CgInstanceKind.values().length;
    private static final int PASSES = CgTrace.name("graph.passes");
    private static final int BATCHES = CgTrace.name("graph.batches");
    private static final int DRAWS = CgTrace.name("graph.draws");
    private static final int SNAPSHOTS = CgTrace.name("graph.snapshots");
    private static final int INSTANCES = CgTrace.name("graph.instances");
    /** A heap key is a pass's class shifted above its index. */
    private static final int CLASS_SHIFT = 28, INDEX = (1 << CLASS_SHIFT) - 1;

    private final CgBatcher batcher = new CgBatcher();
    private final ConcurrentLinkedQueue<CgFrame> recycled = new ConcurrentLinkedQueue<>();

    // Scratch, kept between builds.
    private CgPass[] passes = new CgPass[16];
    private int[] base = new int[4];
    private final IdentityHashMap<CgGraphResource, Integer> slots = new IdentityHashMap<>();
    private final List<CgGraphResource> resources = new ArrayList<>();
    private IntList[] out = new IntList[0], in = new IntList[0], uses = new IntList[0];
    /** Per pass, its events: the recording's index and the event's, in parallel. */
    private IntList[] eventRecording = new IntList[0], eventIndex = new IntList[0];
    /** Per pass, whether it writes a resource that outlives the frame: what no cull may remove. */
    private boolean[] writesOutliving = new boolean[16];
    private final List<IntList> readers = new ArrayList<>();
    private final IntList lastWriter = new IntList();
    private boolean[] needed = new boolean[16];
    private final IntList stack = new IntList();
    private int[] indegree = new int[16];
    private int[] order = new int[16];
    /** Per pass, its heap key ({@link #rank}); and whether it feeds an async pass, or follows one. */
    private int[] rank = new int[16];
    private boolean[] feeds = new boolean[16], follows = new boolean[16];
    private IntHeap ready = new IntHeap(16);
    private int[] firstUse = new int[16], lastUse = new int[16];
    private final IdentityHashMap<CgBindingTable, int[]> interned = new IdentityHashMap<>();
    private final List<int[]> internMaps = new ArrayList<>();
    private int internMapsUsed;
    private CgDrawChunk[] refChunk = new CgDrawChunk[256];
    private int[] refDraw = new int[256];
    /** The values each recording of the graph being built is drawn with. */
    private final IdentityHashMap<CgRecording, CgPropertyValues> valuesOf = new IdentityHashMap<>();
    private final float[] domainBounds = new float[4];
    private final float[] batchBounds = new float[4];
    /** Scratch rects for placing copies, in GL pixels: x0, y0, x1, y1. */
    private final int[] rect = new int[4], lastCopy = new int[4], dirty = new int[4];

    /** Builds the frame. The graph's recordings are only read. */
    public CgFrame build(CgFrameGraph graph) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.build")) {
            List<CgRecording> recordings = graph.recordings();
            valuesOf.clear();
            for (int r = 0; r < recordings.size(); r++) valuesOf.put(recordings.get(r), graph.values(r));
            int total = flatten(recordings);
            edges(recordings, total);
            cull(total);
            int steps = topological(total);

            CgFrame frame = recycled.poll();
            if (frame == null) frame = new CgFrame();
            frame.steps(steps);
            for (int s = 0; s < steps; s++) {
                CgPass pass = passes[order[s]];
                frame.steps[s] = pass;
                frame.outlives[s] = writesOutliving[order[s]];
                frame.kernels |= pass instanceof CgComputePass || pass instanceof CgPass.Fill
                        || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy;
            }
            lifetimes(frame, steps);
            accesses(frame, recordings, steps);

            interned.clear();
            internMapsUsed = 0;
            for (int s = 0; s < steps; s++) {
                if (frame.steps[s] instanceof CgRasterPass raster) {
                    CgFrame.Raster packed = frame.raster(s);
                    pack(raster, frame, packed);
                    frame.batches += packed.count;
                    frame.draws += packed.draws;
                } else if (frame.steps[s] instanceof CgComputePass compute) {
                    pack(compute, frame, frame.compute(s));
                }
            }
            Arrays.fill(passes, 0, total, null);
            slots.clear();
            resources.clear();

            CgTrace.add(CgChannels.GL, PASSES, steps);
            CgTrace.add(CgChannels.GL, BATCHES, frame.batches);
            CgTrace.add(CgChannels.GL, DRAWS, frame.draws);
            CgTrace.add(CgChannels.GL, SNAPSHOTS, frame.bindings.size());
            long instances = 0;
            for (int k = 0; k < KINDS; k++) instances += frame.instanceFloats[k] / CgInstanceKind.of(k).floats();
            CgTrace.add(CgChannels.GL, INSTANCES, instances);
            return frame;
        }
    }

    /** Takes back a frame once it has executed; it must not be used again. Any thread. */
    public void recycle(CgFrame frame) {
        frame.clear();
        recycled.add(frame);
    }

    private int flatten(List<CgRecording> recordings) {
        int total = 0;
        for (CgRecording r : recordings) total += r.passCount();
        if (passes.length < total) passes = new CgPass[Math.max(total, passes.length * 2)];
        if (base.length < recordings.size()) base = new int[Math.max(recordings.size(), base.length * 2)];
        for (int r = 0, at = 0; r < recordings.size(); r++) {
            base[r] = at;
            CgRecording rec = recordings.get(r);
            for (int p = 0; p < rec.passCount(); p++) passes[at++] = rec.pass(p);
        }
        if (out.length < total) {
            out = grow(out, total);
            in = grow(in, total);
            uses = grow(uses, total);
            eventRecording = grow(eventRecording, total);
            eventIndex = grow(eventIndex, total);
        }
        if (writesOutliving.length < total) writesOutliving = new boolean[Math.max(total, writesOutliving.length * 2)];
        for (int p = 0; p < total; p++) {
            out[p].clear();
            in[p].clear();
            uses[p].clear();
            eventRecording[p].clear();
            eventIndex[p].clear();
            writesOutliving[p] = false;
        }
        return total;
    }

    /** A read after the write it sees; a write after the reads and the write before it. */
    private void edges(List<CgRecording> recordings, int total) {
        lastWriter.clear();
        for (int r = 0; r < recordings.size(); r++) {
            CgRecording rec = recordings.get(r);
            for (int e = 0; e < rec.eventCount(); e++) {
                int p = base[r] + rec.eventPass(e);
                CgGraphResource resource = rec.eventResource(e);
                eventRecording[p].add(r);
                eventIndex[p].add(e);
                Integer slot = slots.get(resource);
                if (slot == null) {
                    slot = resources.size();
                    slots.put(resource, slot);
                    resources.add(resource);
                    if (readers.size() <= slot) readers.add(new IntList());
                    readers.get(slot).clear();
                    lastWriter.add(-1);
                }
                int t = slot;
                uses[p].add(t);
                int writer = lastWriter.get(t);
                if (writer >= 0 && writer != p) edge(writer, p);
                if (rec.eventType(e) == CgRecording.READ) {
                    readers.get(t).add(p);
                } else {
                    if (resource.outlivesFrame()) writesOutliving[p] = true;
                    IntList since = readers.get(t);
                    for (int i = 0; i < since.size; i++) if (since.get(i) != p) edge(since.get(i), p);
                    since.clear();
                    lastWriter.set(t, p);
                }
            }
        }
    }

    /** Keeps what has an effect beyond the frame, and everything it depends on. */
    private void cull(int total) {
        if (needed.length < total) needed = new boolean[Math.max(total, needed.length * 2)];
        Arrays.fill(needed, 0, total, false);
        stack.clear();
        for (int p = 0; p < total; p++) {
            if (passes[p].sideEffect() || writesOutliving[p]) {
                needed[p] = true;
                stack.add(p);
            }
        }
        while (stack.size > 0) {
            int p = stack.pop();
            for (int i = 0; i < in[p].size; i++) {
                int q = in[p].get(i);
                if (!needed[q]) {
                    needed[q] = true;
                    stack.add(q);
                }
            }
        }
    }

    /**
     * Kahn's algorithm over the passes still needed. Among the passes ready together, an async pass and what it
     * depends on go first and what depends on it last, so the work between overlaps it; then creation order.
     */
    private int topological(int total) {
        if (indegree.length < total) {
            indegree = new int[Math.max(total, indegree.length * 2)];
            order = new int[indegree.length];
            rank = new int[indegree.length];
            ready = new IntHeap(indegree.length);
        }
        Arrays.fill(indegree, 0, total, 0);
        int count = 0;
        for (int p = 0; p < total; p++) {
            if (!needed[p]) continue;
            count++;
            for (int i = 0; i < out[p].size; i++) if (needed[out[p].get(i)]) indegree[out[p].get(i)]++;
        }
        rank(total);
        ready.size = 0;
        for (int p = 0; p < total; p++) if (needed[p] && indegree[p] == 0) ready.push(rank[p]);
        int at = 0;
        while (ready.size > 0) {
            int p = ready.pop() & INDEX;
            order[at++] = p;
            for (int i = 0; i < out[p].size; i++) {
                int q = out[p].get(i);
                if (needed[q] && --indegree[q] == 0) ready.push(rank[q]);
            }
        }
        if (at < count) {
            StringBuilder stuck = new StringBuilder();
            for (int p = 0; p < total; p++) if (needed[p] && indegree[p] > 0) stuck.append(' ').append(passes[p]);
            throw new IllegalStateException("passes read what each other write:" + stuck);
        }
        return count;
    }

    /**
     * Each needed pass's heap key: its class above its creation index. 0 for an async pass and every pass it depends
     * on, 2 for every pass depending on one and on none, 1 for the rest; all 1 when no pass is async.
     */
    private void rank(int total) {
        if (feeds.length < total) {
            feeds = new boolean[Math.max(total, feeds.length * 2)];
            follows = new boolean[feeds.length];
        }
        Arrays.fill(feeds, 0, total, false);
        Arrays.fill(follows, 0, total, false);
        boolean any = false;
        for (int p = 0; p < total; p++) {
            if (needed[p] && passes[p] instanceof CgComputePass compute && (compute.isAsync() || CgExecutor.ASYNC_ALL)) {
                feeds[p] = true;
                any = true;
            }
        }
        if (any) {
            walk(feeds, follows, out, total);   // from the async passes alone, before the next walk widens feeds
            walk(feeds, feeds, in, total);
        }
        for (int p = 0; p < total; p++) rank[p] = (feeds[p] ? 0 : follows[p] ? 2 : 1) << CLASS_SHIFT | p;
    }

    /** Marks in {@code set} every needed pass reachable along {@code edges} from a pass in {@code from}. */
    private void walk(boolean[] from, boolean[] set, IntList[] edges, int total) {
        stack.clear();
        for (int p = 0; p < total; p++) if (from[p]) stack.add(p);
        while (stack.size > 0) {
            int p = stack.pop();
            for (int i = 0; i < edges[p].size; i++) {
                int q = edges[p].get(i);
                if (needed[q] && !set[q]) {
                    set[q] = true;
                    stack.add(q);
                }
            }
        }
    }

    /** When each transient lives, in the executed order. */
    private void lifetimes(CgFrame frame, int steps) {
        int n = resources.size();
        if (firstUse.length < n) {
            firstUse = new int[Math.max(n, firstUse.length * 2)];
            lastUse = new int[firstUse.length];
        }
        Arrays.fill(firstUse, 0, n, -1);
        for (int s = 0; s < steps; s++) {
            IntList used = uses[order[s]];
            for (int i = 0; i < used.size; i++) {
                int t = used.get(i);
                if (!resources.get(t).isTransient()) continue;
                if (firstUse[t] < 0) firstUse[t] = s;
                lastUse[t] = s;
            }
        }
        int count = 0;
        for (int t = 0; t < n; t++) if (firstUse[t] >= 0) count++;
        frame.lifetimes(count);
        for (int t = 0, i = 0; t < n; t++) {
            if (firstUse[t] < 0) continue;
            frame.transients.add(resources.get(t));
            frame.acquireAt[i] = firstUse[t];
            frame.releaseAfter[i] = lastUse[t];
            i++;
        }
    }

    /**
     * What each step accesses, every resource once with its access bits joined: what the executor derives barriers
     * from. A view — a history's previous version — is kept as named, since it is other storage.
     */
    private void accesses(CgFrame frame, List<CgRecording> recordings, int steps) {
        frame.accessFrom(steps);
        for (int s = 0; s < steps; s++) {
            int p = order[s];
            frame.accessFrom[s] = frame.accessCount;
            for (int i = 0; i < eventIndex[p].size; i++) {
                CgRecording rec = recordings.get(eventRecording[p].get(i));
                int e = eventIndex[p].get(i);
                frame.access(frame.accessFrom[s], rec.eventView(e), rec.eventAccess(e));
            }
        }
        frame.accessFrom[steps] = frame.accessCount;
    }

    private void pack(CgComputePass pass, CgFrame frame, CgFrame.Compute packed) {
        CgBindingTable table = pass.recording.bindings();
        int[] map = internMap(table);
        List<CgDispatch> dispatches = pass.dispatches();
        packed.size(dispatches.size());
        for (int d = 0; d < dispatches.size(); d++) {
            int local = dispatches.get(d).bindings;
            if (map[local] < 0) map[local] = frame.bindings.copy(table, local);
            packed.bindings[d] = map[local];
        }
    }

    private void pack(CgRasterPass pass, CgFrame frame, CgFrame.Raster packed) {
        batcher.reset(pass.order);
        boolean groups = CgTrace.isEnabled(CgChannels.GPU_GROUPS);   // only then may a group split a batch
        int refs = 0;
        List<CgDrawChunk> chunks = pass.chunkList();
        CgSpatialTree tree = pass.recording.spatial();
        for (int c = 0; c < chunks.size(); c++) {
            CgDrawChunk chunk = chunks.get(c);
            int scissor = pass.chunkScissor(c);
            // Draws reorder only among those whose relative position cannot change: the nearest movable node's.
            int domain = tree.domain(chunk.spatial());
            CgBindingTable table = chunk.bindings();
            int[] map = internMap(table);
            for (int d = 0; d < chunk.draws(); d++) {
                int local = chunk.binding(d);
                if (map[local] < 0) map[local] = frame.bindings.copy(table, local);
                if (refs == refChunk.length) {
                    refChunk = Arrays.copyOf(refChunk, refs * 2);
                    refDraw = Arrays.copyOf(refDraw, refs * 2);
                }
                refChunk[refs] = chunk;
                refDraw[refs] = d;
                tree.boundsInDomain(chunk.spatial(), chunk.x0(d), chunk.y0(d), chunk.x1(d), chunk.y1(d), domainBounds);
                batcher.add(chunk.pipeline(d), map[local], chunk.kind(d), chunk.mesh(d), chunk.rangeSubmesh(d),
                        chunk.rangeFirst(d), chunk.rangeCount(d),
                        chunk.indirectCount(d) != null || chunk.objects(d) != null || chunk.buffer(d) != null,
                        groups ? chunk.gpuGroup(d) : -1,
                        domain, scissor,
                        domainBounds[0], domainBounds[1], domainBounds[2], domainBounds[3], chunk.sortKey(d), refs);
                refs++;
            }
        }
        batcher.finish();

        int n = batcher.batches();
        packed.size(n);
        packed.draws = refs;
        for (int b = 0; b < n; b++) {
            CgInstanceKind k = batcher.batchKind(b);
            int floats = k.floats();
            int ki = k.ordinal();
            packed.pipeline[b] = batcher.batchPipeline(b);
            packed.binding[b] = batcher.batchBinding(b);
            packed.kind[b] = ki;
            if (!packed.tables && CgPipeline.byId(packed.pipeline[b]).readsTables()) packed.tables = true;
            packed.mesh[b] = (CgMesh) batcher.batchMesh(b);
            packed.submesh[b] = batcher.batchSubmesh(b);
            packed.rangeFirst[b] = batcher.batchRangeFirst(b);
            packed.rangeCount[b] = batcher.batchRangeCount(b);
            packed.scissor[b] = batcher.batchScissor(b);
            packed.group[b] = batcher.batchGroup(b);
            packed.first[b] = frame.instanceFloats[ki] / floats;
            for (int i = batcher.batchStart(b); i < batcher.batchEnd(b); i++) {
                CgDrawChunk chunk = refChunk[batcher.ref(i)];
                int d = refDraw[batcher.ref(i)];
                if (chunk.objects(d) != null) {
                    packed.objects[b] = chunk.objects(d);
                    packed.first[b] = chunk.first(d);
                } else {
                    int length = chunk.instances(d) * floats;
                    frame.reserve(ki, length);
                    System.arraycopy(chunk.data(k), chunk.first(d) * floats, frame.instances[ki], frame.instanceFloats[ki], length);
                    frame.instanceFloats[ki] += length;
                    packed.kinds |= 1 << ki;
                }
                packed.instances[b] += chunk.instances(d);
                if (chunk.buffer(d) != null) {
                    packed.buffers[b] = chunk.buffer(d);
                    packed.bufferAt[b] = chunk.bufferAt(d);
                }
                if (chunk.indirectCount(d) != null) {
                    packed.counts[b] = chunk.indirectCount(d);
                    packed.countOffsets[b] = chunk.indirectOffset(d);
                    packed.countModes[b] = chunk.indirectMode(d).ordinal() | chunk.indirectFactor(d) << 2;
                    packed.indirects++;
                }
            }
        }
        Arrays.fill(refChunk, 0, refs, null);
        placeCopies(pass, packed);
        frame.kernels |= packed.indirects > 0;   // an indirect draw's command is a kernel's
        packed.clips = frame.clipsOf(pass.recording.clips());
        packed.shapes = frame.shapesOf(pass.recording.shapes());
        packed.palette = frame.paletteOf(pass.recording, valuesOf.get(pass.recording));
        CgBindingTable constants = frame.bindings.begin()
                .block(CgBindingPoints.FRAME_DATA_UBO, pass.constants, 0, CgPassConstants.FLOATS);
        boolean lightmap = false;
        for (int i = 0; i < pass.textureCount(); i++) {
            constants.texture(pass.textureUnit(i), pass.texture(i));
            lightmap |= pass.textureUnit(i) == CgBindingPoints.LIGHTMAP_TEXTURE_UNIT;
        }
        // A pass with no world lights nothing: cg_Lightmap reads white.
        if (!lightmap && CgBindingPoints.LIGHTMAP_TEXTURE_UNIT >= 0 && CgFallbackTextures.WHITE_1x1 != null) {
            constants.texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, CgFallbackTextures.WHITE_1x1);
        }
        if (pass.sceneColorUnit() >= 0) constants.texture(pass.sceneColorUnit(), pass.targetCopy().color);
        if (pass.sceneDepthUnit() >= 0) constants.texture(pass.sceneDepthUnit(), pass.targetCopy().depth);
        if (pass.depthFrom() != null) {
            constants.texture(pass.depthFromUnit(), pass.depthFromCopy().depth);
            frame.readsCurrentDepth |= pass.depthFrom().kind() == CgGraphTexture.Kind.CURRENT;
        }
        if (pass.target == null || pass.target.kind() == CgGraphTexture.Kind.CURRENT) frame.rastersCurrent = true;
        else frame.rastersOther = true;
        packed.constants = constants.end();
    }

    /**
     * Where the pass copies its target for draws sampling it, and how much. Depth is copied whole, before a reader of
     * what a draw since the last copy wrote. Colour is copied before a reader whose rect, its bounds grown by its
     * shader's {@code SceneColorMargin}, the last copy does not hold or a draw since wrote into; the readers after it,
     * up to the next draw that writes colour without reading it, share the copy, which covers all their rects. A
     * reader's own colour writes leave what it reads clean, so readers in a row never see each other's colour; any
     * depth write, a reader's too, is seen by the next depth reader.
     */
    private void placeCopies(CgRasterPass pass, CgFrame.Raster packed) {
        int sampled = (pass.sceneColorUnit() >= 0 ? CgTargetCopy.COLOR : 0)
                | (pass.sceneDepthUnit() >= 0 ? CgTargetCopy.DEPTH : 0);
        if (sampled == 0) return;
        float width = CgPassConstants.width(pass.constants), height = CgPassConstants.height(pass.constants);
        // A pass into part of a layer is not in its target's pixels: its copies are whole.
        boolean regions = width > 0f && height > 0f && pass.viewOwner() == 0 && pass.viewX() == 0f && pass.viewY() == 0f;
        int depthUnseen = sampled & CgTargetCopy.DEPTH;
        int run = -1;   // the batch holding the copy the current readers share
        boolean copied = false;
        clear(dirty);
        for (int b = 0; b < packed.count; b++) {
            CgPipeline pipeline = CgPipeline.byId(packed.pipeline[b]);
            int reads = sampled & readsOf(pipeline);
            int writes = sampled & writesOf(pipeline, pass.state);
            if ((reads & depthUnseen) != 0) {
                packed.copyBefore[b] |= CgTargetCopy.DEPTH;
                depthUnseen = 0;
            }
            if ((reads & CgTargetCopy.COLOR) != 0) {
                // NaN before its shader parses: the whole target. One texel more for the linear filter.
                float share = pipeline.shader().sceneColorMargin();
                screenRect(b, regions && !Float.isNaN(share), width, height, share * height + 1f, rect);
                if (!isEmpty(rect)) {
                    if (run >= 0) {
                        union(packed.copyRect, run * 4, rect);
                    } else if (!copied || !contains(lastCopy, rect) || intersects(dirty, rect)) {
                        run = b;
                        copied = true;
                        packed.copyBefore[b] |= CgTargetCopy.COLOR;
                        System.arraycopy(rect, 0, packed.copyRect, b * 4, 4);
                        clear(dirty);
                    }
                }
            }
            if ((writes & CgTargetCopy.COLOR) != 0) {
                screenRect(b, regions, width, height, 0f, rect);
                union(dirty, 0, rect);
                if ((reads & CgTargetCopy.COLOR) == 0 && run >= 0) {
                    System.arraycopy(packed.copyRect, run * 4, lastCopy, 0, 4);
                    run = -1;
                }
            }
            // A reader's depth write is seen by the next reader, unlike its colour: the distortion apply writes where it
            // read from, and the sky seal after it must test that, not the unbent depth.
            if ((writes & CgTargetCopy.DEPTH) != 0) depthUnseen = sampled & CgTargetCopy.DEPTH;
        }
    }

    /**
     * Batch {@code b}'s bounds grown by {@code margin}, into {@code out} in GL pixels from the bottom left, cut to the
     * target; the whole target ({@link #isWhole}) where they are infinite or not in the target's pixels.
     */
    private void screenRect(int b, boolean regions, float width, float height, float margin, int[] out) {
        int domain = batcher.batchBounds(b, batchBounds);
        float x0 = batchBounds[0], y0 = batchBounds[1], x1 = batchBounds[2], y1 = batchBounds[3];
        if (!regions || domain != 0 || x0 == Float.NEGATIVE_INFINITY || y0 == Float.NEGATIVE_INFINITY
                || x1 == Float.POSITIVE_INFINITY || y1 == Float.POSITIVE_INFINITY) {
            out[0] = out[1] = 0;
            out[2] = out[3] = -1;
            return;
        }
        // Bounds run down from the top; GL's rows run up from the bottom.
        int w = (int) Math.ceil(width), h = (int) Math.ceil(height);
        out[0] = Math.max(0, (int) Math.floor(x0 - margin));
        out[1] = Math.max(0, (int) Math.floor(height - (y1 + margin)));
        out[2] = Math.min(w, (int) Math.ceil(x1 + margin));
        out[3] = Math.min(h, (int) Math.ceil(height - (y0 - margin)));
    }

    /** A rect whose x1 is below 0 is the whole target, whatever its size. */
    static boolean isWhole(int[] r, int at) {
        return r[at + 2] < 0;
    }

    private static boolean isEmpty(int[] r) {
        return !isWhole(r, 0) && (r[2] <= r[0] || r[3] <= r[1]);
    }

    private static void clear(int[] r) {
        r[0] = r[1] = r[2] = r[3] = 0;
    }

    /** Unions {@code r} into the rect at {@code at} of {@code into}; an empty one there takes {@code r}. */
    private static void union(int[] into, int at, int[] r) {
        if (isWhole(into, at) || isEmpty(r)) return;
        if (isWhole(r, 0) || into[at + 2] <= into[at] || into[at + 3] <= into[at + 1]) {
            System.arraycopy(r, 0, into, at, 4);
            return;
        }
        into[at] = Math.min(into[at], r[0]);
        into[at + 1] = Math.min(into[at + 1], r[1]);
        into[at + 2] = Math.max(into[at + 2], r[2]);
        into[at + 3] = Math.max(into[at + 3], r[3]);
    }

    private static boolean contains(int[] outer, int[] r) {
        if (isWhole(outer, 0)) return true;
        if (isWhole(r, 0)) return false;
        return outer[0] <= r[0] && outer[1] <= r[1] && outer[2] >= r[2] && outer[3] >= r[3];
    }

    private static boolean intersects(int[] a, int[] r) {
        if (isEmpty(a) || isEmpty(r)) return false;
        if (isWhole(a, 0) || isWhole(r, 0)) return true;
        return a[0] < r[2] && r[0] < a[2] && a[1] < r[3] && r[1] < a[3];
    }

    private static int readsOf(CgPipeline pipeline) {
        return (pipeline.shader().readsSceneColor() ? CgTargetCopy.COLOR : 0)
                | (pipeline.shader().readsSceneDepth() ? CgTargetCopy.DEPTH : 0);
    }

    /** What a pipeline's draws write into the target: its own state, else the pass's; undeclared is a write. */
    private static int writesOf(CgPipeline pipeline, CgRenderState passState) {
        CgRenderState own = pipeline.state();
        List<CgColorMask> masks = own != null && !own.getColorMasks().isEmpty() ? own.getColorMasks()
                : passState != null ? passState.getColorMasks() : Collections.<CgColorMask>emptyList();
        boolean color = masks.isEmpty();
        for (int i = 0; i < masks.size(); i++) {
            CgColorMask mask = masks.get(i);
            color |= mask.r() || mask.g() || mask.b() || mask.a();
        }
        CgDepthState depth = own != null && own.getDepth() != null ? own.getDepth()
                : passState != null ? passState.getDepth() : null;
        return (color ? CgTargetCopy.COLOR : 0) | (depth == null || depth.write() ? CgTargetCopy.DEPTH : 0);
    }

    /** {@code table}'s ids mapped into the frame's, -1 until first used; the arrays are kept between builds. */
    private int[] internMap(CgBindingTable table) {
        int[] map = interned.get(table);
        if (map != null && map.length >= table.size()) return map;
        int[] fresh;
        if (internMapsUsed < internMaps.size() && internMaps.get(internMapsUsed).length >= table.size()) {
            fresh = internMaps.get(internMapsUsed);
        } else {
            fresh = new int[Math.max(64, Integer.highestOneBit(Math.max(1, table.size())) * 2)];
            if (internMapsUsed < internMaps.size()) internMaps.set(internMapsUsed, fresh);
            else internMaps.add(fresh);
        }
        internMapsUsed++;
        Arrays.fill(fresh, -1);
        if (map != null) System.arraycopy(map, 0, fresh, 0, map.length);
        interned.put(table, fresh);
        return fresh;
    }

    private void edge(int from, int to) {
        out[from].add(to);
        in[to].add(from);
    }

    private static IntList[] grow(IntList[] lists, int need) {
        IntList[] grown = Arrays.copyOf(lists, Math.max(need, lists.length * 2));
        for (int i = lists.length; i < grown.length; i++) grown[i] = new IntList();
        return grown;
    }

    private static final class IntList {
        int[] values = new int[8];
        int size;

        void add(int v) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = v;
        }

        int get(int i) {
            return values[i];
        }

        void set(int i, int v) {
            values[i] = v;
        }

        int pop() {
            return values[--size];
        }

        void clear() {
            size = 0;
        }
    }

    /** A binary min-heap of pass indices. */
    private static final class IntHeap {
        final int[] heap;
        int size;

        IntHeap(int capacity) {
            heap = new int[Math.max(1, capacity)];
        }

        void push(int v) {
            int i = size++;
            heap[i] = v;
            while (i > 0 && heap[(i - 1) / 2] > heap[i]) {
                int parent = (i - 1) / 2;
                int swap = heap[parent];
                heap[parent] = heap[i];
                heap[i] = swap;
                i = parent;
            }
        }

        int pop() {
            int top = heap[0];
            heap[0] = heap[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, least = i;
                if (l < size && heap[l] < heap[least]) least = l;
                if (r < size && heap[r] < heap[least]) least = r;
                if (least == i) return top;
                int swap = heap[least];
                heap[least] = heap[i];
                heap[i] = swap;
                i = least;
            }
        }
    }
}
