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
 * renderThread.post(() -> { CgExecutor.get().execute(frame); builder.recycle(frame); });
 * }</pre>
 *
 * <ul>
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
    private final ConcurrentLinkedQueue<Body> recycled = new ConcurrentLinkedQueue<>();

    /** Per draw handed to the batcher: the chunk and its index in it. */
    private CgDrawChunk[] refChunk = new CgDrawChunk[256];
    private int[] refDraw = new int[256];

    /** Builds the frame. The graph's recordings are only read. */
    public CgFrame build(CgFrameGraph graph) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.build")) {
            List<CgRecording> recordings = graph.recordings();
            int total = 0;
            for (CgRecording r : recordings) total += r.passCount();
            CgPass[] passes = new CgPass[total];
            int[] base = new int[recordings.size()];
            for (int r = 0, at = 0; r < recordings.size(); r++) {
                base[r] = at;
                for (int p = 0; p < recordings.get(r).passCount(); p++) passes[at++] = recordings.get(r).pass(p);
            }

            // Edges from the event log: a read after the write it sees, a write after the reads and write before it.
            IdentityHashMap<CgGraphTexture, Integer> slots = new IdentityHashMap<>();
            List<CgGraphTexture> textures = new ArrayList<>();
            IntList[] out = lists(total), in = lists(total), uses = lists(total);
            List<IntList> readers = new ArrayList<>();
            IntList lastWriter = new IntList();
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
                        readers.add(new IntList());
                        lastWriter.add(-1);
                    }
                    int t = slot;
                    uses[p].add(t);
                    int writer = lastWriter.get(t);
                    if (writer >= 0 && writer != p) edge(out, in, writer, p);
                    if (rec.eventType(e) == CgRecording.READ) {
                        readers.get(t).add(p);
                    } else {
                        IntList since = readers.get(t);
                        for (int i = 0; i < since.size; i++) if (since.get(i) != p) edge(out, in, since.get(i), p);
                        since.clear();
                        lastWriter.set(t, p);
                    }
                }
            }

            // Cull: keep what has an effect beyond the frame, and everything it depends on.
            boolean[] needed = new boolean[total];
            IntList stack = new IntList();
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

            int[] order = topological(passes, out, needed);

            // Transient lifetimes, in the executed order.
            List<CgGraphTexture> transients = new ArrayList<>();
            int[] firstUse = new int[textures.size()], lastUse = new int[textures.size()];
            Arrays.fill(firstUse, -1);
            for (int s = 0; s < order.length; s++) {
                IntList used = uses[order[s]];
                for (int i = 0; i < used.size; i++) {
                    int t = used.get(i);
                    if (textures.get(t).kind() != CgGraphTexture.Kind.TRANSIENT) continue;
                    if (firstUse[t] < 0) firstUse[t] = s;
                    lastUse[t] = s;
                }
            }
            IntList acquire = new IntList(), release = new IntList();
            for (int t = 0; t < textures.size(); t++) {
                if (firstUse[t] < 0) continue;
                transients.add(textures.get(t));
                acquire.add(firstUse[t]);
                release.add(lastUse[t]);
            }

            // Batch and pack.
            Body body = recycled.poll();
            if (body == null) body = new Body();
            IdentityHashMap<CgBindingTable, int[]> interned = new IdentityHashMap<>();
            CgPass[] steps = new CgPass[order.length];
            CgFrame.Raster[] rasters = new CgFrame.Raster[order.length];
            int batches = 0, draws = 0;
            for (int s = 0; s < order.length; s++) {
                steps[s] = passes[order[s]];
                if (steps[s] instanceof CgRasterPass raster) {
                    rasters[s] = pack(raster, body, interned);
                    batches += rasters[s].count;
                    draws += rasters[s].draws;
                }
            }

            CgTrace.add(CgChannels.GL, PASSES, steps.length);
            CgTrace.add(CgChannels.GL, BATCHES, batches);
            CgTrace.add(CgChannels.GL, DRAWS, draws);
            CgTrace.add(CgChannels.GL, SNAPSHOTS, body.bindings.size());
            long instances = 0;
            for (int k = 0; k < KINDS; k++) instances += body.floats[k] / CgInstanceKind.of(k).floats();
            CgTrace.add(CgChannels.GL, INSTANCES, instances);

            return new CgFrame(steps, rasters, body.bindings, body.instances, body.floats, transients,
                    acquire.toArray(), release.toArray(), batches, draws, body);
        }
    }

    /** Takes back a frame's arrays once it has executed; the frame must not be used again. Any thread. */
    public void recycle(CgFrame frame) {
        Body body = frame.body;
        body.bindings.reset();
        Arrays.fill(body.floats, 0);
        recycled.add(body);
    }

    private CgFrame.Raster pack(CgRasterPass pass, Body body, IdentityHashMap<CgBindingTable, int[]> interned) {
        batcher.reset(pass.order);
        int refs = 0;
        for (CgDrawChunk chunk : pass.chunkList()) {
            CgBindingTable table = chunk.bindings();
            int[] map = interned.get(table);
            if (map == null || map.length < table.size()) {
                int[] grown = new int[table.size()];
                Arrays.fill(grown, -1);
                if (map != null) System.arraycopy(map, 0, grown, 0, map.length);
                map = grown;
                interned.put(table, map);
            }
            for (int d = 0; d < chunk.draws(); d++) {
                int local = chunk.binding(d);
                if (map[local] < 0) map[local] = body.bindings.copy(table, local);
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
        int[] pipeline = new int[n], binding = new int[n], kind = new int[n], first = new int[n], count = new int[n];
        CgMesh[] mesh = new CgMesh[n];
        int kinds = 0;
        for (int b = 0; b < n; b++) {
            CgInstanceKind k = batcher.batchKind(b);
            int floats = k.floats();
            int ki = k.ordinal();
            pipeline[b] = batcher.batchPipeline(b);
            binding[b] = batcher.batchBinding(b);
            kind[b] = ki;
            mesh[b] = (CgMesh) batcher.batchMesh(b);
            first[b] = body.floats[ki] / floats;
            kinds |= 1 << ki;
            for (int i = batcher.batchStart(b); i < batcher.batchEnd(b); i++) {
                CgDrawChunk chunk = refChunk[batcher.ref(i)];
                int d = refDraw[batcher.ref(i)];
                int length = chunk.instances(d) * floats;
                body.reserve(ki, length);
                System.arraycopy(chunk.data(k), chunk.first(d) * floats, body.instances[ki], body.floats[ki], length);
                body.floats[ki] += length;
                count[b] += chunk.instances(d);
            }
        }
        Arrays.fill(refChunk, 0, refs, null);
        int constants = body.bindings.begin()
                .block(CgBindingPoints.FRAME_DATA_UBO, pass.constants, 0, CgPassConstants.FLOATS)
                .end();
        return new CgFrame.Raster(constants, n, pipeline, binding, kind, first, count, mesh, kinds, refs);
    }

    /** Kahn's algorithm over the passes still needed, the lowest creation index first: creation order breaks ties. */
    private static int[] topological(CgPass[] passes, IntList[] out, boolean[] needed) {
        int total = passes.length;
        int[] indegree = new int[total];
        int count = 0;
        for (int p = 0; p < total; p++) {
            if (!needed[p]) continue;
            count++;
            for (int i = 0; i < out[p].size; i++) if (needed[out[p].get(i)]) indegree[out[p].get(i)]++;
        }
        IntHeap ready = new IntHeap(total);
        for (int p = 0; p < total; p++) if (needed[p] && indegree[p] == 0) ready.push(p);
        int[] order = new int[count];
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
        return order;
    }

    private static void edge(IntList[] out, IntList[] in, int from, int to) {
        out[from].add(to);
        in[to].add(from);
    }

    private static IntList[] lists(int n) {
        IntList[] lists = new IntList[n];
        for (int i = 0; i < n; i++) lists[i] = new IntList();
        return lists;
    }

    /** A frame's arrays, handed back by {@link #recycle} so a steady frame allocates no instance storage. */
    static final class Body {
        final CgBindingTable bindings = new CgBindingTable();
        final float[][] instances = new float[KINDS][];
        final int[] floats = new int[KINDS];

        Body() {
            for (int k = 0; k < KINDS; k++) instances[k] = new float[CgInstanceKind.of(k).floats() * 256];
        }

        void reserve(int kind, int more) {
            int need = floats[kind] + more;
            if (need > instances[kind].length) instances[kind] = Arrays.copyOf(instances[kind], Math.max(need, instances[kind].length * 2));
        }
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

        int[] toArray() {
            return Arrays.copyOf(values, size);
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
