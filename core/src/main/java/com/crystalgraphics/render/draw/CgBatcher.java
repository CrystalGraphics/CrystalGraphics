package com.crystalgraphics.render.draw;

import java.util.Arrays;

/**
 * Groups a pass's draws into batches: draws one pipeline, binding snapshot, kind and mesh can draw in one call,
 * without changing what overlapping draws look like. CPU only and reusable; a frame builder runs it per pass.
 *
 * <pre>{@code
 * batcher.reset(CgOrder.LOOKBACK);
 * for (each draw, in submission order) batcher.add(pipeline, binding, kind, mesh, domain, scissor, x0, y0, x1, y1, sortKey, ref);
 * batcher.finish();
 * for (int b = 0; b < batcher.batches(); b++) {
 *     for (int i = batcher.batchStart(b); i < batcher.batchEnd(b); i++) use(batcher.ref(i));   // submission order kept
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Within a batch, draws keep submission order, which is the order GL blends primitives in.</li>
 *   <li>A <b>domain</b> is a set of draws whose relative positions cannot change after batching. Draws in different
 *       domains are treated as overlapping, so a batch stays correct when the compositor moves one domain. The last
 *       batch still takes a draw of its key from any domain, since nothing lies between them; no later draw looks
 *       past it.</li>
 *   <li>A <b>scissor</b> is part of a batch's key: draws under different ones never join, though a draw may still
 *       move past one it does not overlap.</li>
 *   <li>{@link CgOrder#LOOKBACK} looks back over at most {@value #LOOKBACK} batches.</li>
 * </ul>
 */
public final class CgBatcher {

    /** Batches a draw looks back over before opening its own. */
    public static final int LOOKBACK = 32;

    /** A batch with more draws than this is tested by its union alone, which only ever errs towards a new batch. */
    private static final int RECT_TESTS = 64;

    private CgOrder order = CgOrder.LOOKBACK;

    private int draws;
    private int[] refs = new int[256];
    private float[] rects = new float[256 * 4];
    private long[] sortKeys = new long[256];
    private int[] next = new int[256];
    /** Per draw in SORTED order: its key, kept until finish() batches the sorted draws. */
    private int[] pipelines = new int[256];
    private int[] bindings = new int[256];
    private int[] kinds = new int[256];
    private Object[] meshes = new Object[256];
    private int[] domains = new int[256];
    private int[] scissors = new int[256];

    private int batchCount;
    private int[] batchPipeline = new int[64];
    private int[] batchBinding = new int[64];
    private int[] batchKind = new int[64];
    private Object[] batchMesh = new Object[64];
    private int[] batchDomain = new int[64];
    private int[] batchScissor = new int[64];
    private float[] batchUnion = new float[64 * 4];
    private int[] batchHead = new int[64];
    private int[] batchTail = new int[64];
    private int[] batchSize = new int[64];

    private int[] sorted = new int[256];
    private int[] scratch = new int[256];

    private int[] ordered = new int[256];
    private int[] batchStart = new int[65];

    /** Starts a pass. */
    public void reset(CgOrder order) {
        this.order = order;
        draws = 0;
        batchCount = 0;
        Arrays.fill(meshes, null);
        Arrays.fill(batchMesh, null);
    }

    /** Adds the pass's next draw; {@code ref} is the caller's handle for it, given back in batch order. */
    public void add(int pipeline, int binding, CgInstanceKind kind, Object mesh, int domain, int scissor,
                    float x0, float y0, float x1, float y1, long sortKey, int ref) {
        if (draws == refs.length) growDraws();
        int d = draws++;
        refs[d] = ref;
        rects[d * 4] = x0;
        rects[d * 4 + 1] = y0;
        rects[d * 4 + 2] = x1;
        rects[d * 4 + 3] = y1;
        sortKeys[d] = sortKey;
        next[d] = -1;
        pipelines[d] = pipeline;
        bindings[d] = binding;
        kinds[d] = kind.ordinal();
        meshes[d] = mesh;
        domains[d] = domain;
        scissors[d] = scissor;
        if (order == CgOrder.LOOKBACK) lookback(d);
    }

    /** Ends the pass: after it, the batches and their draws are readable. */
    public void finish() {
        if (order == CgOrder.SORTED) sortAndMerge();
        if (ordered.length < draws) ordered = new int[refs.length];
        if (batchStart.length < batchCount + 1) batchStart = new int[batchHead.length + 1];
        int at = 0;
        for (int b = 0; b < batchCount; b++) {
            batchStart[b] = at;
            for (int d = batchHead[b]; d >= 0; d = next[d]) ordered[at++] = refs[d];
        }
        batchStart[batchCount] = at;
    }

    public int batches() {
        return batchCount;
    }

    public int batchPipeline(int batch) {
        return batchPipeline[batch];
    }

    public int batchBinding(int batch) {
        return batchBinding[batch];
    }

    public CgInstanceKind batchKind(int batch) {
        return CgInstanceKind.of(batchKind[batch]);
    }

    public Object batchMesh(int batch) {
        return batchMesh[batch];
    }

    /** The scissor every draw in {@code batch} was added with. */
    public int batchScissor(int batch) {
        return batchScissor[batch];
    }

    /** The batch's first position in {@link #ref}. */
    public int batchStart(int batch) {
        return batchStart[batch];
    }

    /** One past the batch's last position in {@link #ref}. */
    public int batchEnd(int batch) {
        return batchStart[batch + 1];
    }

