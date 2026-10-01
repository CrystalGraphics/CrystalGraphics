package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBatcher;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Turns a {@link CgFrameGraph} into a {@link CgFrame}: orders the passes by what they read and write, culls those
 * nobody reads, plans when each transient texture lives, batches every raster pass, and packs every instance of a
 * kind into one array and every binding snapshot into one table. No GL, so it runs wherever the frame is built — a
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
 * </ul>
 */
public final class CgFrameBuilder {

    private static final int KINDS = CgInstanceKind.values().length;
    private static final int PASSES = CgTrace.name("graph.passes");
    private static final int BATCHES = CgTrace.name("graph.batches");
    private static final int DRAWS = CgTrace.name("graph.draws");
    private static final int SNAPSHOTS = CgTrace.name("graph.snapshots");
    private static final int INSTANCES = CgTrace.name("graph.instances");

    private final CgBatcher batcher = new CgBatcher();
    private final ConcurrentLinkedQueue<CgFrame> recycled = new ConcurrentLinkedQueue<>();

    // Scratch, kept between builds.
    private CgPass[] passes = new CgPass[16];
    private int[] base = new int[4];
    private final IdentityHashMap<CgGraphTexture, Integer> slots = new IdentityHashMap<>();
    private final List<CgGraphTexture> textures = new ArrayList<>();
    private IntList[] out = new IntList[0], in = new IntList[0], uses = new IntList[0];
    private final List<IntList> readers = new ArrayList<>();
    private final IntList lastWriter = new IntList();
    private boolean[] needed = new boolean[16];
    private final IntList stack = new IntList();
    private int[] indegree = new int[16];
    private int[] order = new int[16];
    private IntHeap ready = new IntHeap(16);
    private int[] firstUse = new int[16], lastUse = new int[16];
    private final IdentityHashMap<CgBindingTable, int[]> interned = new IdentityHashMap<>();
    private final List<int[]> internMaps = new ArrayList<>();
    private int internMapsUsed;
    private CgDrawChunk[] refChunk = new CgDrawChunk[256];
    private int[] refDraw = new int[256];

    /** Builds the frame. The graph's recordings are only read. */
    public CgFrame build(CgFrameGraph graph) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.build")) {
            List<CgRecording> recordings = graph.recordings();
            int total = flatten(recordings);
            edges(recordings, total);
            cull(total);
            int steps = topological(total);

            CgFrame frame = recycled.poll();
            if (frame == null) frame = new CgFrame();
            frame.steps(steps);
            for (int s = 0; s < steps; s++) frame.steps[s] = passes[order[s]];
            lifetimes(frame, steps);

            interned.clear();
            internMapsUsed = 0;
            for (int s = 0; s < steps; s++) {
                if (frame.steps[s] instanceof CgRasterPass raster) {
                    CgFrame.Raster packed = frame.raster(s);
                    pack(raster, frame, packed);
                    frame.batches += packed.count;
                    frame.draws += packed.draws;
                }
            }
            Arrays.fill(passes, 0, total, null);
            slots.clear();
            textures.clear();

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
        }
        for (int p = 0; p < total; p++) {
            out[p].clear();
            in[p].clear();
            uses[p].clear();
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
                CgGraphTexture texture = rec.eventTexture(e);
                Integer slot = slots.get(texture);
                if (slot == null) {
                    slot = textures.size();
                    slots.put(texture, slot);
                    textures.add(texture);
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
            if (passes[p].sideEffect()) {
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

    /** Kahn's algorithm over the passes still needed, the lowest creation index first: creation order breaks ties. */
    private int topological(int total) {
        if (indegree.length < total) {
            indegree = new int[Math.max(total, indegree.length * 2)];
            order = new int[indegree.length];
            ready = new IntHeap(indegree.length);
        }
        Arrays.fill(indegree, 0, total, 0);
        int count = 0;
        for (int p = 0; p < total; p++) {
            if (!needed[p]) continue;
            count++;
            for (int i = 0; i < out[p].size; i++) if (needed[out[p].get(i)]) indegree[out[p].get(i)]++;
        }
        ready.size = 0;
        for (int p = 0; p < total; p++) if (needed[p] && indegree[p] == 0) ready.push(p);
        int at = 0;
        while (ready.size > 0) {
            int p = ready.pop();
            order[at++] = p;
            for (int i = 0; i < out[p].size; i++) {
                int q = out[p].get(i);
                if (needed[q] && --indegree[q] == 0) ready.push(q);
            }
        }
        if (at < count) {
            StringBuilder stuck = new StringBuilder();
            for (int p = 0; p < total; p++) if (needed[p] && indegree[p] > 0) stuck.append(' ').append(passes[p]);
            throw new IllegalStateException("passes read what each other write:" + stuck);
        }
        return count;
    }

    /** When each transient lives, in the executed order. */
    private void lifetimes(CgFrame frame, int steps) {
        int n = textures.size();
        if (firstUse.length < n) {
            firstUse = new int[Math.max(n, firstUse.length * 2)];
            lastUse = new int[firstUse.length];
        }
        Arrays.fill(firstUse, 0, n, -1);
        for (int s = 0; s < steps; s++) {
            IntList used = uses[order[s]];
            for (int i = 0; i < used.size; i++) {
                int t = used.get(i);
                if (textures.get(t).kind() != CgGraphTexture.Kind.TRANSIENT) continue;
                if (firstUse[t] < 0) firstUse[t] = s;
                lastUse[t] = s;
            }
        }
        int count = 0;
        for (int t = 0; t < n; t++) if (firstUse[t] >= 0) count++;
        frame.lifetimes(count);
        for (int t = 0, i = 0; t < n; t++) {
            if (firstUse[t] < 0) continue;
            frame.transients.add(textures.get(t));
            frame.acquireAt[i] = firstUse[t];
            frame.releaseAfter[i] = lastUse[t];
            i++;
        }
    }

    private void pack(CgRasterPass pass, CgFrame frame, CgFrame.Raster packed) {
        batcher.reset(pass.order);
        int refs = 0;
        for (CgDrawChunk chunk : pass.chunkList()) {
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
                // The chunk's spatial node is its batching domain until G10 names the movable ones.
                batcher.add(chunk.pipeline(d), map[local], chunk.kind(d), chunk.mesh(d), chunk.spatial(),
                        chunk.x0(d), chunk.y0(d), chunk.x1(d), chunk.y1(d), chunk.sortKey(d), refs);
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
            packed.mesh[b] = (CgMesh) batcher.batchMesh(b);
            packed.first[b] = frame.instanceFloats[ki] / floats;
            packed.kinds |= 1 << ki;
            for (int i = batcher.batchStart(b); i < batcher.batchEnd(b); i++) {
                CgDrawChunk chunk = refChunk[batcher.ref(i)];
                int d = refDraw[batcher.ref(i)];
                int length = chunk.instances(d) * floats;
                frame.reserve(ki, length);
                System.arraycopy(chunk.data(k), chunk.first(d) * floats, frame.instances[ki], frame.instanceFloats[ki], length);
                frame.instanceFloats[ki] += length;
                packed.instances[b] += chunk.instances(d);
            }
        }
        Arrays.fill(refChunk, 0, refs, null);
        packed.constants = frame.bindings.begin()
                .block(CgBindingPoints.FRAME_DATA_UBO, pass.constants, 0, CgPassConstants.FLOATS)
                .end();
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