    /** The caller's handle of the draw at {@code position}, batch after batch. */
    public int ref(int position) {
        return ordered[position];
    }

    /** The domain of a batch holding draws of several: no draw's, so nothing joins it but as the last batch. */
    private static final int MIXED = -1;

    private void lookback(int d) {
        int last = batchCount - 1;
        if (last >= 0 && sameKey(last, d)) {
            if (batchDomain[last] != domains[d]) batchDomain[last] = MIXED;
            join(last, d);
            return;
        }
        int stop = Math.max(0, batchCount - LOOKBACK);
        for (int b = batchCount - 1; b >= stop; b--) {
            if (sameKey(b, d) && batchDomain[b] == domains[d]) {
                join(b, d);
                return;
            }
            if (batchDomain[b] != domains[d] || overlaps(b, d)) break;
        }
        open(d);
    }

    /** Stable by key, then adjacent equal batches merged: equal keys with nothing between them draw in one call. */
    private void sortAndMerge() {
        if (sorted.length < draws) {
            sorted = new int[refs.length];
            scratch = new int[refs.length];
        }
        for (int i = 0; i < draws; i++) sorted[i] = i;
        int[] from = sorted, to = scratch;
        for (int width = 1; width < draws; width *= 2) {   // bottom-up merge sort: stable, no boxing
            for (int lo = 0; lo < draws; lo += 2 * width) {
                int mid = Math.min(lo + width, draws), hi = Math.min(lo + 2 * width, draws);
                int a = lo, b = mid, k = lo;
                while (a < mid && b < hi) to[k++] = sortKeys[from[b]] < sortKeys[from[a]] ? from[b++] : from[a++];
                while (a < mid) to[k++] = from[a++];
                while (b < hi) to[k++] = from[b++];
            }
            int[] swap = from;
            from = to;
            to = swap;
        }
        for (int i = 0; i < draws; i++) {
            int d = from[i];
            if (batchCount > 0 && sameKey(batchCount - 1, d)) join(batchCount - 1, d);
            else open(d);
        }
    }

    private boolean sameKey(int b, int d) {
        return batchPipeline[b] == pipelines[d] && batchBinding[b] == bindings[d] && batchKind[b] == kinds[d]
                && batchMesh[b] == meshes[d] && batchScissor[b] == scissors[d];
    }

    private void open(int d) {
        if (batchCount == batchHead.length) growBatches();
        int b = batchCount++;
        batchPipeline[b] = pipelines[d];
        batchBinding[b] = bindings[d];
        batchKind[b] = kinds[d];
        batchMesh[b] = meshes[d];
        batchDomain[b] = domains[d];
        batchScissor[b] = scissors[d];
        System.arraycopy(rects, d * 4, batchUnion, b * 4, 4);
        batchHead[b] = d;
        batchTail[b] = d;
        batchSize[b] = 1;
    }

    private void join(int b, int d) {
        next[batchTail[b]] = d;
        batchTail[b] = d;
        batchSize[b]++;
        int u = b * 4, r = d * 4;
        batchUnion[u] = Math.min(batchUnion[u], rects[r]);
        batchUnion[u + 1] = Math.min(batchUnion[u + 1], rects[r + 1]);
        batchUnion[u + 2] = Math.max(batchUnion[u + 2], rects[r + 2]);
        batchUnion[u + 3] = Math.max(batchUnion[u + 3], rects[r + 3]);
    }

    private boolean overlaps(int b, int d) {
        if (!intersects(batchUnion, b * 4, rects, d * 4)) return false;
        if (batchSize[b] > RECT_TESTS) return true;
        for (int o = batchHead[b]; o >= 0; o = next[o]) {
            if (intersects(rects, o * 4, rects, d * 4)) return true;
        }
        return false;
    }

    /** Touching edges do not overlap: two abutting boxes may share a batch. */
    private static boolean intersects(float[] a, int i, float[] b, int j) {
        return a[i] < b[j + 2] && b[j] < a[i + 2] && a[i + 1] < b[j + 3] && b[j + 1] < a[i + 3];
    }

    private void growDraws() {
        int n = refs.length * 2;
        refs = Arrays.copyOf(refs, n);
        rects = Arrays.copyOf(rects, n * 4);
        sortKeys = Arrays.copyOf(sortKeys, n);
        next = Arrays.copyOf(next, n);
        pipelines = Arrays.copyOf(pipelines, n);
        bindings = Arrays.copyOf(bindings, n);
        kinds = Arrays.copyOf(kinds, n);
        meshes = Arrays.copyOf(meshes, n);
        domains = Arrays.copyOf(domains, n);
        scissors = Arrays.copyOf(scissors, n);
    }

    private void growBatches() {
        int n = batchHead.length * 2;
        batchPipeline = Arrays.copyOf(batchPipeline, n);
        batchBinding = Arrays.copyOf(batchBinding, n);
        batchKind = Arrays.copyOf(batchKind, n);
        batchMesh = Arrays.copyOf(batchMesh, n);
        batchDomain = Arrays.copyOf(batchDomain, n);
        batchScissor = Arrays.copyOf(batchScissor, n);
        batchUnion = Arrays.copyOf(batchUnion, n * 4);
        batchHead = Arrays.copyOf(batchHead, n);
        batchTail = Arrays.copyOf(batchTail, n);
        batchSize = Arrays.copyOf(batchSize, n);
    }
}
