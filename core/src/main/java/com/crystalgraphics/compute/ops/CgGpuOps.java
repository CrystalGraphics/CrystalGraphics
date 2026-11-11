package com.crystalgraphics.compute.ops;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * What every GPU-driven consumer otherwise writes for itself: fills, sequences, copies, reductions,
 * scans, compaction, sorting, histograms and bounds over buffers, culling instances (C9b), and mip chains, depth
 * pyramids and blurs over textures, dispatched
 * into the caller's compute pass and run on every tier (as compute, lowered below it, or by Java bodies on the CPU
 * tier) with the same answer on each.
 *
 * <pre>{@code
 * CgComputePass pass = recording.compute("particles");
 * pass.dispatch(simulate, capacity).bind("STATE", state).bind("ALIVE", flags);
 * CgGpuCount all = CgGpuCount.of(capacity);
 * CgGpuOps.compact(pass, flags, null, all, survivors, counts, 0);              // the live indices, in order
 * CgGpuCount alive = CgGpuCount.at(counts, 0, capacity);                      // how many, as the GPU counted
 * CgGpuOps.sort(pass, Element.FLOAT, Order.DESCENDING, depths, survivors, alive);   // back to front
 * CgGpuOps.dispatchArgs(pass, alive, 64, args, 0);                            // a dispatch sized to them
 * pass.end();
 * }</pre>
 *
 * <ul>
 *   <li>An op's dispatches go into the pass in order with its scratch as graph transients, so the graph places the
 *       barriers between them and the caller's own dispatches.</li>
 *   <li>A count is fixed or a word on the GPU ({@link CgGpuCount}). A GPU count dispatches the capacity and every
 *       kernel stops at the count it reads, so no op needs the count on the CPU.</li>
 *   <li>Buffers hold 32-bit words, and each needs {@link CgBufferUsage#STORAGE}: an {@link Element} says how to read
 *       them, and a float is its bits. Nothing past the count is written.</li>
 *   <li>A float sum folds sixteen at a time, left to right, a level at a time: the same order on every tier, so the
 *       same bits, though not the bits a plain loop would add to.</li>
 *   <li>An op never writes what it reads; it throws when handed one buffer for both.</li>
 *   <li>The image ops take graph textures of the {@link #IMAGE_TYPES}, and answer within a rounding of the format
 *       rather than in the same bits.</li>
 * </ul>
 */
public final class CgGpuOps {

    /** How values combine. */
    public enum Fold { SUM, MIN, MAX }

    /** How a buffer's words read. */
    public enum Element { UINT, INT, FLOAT }

    /** Whether element i of a scan includes element i. */
    public enum Scan { INCLUSIVE, EXCLUSIVE }

    /** Which way a sort runs. */
    public enum Order { ASCENDING, DESCENDING }

    /**
     * What a mip texel holds of the texels it covers: their area-weighted mean, or the least or greatest of every one
     * it touches (a depth pyramid's nearest or farthest).
     */
    public enum Filter { AVERAGE, MIN, MAX }

    /** What a blur reads past its region: the edge texel held, or the region folded back in (CSS's mirror). */
    public enum Edge { CLAMP, MIRROR }

    /** Where {@link #readRows} delivers: the count as the GPU wrote it, and the rows. */
    @FunctionalInterface
    public interface Rows {

        /**
         * {@code count} as written, which may pass the capacity, and {@code min(count, capacity)} rows: {@code rows}'
         * limit is their bytes. Native order; valid only during the call.
         */
        void accept(int count, ByteBuffer rows);

        /** They will never arrive: the context was torn down, a read failed, or {@link #accept} threw. */
        default void failed(String reason) {}
    }

    /** The colour formats the image ops take. */
    public static final List<CgTextureType> IMAGE_TYPES =
            List.of(CgTextureType.RGBA8, CgTextureType.RGBA16F, CgTextureType.R16F, CgTextureType.R32F);

    /** A depth pyramid's: one float a texel, the eye depth. */
    public static final CgFrameBufferFormat PYRAMID_FORMAT =
            CgFrameBufferFormat.builder("cg_depth_pyramid").color(0, CgTextureType.R32F).build();

    /** What one element of a level folds of the level below it. */
    static final int BLOCK = 16;

    private static final String FILL_PATH = "crystalgraphics:shaders/env/compute/ops/fill.compute";
    private static final String SCAN_PATH = "crystalgraphics:shaders/env/compute/ops/scan.compute";
    private static final String SORT_PATH = "crystalgraphics:shaders/env/compute/ops/sort.compute";
    private static final String HISTOGRAM_PATH = "crystalgraphics:shaders/env/compute/ops/histogram.compute";
    private static final int REDUCE_STEP = 0, REDUCE_LAST = 1, SCAN_BLOCK = 2, COMPACT_INDICES = 3, COMPACT_VALUES = 4,
            COMPACT_COUNT = 5;
    private static final String[] SCAN_KERNELS = {"ReduceStep", "ReduceLast", "ScanBlock", "CompactIndices",
            "CompactValues", "CompactCount"};
    /** Each kernel under each set of keywords an op asks for, made once. */
    private static final CgKernel[][] SCAN_VARIANTS = new CgKernel[SCAN_KERNELS.length][3 * 3 * 2 * 2];
    private static final int SORT_COUNT = 0, SORT_REDUCE = 1, SORT_SCAN = 2, SORT_SCAN_ADD = 3, SORT_SCATTER = 4,
            SORT_SCATTER_KEYS = 5;
    private static final String[] SORT_KERNELS = {"SortCount", "SortReduce", "SortScan", "SortScanAdd", "SortScatter",
            "SortScatterKeys"};
    /** The sort's keys a block, and its work groups at most: few enough for one group to scan their sums. */
    private static final int SORT_BLOCK = 512, SORT_GROUPS = 1024;
    /** The grouped histogram's bins at most (held in shared memory), its work groups at most, and keys a group. */
    private static final int GROUP_BINS = 1024, HISTOGRAM_GROUPS = 256, HISTOGRAM_GROUP_KEYS = 4096;
    private static final CgKernel[][] SORT_VARIANTS = new CgKernel[SORT_KERNELS.length][3 * 2];
    private static final String[] SUMS = levels("ops.sums."), PREFIXES = levels("ops.prefixes.");
    private static final String IMAGE_PATH = "crystalgraphics:shaders/env/compute/ops/image.compute";
    private static final String CULL_PATH = "crystalgraphics:shaders/env/compute/ops/cull.compute";
    private static final String INDIRECT_ARGS = "crystalgraphics:shaders/env/compute/args.compute";
    private static final String EXPAND_PATH = "crystalgraphics:shaders/env/compute/ops/expand.compute";
    private static final String PYRAMID_PATH = "crystalgraphics:shaders/env/compute/ops/depth_pyramid.compute";
    /** Levels one pyramid dispatch makes past its first: a 64-texel tile to one texel, and seven images bound. */
    private static final int PYRAMID_BATCH = 6, PYRAMID_TILE = 64;
    private static final int GPU_PYRAMID = CgGpuTrace.name("ops.depthPyramid");
    private static final String[] PYRAMID_OUTS = {"OUT0", "OUT1", "OUT2", "OUT3", "OUT4", "OUT5", "OUT6"};
    /** What a dispatch making fewer than six levels binds where the rest would go: never written. */
    private static final CgGraphTexture PYRAMID_UNUSED = CgGraphTexture.transientTexture("ops.pyramid.unused",
            new CgTextureDesc(1, 1, PYRAMID_FORMAT));
    /** An object record's bytes: what each instance and each kept record of a cull is. */
    private static final int RECORD_BYTES = CgPrimitiveTable.FLOATS * 4;
    /** Each instance buffer's keys start at a multiple of this in a batched cull: a binding offset is a whole 256 bytes. */
    private static final int KEY_ALIGN = 64;
    /** Per image type: its kernels and images in image.compute. */
    static final String[] DOWNSAMPLE_KERNELS = {"DownsampleRgba8", "DownsampleRgba16f", "DownsampleR16f", "DownsampleR32f"},
            BLUR_KERNELS = {"BlurRgba8", "BlurRgba16f", "BlurR16f", "BlurR32f"},
            REDUCE_KERNELS = {"ReduceRgba8", "ReduceRgba16f", "ReduceR16f", "ReduceR32f"},
            SHRINK_KERNELS = {"ShrinkRgba8", "ShrinkRgba16f", "ShrinkR16f", "ShrinkR32f"},
            SOURCES = {"SRC_RGBA8", "SRC_RGBA16F", "SRC_R16F", "SRC_R32F"},
            TARGETS = {"DST_RGBA8", "DST_RGBA16F", "DST_R16F", "DST_R32F"};
    /** A blur's scratch, per image type. */
    private static final CgFrameBufferFormat[] SCRATCH = new CgFrameBufferFormat[IMAGE_TYPES.size()];
    private static final CgKernel[][] DOWNSAMPLE_VARIANTS = new CgKernel[IMAGE_TYPES.size()][Filter.values().length];
    /** Per image type: across, down, and each linear-sampled. */
    private static final CgKernel[][] BLUR_VARIANTS = new CgKernel[IMAGE_TYPES.size()][4];
    private static final CgKernel[] REDUCE_VARIANTS = new CgKernel[IMAGE_TYPES.size()];
    private static final CgKernel[] SHRINK_VARIANTS = new CgKernel[IMAGE_TYPES.size()];
    /** The widest sigma a blur keeps, in texels: its kernel reaches three of it, at most 64 texels. */
    public static final float MAX_BLUR_SIGMA = 64f / 3f;
    /** The largest sigma a scaled blur runs at, in its working texels: past it the region is reduced. Skia's is 4. */
    private static final float MAX_WORKING_SIGMA = 4f;
    /** The most a scaled blur reduces by. */
    private static final int MAX_SCALE = 4;
    /** Source texels a shrink averages per target texel and axis in one pass (image.compute's MAX_SHRINK_SPAN less 2). */
    private static final int MAX_SHRINK = 32;

    static {
        for (int f = 0; f < SCRATCH.length; f++) {
            CgTextureType type = IMAGE_TYPES.get(f);
            SCRATCH[f] = CgFrameBufferFormat.builder("cg_ops_" + type.name().toLowerCase()).color(0, type).build();
        }
    }

    private CgGpuOps() {}

    // ── Fills, sequences, copies ─────────────────────────────────────────────

    /** Sets the count's words of {@code buffer} to {@code value}. */
    public static void fill(CgComputePass pass, CgGraphBuffer buffer, int value, CgGpuCount count) {
        if (count.capacity() == 0) return;
        counted(pass.dispatch(Files.fill().kernel("Fill"), count.capacity()), count, buffer)
                .bind("DST", buffer).set("_Value", value);
    }

    /** Word i of {@code buffer} becomes {@code first + i * step}, for i below the count. */
    public static void iota(CgComputePass pass, CgGraphBuffer buffer, int first, int step, CgGpuCount count) {
        if (count.capacity() == 0) return;
        counted(pass.dispatch(Files.fill().kernel("Iota"), count.capacity()), count, buffer)
                .bind("DST", buffer).set("_Value", first).set("_Step", step);
    }

    /** Copies the count's words of {@code from} into {@code to}. */
    public static void copy(CgComputePass pass, CgGraphBuffer from, CgGraphBuffer to, CgGpuCount count) {
        distinct(from, to);
        if (count.capacity() == 0) return;
        counted(pass.dispatch(Files.fill().kernel("Copy"), count.capacity()), count, from)
                .bind("SRC", from).bind("DST", to);
    }

    /**
     * Writes a dispatch's three group counts at {@code word} of {@code args}: the groups of {@code groupSize} the count
     * needs, then 1, 1. What {@code CgComputePass.dispatchIndirect} reads; {@code args} needs
     * {@link CgBufferUsage#INDIRECT} for that.
     */
    public static void dispatchArgs(CgComputePass pass, CgGpuCount count, int groupSize, CgGraphBuffer args, int word) {
        if (groupSize <= 0) throw new IllegalArgumentException("a group of " + groupSize);
        counted(pass.dispatch(Files.fill().kernel("DispatchArgs"), 3), count, args)
                .bind("DST", args).set("_At", word).set("_Group", groupSize);
    }

    // ── Reduce, bounds, scan, compact ────────────────────────────────────────

    /** Word {@code word} of {@code result} becomes the fold of the count's elements: the fold's identity for none. */
    public static void reduce(CgComputePass pass, Fold fold, Element element, CgGraphBuffer values, CgGpuCount count,
                              CgGraphBuffer result, int word) {
        distinct(values, result);
        reduceInto(pass, count, fold, element, values, 1, 0, result, word);
    }

    /**
     * The bounding box of the count's points: x, y and z at word {@code offset} of each record of {@code stride} words,
     * as floats. Their minimum x, y, z then maximum x, y, z land at {@code word} of {@code out}; with no points, +inf
     * then -inf.
     *
     * <pre>{@code
     * CgGpuOps.bounds(pass, positions, 4, 0, alive, box, 0);   // vec4 positions
     * CgGpuOps.bounds(pass, particles, 8, 0, alive, box, 0);   // { vec4 positionLife; vec4 velocitySeed; }
     * }</pre>
     */
    public static void bounds(CgComputePass pass, CgGraphBuffer records, int stride, int offset, CgGpuCount count,
                              CgGraphBuffer out, int word) {
        if (stride < 3 || offset < 0 || offset + 3 > stride) {
            throw new IllegalArgumentException("xyz at word " + offset + " of " + stride + "-word records");
        }
        distinct(records, out);
        for (int c = 0; c < 3; c++) {
            reduceInto(pass, count, Fold.MIN, Element.FLOAT, records, stride, offset + c, out, word + c);
            reduceInto(pass, count, Fold.MAX, Element.FLOAT, records, stride, offset + c, out, word + 3 + c);
        }
    }

    /** Element i of {@code out} becomes the fold of {@code values} up to i: before it, or with it. */
    public static void scan(CgComputePass pass, Scan scan, Fold fold, Element element, CgGraphBuffer values,
                            CgGpuCount count, CgGraphBuffer out) {
        distinct(values, out);
        if (count.capacity() == 0) return;
        scanLevel(pass, count, fold, element, false, scan == Scan.INCLUSIVE, values, out, 0, count.capacity());
    }

    /**
     * The indices of the elements whose word of {@code flags} is not zero, in order, into {@code out} (or their words
     * of {@code values} when given) and how many at {@code word} of {@code outCount}.
     */
    public static void compact(CgComputePass pass, CgGraphBuffer flags, @Nullable CgGraphBuffer values, CgGpuCount count,
                               CgGraphBuffer out, CgGraphBuffer outCount, int word) {
        distinct(flags, out);
        if (values != null) distinct(values, out);
        if (count.capacity() == 0) {
            counted(pass.dispatch(scanKernel(COMPACT_COUNT, Fold.SUM, Element.UINT, false, false), 1), count, flags)
                    .bind("SRC", flags).bind("PREFIX", flags).bind("DST", outCount).set("_At", word);
            return;
        }
        CgGraphBuffer places = words(pass, "ops.compact.places", count.capacity());
        scanLevel(pass, count, Fold.SUM, Element.UINT, true, false, flags, places, 0, count.capacity());
        CgDispatch keep = values == null
                ? pass.dispatch(scanKernel(COMPACT_INDICES, Fold.SUM, Element.UINT, false, false), count.capacity())
                : pass.dispatch(scanKernel(COMPACT_VALUES, Fold.SUM, Element.UINT, false, false), count.capacity());
        counted(keep, count, flags).bind("SRC", flags).bind("PREFIX", places).bind("DST", out);
        if (values != null) keep.bind("VALUES", values);
        counted(pass.dispatch(scanKernel(COMPACT_COUNT, Fold.SUM, Element.UINT, false, false), 1), count, flags)
                .bind("SRC", flags).bind("PREFIX", places).bind("DST", outCount).set("_At", word);
    }

    /**
     * Rows expanded into elements: row r owns the next word r of {@code lengths} elements of {@code out}, and each
     * learns its row and its index in that row, a {@code uvec2}. How many elements that makes lands at {@code word} of
     * {@code total}. A kernel claiming k slots per source writes k, expands, and a map fills slot i of source s.
     *
     * <pre>{@code
     * // Each emitter spawns lengths[e] sparks this frame; spark j learns its emitter and which of its spawns it is.
     * CgGpuOps.expand(pass, lengths, CgGpuCount.at(counts, 0, EMITTERS), spawns, counts, 1);
     * CgGpuCount spawned = CgGpuCount.at(counts, 1, MAX_SPAWNS);
     * pass.dispatch(spawn, MAX_SPAWNS).bind("SPAWNS", spawns).bind("COUNT", counts).set("_CountAt", 1);
     * // spawn.compute: uvec2 s = SPAWNS(CG_ELEMENT);   s.x the emitter, s.y its spawn index
     * }</pre>
     *
     * <ul>
     *   <li>{@code out} holds {@code out.size() / 8} elements and none past them is written, but {@code total} counts
     *       every one: read it as {@code CgGpuCount.at(total, word, out.size() / 8)}, which clamps.</li>
     *   <li>{@code total} may be the rows' count's own buffer, at another word.</li>
     * </ul>
     */
    public static void expand(CgComputePass pass, CgGraphBuffer lengths, CgGpuCount rows, CgGraphBuffer out,
                              CgGraphBuffer total, int word) {
        distinct(lengths, out);
        distinct(lengths, total);
        distinct(out, total);
        CgGraphBuffer starts = words(pass, "ops.expand.starts", rows.capacity());
        scan(pass, Scan.EXCLUSIVE, Fold.SUM, Element.UINT, lengths, rows, starts);
        counted(pass.dispatch(Files.expand().kernel("ExpandTotal"), 1), rows, lengths)
                .bind("LENGTHS", lengths).bind("STARTS", starts).bind("TOTAL", total).set("_TotalAt", word);
        int elements = (int) Math.min(Integer.MAX_VALUE, out.size() / 8);
        if (elements == 0) return;
        counted(pass.dispatch(Files.expand().kernel("Expand"), elements), rows, starts).bind("STARTS", starts)
                .bind("TOTAL", total).bind("OUT", out).set("_TotalAt", word).set("_Elements", elements);
    }

    /** Level by level into scratch, then the last level's fold at {@code word}. Level 0 reads a word of each record. */
    private static void reduceInto(CgComputePass pass, CgGpuCount count, Fold fold, Element element,
                                   CgGraphBuffer values, int stride, int offset, CgGraphBuffer result, int word) {
        CgGraphBuffer src = values;
        int level = 0;
        for (int n = count.capacity(); n > BLOCK; n = (n + BLOCK - 1) / BLOCK, level++) {
            int above = (n + BLOCK - 1) / BLOCK;
            CgGraphBuffer sums = words(pass, SUMS[level], above);
            CgDispatch step = counted(pass.dispatch(scanKernel(REDUCE_STEP, fold, element, false, false), above), count, src)
                    .bind("SRC", src).bind("DST", sums).set("_Level", level);
            if (level == 0) step.set("_Stride", stride).set("_Offset", offset);
            src = sums;
        }
        CgDispatch last = counted(pass.dispatch(scanKernel(REDUCE_LAST, fold, element, false, false), 1), count, src)
                .bind("SRC", src).bind("DST", result).set("_Level", level).set("_At", word);
        if (level == 0) last.set("_Stride", stride).set("_Offset", offset);
    }

    /**
     * Scans level {@code level} ({@code n} elements at most) of {@code src} into {@code dst}: above sixteen, the block
     * sums are scanned a level up first, and each block starts from its prefix there.
     */
    private static void scanLevel(CgComputePass pass, CgGpuCount count, Fold fold, Element element, boolean flags,
                                  boolean inclusive, CgGraphBuffer src, CgGraphBuffer dst, int level, int n) {
        CgGraphBuffer prefixes = null;
        if (n > BLOCK) {
            int above = (n + BLOCK - 1) / BLOCK;
            CgGraphBuffer sums = words(pass, SUMS[level], above);
            prefixes = words(pass, PREFIXES[level], above);
            counted(pass.dispatch(scanKernel(REDUCE_STEP, fold, element, flags, false), above), count, src)
                    .bind("SRC", src).bind("DST", sums).set("_Level", level);
            scanLevel(pass, count, fold, element, false, false, sums, prefixes, level + 1, above);
        }
        // Without a level above, PREFIX is unread and bound all the same.
        counted(pass.dispatch(scanKernel(SCAN_BLOCK, fold, element, flags, inclusive), n), count, src)
                .bind("SRC", src).bind("PREFIX", prefixes != null ? prefixes : src).bind("DST", dst)
                .set("_Level", level).set("_HasPrefix", prefixes != null ? 1 : 0);
    }

    // ── Sort, histogram ──────────────────────────────────────────────────────

    /**
     * Sorts the count's keys, read as {@code element}s, and moves the words of {@code values} with them when given.
     * Stable: equal keys keep their order.
     *
     * <pre>{@code
     * CgGpuOps.sort(pass, Element.FLOAT, Order.DESCENDING, depths, indices, alive);   // back to front
     * CgGpuOps.sort(pass, Element.UINT, Order.ASCENDING, ids, null, CgGpuCount.of(n));
     * }</pre>
     */
    public static void sort(CgComputePass pass, Element element, Order order, CgGraphBuffer keys,
                            @Nullable CgGraphBuffer values, CgGpuCount count) {
        radixSort(pass, element, order, 32, keys, values, count);
    }

    /**
     * Sorts uint keys by their low {@code bits} bits alone, a pass per four: a 12-bit cell id sorts in three passes
     * rather than eight. The bits above stay in the keys and play no part in the order.
     */
    public static void sort(CgComputePass pass, int bits, Order order, CgGraphBuffer keys, @Nullable CgGraphBuffer values,
                            CgGpuCount count) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("a key of " + bits + " bits");
        radixSort(pass, Element.UINT, order, bits, keys, values, count);
    }

    /**
     * Word b of {@code bins}, for b below {@code binCount}, becomes how many of the count's keys fall in bin b:
     * {@code min(key >>> shift, binCount - 1)}. Exact to 2^24 a bin on every tier.
     */
    /** {@link #histogram}'s last bin count, and the radix sort's cells. A recording on another thread at worst makes one more. */
    private static volatile CgGpuCount histogramBins;

    public static void histogram(CgComputePass pass, CgGraphBuffer keys, CgGpuCount count, CgGraphBuffer bins,
                                 int binCount, int shift) {
        if (shift < 0 || shift > 31) throw new IllegalArgumentException("a shift of " + shift);
        if (binCount < 1) throw new IllegalArgumentException(binCount + " bins");
        distinct(keys, bins);
        CgGpuCount cleared = histogramBins;   // the last bin count's: made again only when it changes
        if (cleared == null || cleared.capacity() != binCount) histogramBins = cleared = CgGpuCount.of(binCount);
        fill(pass, bins, 0, cleared);
        int capacity = count.capacity();
        if (capacity == 0) return;
        CgKernel grouped = Files.histogram().kernel("HistogramGroups");
        CgDispatch dispatch;
        if (binCount <= GROUP_BINS) {
            int groups = Math.min(HISTOGRAM_GROUPS, (capacity + HISTOGRAM_GROUP_KEYS - 1) / HISTOGRAM_GROUP_KEYS);
            dispatch = pass.dispatchGroups(grouped, groups, 1, 1).set("_Groups", groups);
        } else {
            dispatch = pass.dispatch(Files.histogram().kernel("Histogram"), capacity);
        }
        counted(dispatch, count, keys).bind("KEYS", keys).bind("BINS", bins).set("_Shift", shift).set("_Bins", binCount);
    }

    // ── Warming ──────────────────────────────────────────────────────────────

    /**
     * Starts the programs a sort of {@code element} keys in {@code order} dispatches, ahead of its first use, so that
     * frame does not build them. Render thread.
     *
     * <pre>{@code
     * CgGpuOps.precacheSort(Element.UINT, Order.ASCENDING);       // on a loading screen, or as the effect is made
     * CgGpuOps.precacheScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
     * CgGpuOps.precacheHistogram();
     * }</pre>
     *
     * A sort by bits ({@link #sort(CgComputePass, int, Order, CgGraphBuffer, CgGraphBuffer, CgGpuCount)}) is
     * {@code Element.UINT}'s.
     */
    public static void precacheSort(Element element, Order order) {
        for (int k = 0; k < SORT_KERNELS.length; k++) precache(sortKernel(k, element, order));
        precache(Files.fill().kernel("Copy"));
    }

    /** {@link #precacheSort}, for a scan. */
    public static void precacheScan(Scan scan, Fold fold, Element element) {
        precache(scanKernel(REDUCE_STEP, fold, element, false, false));
        precache(scanKernel(SCAN_BLOCK, fold, element, false, scan == Scan.INCLUSIVE));
    }

    /** {@link #precacheSort}, for {@link #fill}, {@link #iota} and {@link #copy}. */
    public static void precacheFill() {
        precache(Files.fill().kernel("Fill"));
        precache(Files.fill().kernel("Iota"));
        precache(Files.fill().kernel("Copy"));
    }

    /** {@link #precacheSort}, for a histogram. */
    public static void precacheHistogram() {
        precache(Files.fill().kernel("Fill"));
        precache(Files.histogram().kernel("HistogramGroups"));
        precache(Files.histogram().kernel("Histogram"));
    }

    /**
     * {@link #precacheSort}, for the kernels writing every indirect draw's command: what a draw's {@code .indirect} or
     * {@code .instances} costs at its first frame otherwise: a pass's commands are one {@code DrawArgsMany} dispatch.
     */
    public static void precacheIndirect() {
        precache(CgCompute.load(INDIRECT_ARGS).kernel("DrawArgsMany"));
    }

    /** {@link #precacheSort}, for {@link #cull}; with {@code ordered}, its {@link CgCull#ordered} form too. */
    public static void precacheCull(boolean ordered) {
        precacheIndirect();
        precache(Files.fill().kernel("FillAt"));
        precache(Files.cull().kernel("Cull"));
        precache(Files.cull().kernel("CullKey"));
        precache(Files.cull().kernel("CullGather"));
        precacheSort(Element.UINT, Order.ASCENDING);
        precacheHistogram();
        precacheScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
        if (!ordered) return;
        precache(Files.cull().kernel("CullFlags"));
        precache(Files.cull().kernel("CullPlace"));
        for (int k : new int[]{REDUCE_STEP, SCAN_BLOCK, COMPACT_INDICES, COMPACT_COUNT}) {
            precache(scanKernel(k, Fold.SUM, Element.UINT, k == REDUCE_STEP || k == SCAN_BLOCK, false));
        }
        precacheScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
    }

    /** {@link #precacheSort}, for {@link #depthPyramid}: its two kernels. */
    public static void precacheDepthPyramid() {
        precache(Files.pyramid().kernel("Seed"));
        precache(Files.pyramid().kernel("Reduce"));
    }

    /** {@link #precacheSort}, for {@link #blur} and {@link #blurScaled} over images of {@code type}. */
    public static void precacheBlur(CgTextureType type) {
        int f = IMAGE_TYPES.indexOf(type);
        if (f < 0) throw new IllegalArgumentException(type + ": the image ops take " + IMAGE_TYPES);
        for (int at = 0; at < 4; at++) precache(blurKernel(f, (at & 1) != 0, (at & 2) != 0));
        precache(reduceKernel(f));
    }

    /** {@link #precacheSort}, for {@link #shrink} over images of {@code type}. */
    public static void precacheShrink(CgTextureType type) {
        int f = IMAGE_TYPES.indexOf(type);
        if (f < 0) throw new IllegalArgumentException(type + ": the image ops take " + IMAGE_TYPES);
        precache(shrinkKernel(f));
    }

    private static void precache(CgKernel kernel) {
        kernel.precache();
    }

    // ── Culling ──────────────────────────────────────────────────────────────

    /**
     * Culls the count's instances of one mesh as {@code CgWorldRenderer} culls a draw: each record of
     * {@code instances} ({@code CgInstanceKind.OBJECT}'s layout, in the set's own space) placed by {@code cull}, its
     * box tested against the view's frustum, given the level its screen height picks, and, with a pyramid, tested
     * against the scene's depth. Each one kept is written into {@code out} as the record a draw reads: level l's from
     * record {@link #cullFirst}{@code (l, capacity)}, as many as word {@code word + l} of {@code counts} says.
     *
     * <pre>{@code
     * CgGraphBuffer visible = CgGraphBuffer.transientBuffer("rocks.visible",
     *         CgBufferDesc.elements(CgGpuOps.cullRecords(cull, n), CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE));
     * CgGpuOps.cull(pass, cull, rocks, CgGpuCount.of(n), visible, counts, 0);
     * pass.end();
     * for (int l = 0; l < cull.levels(); l++) {
     *     chunks.draw(pipeline, bindings, lods.level(l)).objects(visible, CgGpuOps.cullFirst(l, n), n)
     *           .indirect(counts, l * 4L, CgIndirect.INSTANCES, 1);
     * }
     * }</pre>
     *
     * <ul>
     *   <li>Kept records keep the order they were in on every tier but compute, where they come in any order unless
     *       the cull is {@link CgCull#ordered}.</li>
     *   <li>{@code counts} needs {@link CgBufferUsage#STORAGE} and the draws' {@code INDIRECT} use; the op zeroes its
     *       words first.</li>
     *   <li>The box is tested as it stands under the view: an instance whose shader moves its vertices states the
     *       box they stay in, with {@link CgCull#box} or {@link CgCull#pad}.</li>
     * </ul>
     */
    public static void cull(CgComputePass pass, CgCull cull, CgGraphBuffer instances, CgGpuCount count,
                            CgGraphBuffer out, CgGraphBuffer counts, int word) {
        cull(pass, cull, instances, 0, count, out, counts, word);
    }

    /**
     * {@link #cull(CgComputePass, CgCull, CgGraphBuffer, CgGpuCount, CgGraphBuffer, CgGraphBuffer, int)} of the records
     * of {@code instances} from record {@code first}: several sets sharing one buffer, each its own range.
     *
     * <pre>{@code
     * CgGpuOps.cull(pass, cull, pool, firstOfThisSlot, CgGpuCount.of(slotSize), visible, counts, 0);
     * }</pre>
     */
    public static void cull(CgComputePass pass, CgCull cull, CgGraphBuffer instances, int first, CgGpuCount count,
                            CgGraphBuffer out, CgGraphBuffer counts, int word) {
        cull(pass, cull, instances, first, count, out, 0, counts, word);
    }

    /**
     * {@link #cull(CgComputePass, CgCull, CgGraphBuffer, int, CgGpuCount, CgGraphBuffer, CgGraphBuffer, int)} into
     * {@code out} from record {@code outFirst}: several culls sharing one output, so their draws read one buffer and
     * join into a multi-draw. Level l's records start at {@code outFirst + cullFirst(l, capacity)}.
     *
     * <pre>{@code
     * CgGpuOps.cull(pass, rocks, rockRecords, 0, CgGpuCount.of(n), visible, 0, counts, 0);
     * CgGpuOps.cull(pass, trees, treeRecords, 0, CgGpuCount.of(m), visible, CgGpuOps.cullRecords(rocks, n), counts, 8);
     * }</pre>
     *
     * {@code outFirst} is a multiple of 4, as every {@link #cullRecords} is: a binding offset is a whole 256 bytes.
     */
    public static void cull(CgComputePass pass, CgCull cull, CgGraphBuffer instances, int first, CgGpuCount count,
                            CgGraphBuffer out, int outFirst, CgGraphBuffer counts, int word) {
        distinct(instances, out);
        if (outFirst < 0 || (outFirst & 3) != 0) throw new IllegalArgumentException("output from record " + outFirst);
        int capacity = count.capacity(), levels = cull.levels();
        if (first < 0 || (long) (first + capacity) * RECORD_BYTES > instances.size()) {
            throw new IllegalArgumentException("records " + first + " to " + (first + capacity) + " of " + instances
                    + ", which holds " + instances.size() / RECORD_BYTES);
        }
        long region = (long) cullFirst(1, capacity) * RECORD_BYTES, base = (long) outFirst * RECORD_BYTES;
        if (out.size() < base + levels * region) {
            throw new IllegalArgumentException(out + " holds " + out.size() + " bytes; a cull of " + capacity
                    + " instances at " + levels + " levels from record " + outFirst + " writes to byte "
                    + (base + levels * region) + ": size it by cullRecords");
        }
        counted(pass.dispatch(Files.fill().kernel("FillAt"), levels), CgGpuCount.of(levels), counts)
                .bind("DST", counts).set("_At", word).set("_Value", 0);
        if (capacity == 0) return;
        if (cull.ordered) {
            cullOrdered(pass, cull, instances, first, count, out, counts, word, base, region);
            return;
        }
        CgKernel kernel = Files.cull().kernel("Cull");
        for (int l = 0; l < levels; l++) {
            CgDispatch dispatch = counted(pass.dispatch(kernel, capacity), count, instances)
                    .bind("INSTANCES", instances).bind("OUT", out, base + l * region, region)
                    .counter("OUT", counts, (word + l) * 4L).set("_Level", l).set("_First", first);
            cull.apply(dispatch);
        }
    }

    /** Each level's instances flagged, listed in order by {@link #compact}, and their records written at their place. */
    private static void cullOrdered(CgComputePass pass, CgCull cull, CgGraphBuffer instances, int first, CgGpuCount count,
                                    CgGraphBuffer out, CgGraphBuffer counts, int word, long base, long region) {
        int capacity = count.capacity();
        CgGraphBuffer flags = words(pass, "ops.cull.flags", capacity), kept = words(pass, "ops.cull.kept", capacity);
        CgKernel flag = Files.cull().kernel("CullFlags"), place = Files.cull().kernel("CullPlace");
        for (int l = 0; l < cull.levels(); l++) {
            CgDispatch flagging = counted(pass.dispatch(flag, capacity), count, instances)
                    .bind("INSTANCES", instances).bind("FLAGS", flags).set("_Level", l).set("_First", first);
            cull.apply(flagging);
            compact(pass, flags, null, count, kept, counts, word + l);
            CgDispatch placing = counted(pass.dispatch(place, capacity), CgGpuCount.at(counts, word + l, capacity), kept)
                    .bind("INSTANCES", instances).bind("KEPT", kept).bind("PLACED", out, base + l * region, region)
                    .set("_First", first);
            cull.apply(placing);
        }
    }

    /**
     * Every set of {@code sets} culled at once, under {@code view}'s view and pyramid, each as {@link #cull} culls one:
     * set s's level l kept records start at record {@code sets.first(s) + cullFirst(l, capacity)} of {@code out}, as
     * many as word {@code sets.word(s) + l} of {@code counts} says. Two dispatches per instance buffer the sets read,
     * and one sort, histogram and scan over them all, however many sets.
     *
     * <pre>{@code
     * sets.clear();
     * int rocks = sets.add(cull.mesh(rockLods).place(rockPlace), rockRecords, 0, CgGpuCount.of(n));
     * int sparks = sets.add(cull.mesh(spark).place(sparkPlace).scale(0.2f), particles, base, CgGpuCount.at(visible, 3, m));
     * CgGpuOps.cull(pass, cull.view(view, projection).pyramid(depth), sets, culled, counts);   // sized by sets.records(), sets.words()
     * }</pre>
     *
     * <ul>
     *   <li>Each level's records keep the order their set holds them in, on every tier.</li>
     *   <li>{@code counts} needs {@code INDIRECT} for the draws; its last word counts what no level kept.</li>
     * </ul>
     */
    public static void cull(CgComputePass pass, CgCull view, CgCullSets sets, CgGraphBuffer out, CgGraphBuffer counts) {
        int n = sets.size();
        if (n == 0) return;
        if (out.size() < (long) sets.records() * RECORD_BYTES) {
            throw new IllegalArgumentException(out + " holds " + out.size() + " bytes; " + n + " sets write "
                    + (long) sets.records() * RECORD_BYTES + ": size it by sets.records()");
        }
        if (counts.size() < sets.words() * 4L) {
            throw new IllegalArgumentException(counts + " holds " + counts.size() / 4 + " words; " + n + " sets need "
                    + sets.words() + ": size it by sets.words()");
        }
        int keys = sets.layout(KEY_ALIGN), culled = n * CgCull.MAX_LEVELS;
        CgGraphBuffer rows = sets.rowsBuffer();
        pass.recording().update(rows, 0, sets.rows());
        CgGraphBuffer keyBuffer = words(pass, "ops.cull.keys", keys), indices = words(pass, "ops.cull.indices", keys);
        CgGraphBuffer starts = words(pass, "ops.cull.starts", culled + 1);
        CgKernel key = Files.cull().kernel("CullKey"), gather = Files.cull().kernel("CullGather");
        for (int g = 0; g < sets.groups(); g++) {
            CgGraphBuffer instances = sets.groupInstances(g), count = sets.groupCounts(g);
            distinct(instances, out);
            int span = sets.laid(g, 3), first = sets.laid(g, 2);
            if (span == 0) continue;
            CgDispatch keyed = pass.dispatch(key, span).bind("SETS", rows).bind("INSTANCES", instances)
                    .bind("COUNT", count != null ? count : rows)
                    .bind("KEYS", keyBuffer, first * 4L, span * 4L).bind("INDICES", indices, first * 4L, span * 4L)
                    .set("_SetFirst", sets.laid(g, 0)).set("_Sets", sets.laid(g, 1)).set("_Culled", culled);
            view.applyView(keyed);
        }
        CgGpuCount all = CgGpuCount.of(keys);
        sort(pass, 32 - Integer.numberOfLeadingZeros(culled), Order.ASCENDING, keyBuffer, indices, all);
        histogram(pass, keyBuffer, all, counts, culled + 1, 0);
        scan(pass, Scan.EXCLUSIVE, Fold.SUM, Element.UINT, counts, CgGpuCount.of(culled + 1), starts);
        for (int g = 0; g < sets.groups(); g++) {
            int records = sets.laid(g, 5);
            if (records == 0) continue;
            long first = (long) sets.laid(g, 4) * RECORD_BYTES;
            pass.dispatch(gather, records).bind("SETS", rows).bind("INSTANCES", sets.groupInstances(g))
                    .bind("COUNTS", counts).bind("STARTS", starts).bind("SORTED", indices)
                    .bind("PLACED", out, first, (long) records * RECORD_BYTES)
                    .set("_SetFirst", sets.laid(g, 0)).set("_Sets", sets.laid(g, 1));
        }
    }

    /** Where level {@code level}'s kept records start in a cull's output, of {@code capacity} instances. */
    public static int cullFirst(int level, int capacity) {
        return level * ((capacity + 3) & ~3);   // a whole number of 256-byte binding offsets
    }

    /** The records a cull's output holds: a level's region for each of its levels. */
    public static int cullRecords(CgCull cull, int capacity) {
        return cullFirst(cull.levels(), capacity);
    }

    /** An object record's bytes: the stride of a cull's instances and its output. */
    public static int cullRecordBytes() {
        return RECORD_BYTES;
    }

    /**
     * The scene's depth as {@link #cull} reads it: level 0 the eye depth of each texel of {@code depthOf}'s depth
     * under {@code constants}' projection and depth convention, each level after it the farthest of what it covers.
     * {@code pyramid} is {@link #PYRAMID_FORMAT} with mips, the size of {@code depthOf}. Recorded where it stands: after
     * whatever should hide the instances has drawn. One compute pass, a dispatch per six levels: two up to 4096 texels a
     * side ({@code depth_pyramid.compute}, SPD's tile through shared memory).
     *
     * <pre>{@code
     * CgGraphTexture depth = CgGraphTexture.transientTexture("hiz",
     *         new CgTextureDesc(w, h, CgGpuOps.PYRAMID_FORMAT).withMips());
     * CgGpuOps.depthPyramid(recording, CgGraphTexture.current(), constants, depth);
     * cull.pyramid(depth);
     * }</pre>
     */
    public static void depthPyramid(CgRecording recording, CgGraphTexture depthOf, CgPassConstants constants,
                                    CgGraphTexture pyramid) {
        if (pyramid.getLevels() < 2) throw new IllegalArgumentException(pyramid + " has one level: describe it withMips()");
        int w = pyramid.getWidth(), h = pyramid.getHeight(), last = pyramid.getLevels() - 1;
        CgComputePass pass = recording.compute("cg.depthPyramid", constants).sceneDepth(depthOf).timed(GPU_PYRAMID);
        for (int from = 0; from < last; from += PYRAMID_BATCH) {
            int made = Math.min(PYRAMID_BATCH, last - from);
            int gx = (size(w, from) + PYRAMID_TILE - 1) / PYRAMID_TILE, gy = (size(h, from) + PYRAMID_TILE - 1) / PYRAMID_TILE;
            CgDispatch d = pass.dispatchGroups(Files.pyramid().kernel(from == 0 ? "Seed" : "Reduce"), gx, gy, 1)
                    .set("_Levels", made);
            if (from == 0) d.image(PYRAMID_OUTS[0], pyramid, 0, -1);
            else d.image("SRC", pyramid, from, -1);
            for (int k = 1; k <= PYRAMID_BATCH; k++) {
                if (k <= made) d.image(PYRAMID_OUTS[k], pyramid, from + k, -1);
                else d.image(PYRAMID_OUTS[k], PYRAMID_UNUSED, 0, -1);
            }
        }
        pass.end();
    }

    /**
     * Least significant digit first, four bits a pass, ping-ponging through scratch; an odd number of passes ends in
     * scratch and is copied back. Stable. FidelityFX Parallel Sort's: each work group counts the digits of its run of
     * 512-key blocks, the counts are scanned digit-major in two levels, and each group sorts every 128 of its keys by
     * the digit in shared memory before writing them out.
     */
    private static void radixSort(CgComputePass pass, Element element, Order order, int bits, CgGraphBuffer keys,
                                  @Nullable CgGraphBuffer values, CgGpuCount count) {
        if (values != null) distinct(keys, values);
        int capacity = count.capacity();
        if (capacity <= 1) return;
        int blocks = (capacity + SORT_BLOCK - 1) / SORT_BLOCK;
        int groups = Math.min(blocks, SORT_GROUPS), reduce = (groups + SORT_BLOCK - 1) / SORT_BLOCK;
        int passes = (bits + 3) / 4;
        CgGraphBuffer digits = words(pass, "ops.sort.digits", 16 * groups);
        CgGraphBuffer reduced = words(pass, "ops.sort.reduced", 16 * reduce);
        CgGraphBuffer keysIn = keys, keysOut = words(pass, "ops.sort.keys", capacity);
        CgGraphBuffer valuesIn = values, valuesOut = values == null ? null : words(pass, "ops.sort.values", capacity);
        for (int p = 0; p < passes; p++) {
            groupRuns(counted(pass.dispatchGroups(sortKernel(SORT_COUNT, element, order), groups, 1, 1), count,
                    keysIn), blocks, groups).bind("KEYS", keysIn).bind("TABLE", digits).set("_Shift", 4 * p);
            scanGroups(pass, digits, reduced, groups, reduce);
            CgDispatch scatter = groupRuns(counted(pass.dispatchGroups(sortKernel(valuesIn == null ? SORT_SCATTER_KEYS
                    : SORT_SCATTER, element, order), groups, 1, 1), count, keysIn), blocks, groups)
                    .bind("TABLE", digits);
            scatter.bind("KEYS", keysIn).bind("KEYS_OUT", keysOut).set("_Shift", 4 * p);
            if (valuesIn != null) scatter.bind("VALUES_IN", valuesIn).bind("VALUES_OUT", valuesOut);
            CgGraphBuffer k = keysIn;
            keysIn = keysOut;
            keysOut = k;
            CgGraphBuffer v = valuesIn;
            valuesIn = valuesOut;
            valuesOut = v;
        }
        if (passes % 2 == 1) {
            copy(pass, keysIn, keys, count);
            if (values != null) copy(pass, valuesIn, values, count);
        }
    }

    /** How the sort shares {@code blocks} blocks of 512 among {@code groups} work groups. */
    private static CgDispatch groupRuns(CgDispatch dispatch, int blocks, int groups) {
        return dispatch.set("_Groups", groups).set("_GroupBlocks", blocks / groups).set("_ExtraGroups", blocks % groups);
    }

    /**
     * The sort's counts, scanned digit-major in place: each digit's groups summed per 512, those sums
     * scanned by one work group, then each run of 512 scanned from its sum's prefix.
     */
    private static void scanGroups(CgComputePass pass, CgGraphBuffer table, CgGraphBuffer reduced, int groups,
                                   int reduce) {
        pass.dispatchGroups(sortKernel(SORT_REDUCE, Element.UINT, Order.ASCENDING), 16 * reduce, 1, 1)
                .bind("TABLE", table).bind("REDUCED", reduced).set("_Groups", groups).set("_ReduceGroups", reduce);
        pass.dispatchGroups(sortKernel(SORT_SCAN, Element.UINT, Order.ASCENDING), 1, 1, 1)
                .bind("REDUCED", reduced).set("_ReduceGroups", reduce);
        pass.dispatchGroups(sortKernel(SORT_SCAN_ADD, Element.UINT, Order.ASCENDING), 16 * reduce, 1, 1)
                .bind("REDUCED", reduced).bind("TABLE", table).set("_Groups", groups).set("_ReduceGroups", reduce);
    }

    // ── Mip chains, blur ─────────────────────────────────────────────────────

    /**
     * Fills every level of {@code texture} below 0 from the one above it: each texel of level l + 1 folds the texels
     * of level l it covers, two by two, or up to three by three where an odd size halves.
     *
     * <pre>{@code
     * CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", new CgTextureDesc(w, h, HDR).withMips());
     * // ... level 0 drawn or written ...
     * CgGpuOps.downsample(pass, bloom, Filter.AVERAGE);      // levels 1 down to 1x1, and bloom samples trilinearly
     * CgGpuOps.downsample(pass, depthPyramid, Filter.MAX);   // each texel the farthest depth it covers
     * }</pre>
     *
     * <p>Throws for a texture of one level: describe it {@link CgTextureDesc#withMips()}.</p>
     */
    public static void downsample(CgComputePass pass, CgGraphTexture texture, Filter filter) {
        downsample(pass, texture, 0, texture.getLevels() - 1, filter);
    }

    /** Fills levels {@code from + 1} to {@code to} of {@code texture}, each from the one above it. */
    public static void downsample(CgComputePass pass, CgGraphTexture texture, int from, int to, Filter filter) {
        if (from < 0 || from >= to || to >= texture.getLevels()) {
            throw new IllegalArgumentException("levels " + from + " to " + to + " of " + texture + ", which has "
                    + texture.getLevels() + (texture.getLevels() == 1 ? ": describe it withMips()" : ""));
        }
        int f = imageType(texture);
        CgKernel kernel = downsampleKernel(f, filter);
        for (int l = from; l < to; l++) {
            pass.dispatch(kernel, size(texture.getWidth(), l + 1), size(texture.getHeight(), l + 1), 1)
                    .image(SOURCES[f], texture, l, -1).image(TARGETS[f], texture, l + 1, -1);
        }
    }

    /**
     * A Gaussian blur of {@code sigma} texels from level 0 of {@code source} into level 0 of {@code target}: across,
     * into scratch, then down. One texture for both blurs it in place.
     *
     * <pre>{@code
     * CgGpuOps.blur(pass, scene, glow, 4f);
     * CgGpuOps.blur(pass, glow, glow, 2f);                   // in place
     * }</pre>
     *
     * <p>A texel reads {@code 2 * ceil(3 * sigma) + 1} texels an axis, so a wide blur is cheaper at a smaller level:
     * downsample, then blur level l by {@code sigma / 2^l}.</p>
     */
    public static void blur(CgComputePass pass, CgGraphTexture source, CgGraphTexture target, float sigma) {
        blur(pass, source, 0, target, 0, sigma);
    }

    /**
     * A Gaussian blur from level {@code sourceLevel} of {@code source} into level {@code targetLevel} of
     * {@code target}, which must be its size and format.
     *
     * <pre>{@code
     * CgGpuOps.downsample(pass, bloom, Filter.AVERAGE);
     * CgGpuOps.blur(pass, bloom, 3, bloom, 3, 2f);           // a sixty-fourth of the texels: about a sigma of 16 at 0
     * }</pre>
     */
    public static void blur(CgComputePass pass, CgGraphTexture source, int sourceLevel, CgGraphTexture target,
                            int targetLevel, float sigma) {
        if (!(sigma > 0f) || Float.isInfinite(sigma)) throw new IllegalArgumentException("a sigma of " + sigma);
        int f = imageType(source);
        if (imageType(target) != f) throw new IllegalArgumentException(source + " and " + target + " differ in format");
        int w = size(source.getWidth(), sourceLevel), h = size(source.getHeight(), sourceLevel);
        if (sourceLevel < 0 || sourceLevel >= source.getLevels() || targetLevel < 0 || targetLevel >= target.getLevels()
                || size(target.getWidth(), targetLevel) != w || size(target.getHeight(), targetLevel) != h) {
            throw new IllegalArgumentException("level " + sourceLevel + " of " + source + " and level " + targetLevel
                    + " of " + target + " are not one size");
        }
        dispatchBlur(pass, f, source, sourceLevel, 0, 0, w, h, target, targetLevel, 0, 0, sigma, sigma, Edge.CLAMP, false);
    }

    /**
     * A Gaussian blur of the region {@code (x, y, w, h)} of level 0 of {@code source}, landing with its corner at
     * {@code (tx, ty)} of level 0 of {@code target} (its format; the same texture blurs in place). Each axis has its own
     * sigma, in texels, and taps past the region read by {@code edge}, never what lies outside it.
     *
     * <pre>{@code
     * CgGpuOps.blur(pass, capture, 0, h - band, w, band, capture, 0, h - band, 6f, 6f, CgGpuOps.Edge.MIRROR);  // a band
     * CgGpuOps.blur(pass, coverage, 0, 0, iw, ih, shadow, 0, 0, 3f, 1.5f, CgGpuOps.Edge.CLAMP);   // into a texture of its own
     * }</pre>
     *
     * <p>A sigma reaches three of itself; past {@link #MAX_BLUR_SIGMA} the kernel is cut short, still normalised. A wide
     * blur belongs at a reduced size ({@link #blurScaled}).</p>
     */
    public static void blur(CgComputePass pass, CgGraphTexture source, int x, int y, int w, int h,
                            CgGraphTexture target, int tx, int ty, float sigmaX, float sigmaY, Edge edge) {
        if (!(sigmaX > 0f) || !(sigmaY > 0f) || Float.isInfinite(sigmaX) || Float.isInfinite(sigmaY)) {
            throw new IllegalArgumentException("sigmas of " + sigmaX + " and " + sigmaY);
        }
        int f = imageType(source);
        if (imageType(target) != f) throw new IllegalArgumentException(source + " and " + target + " differ in format");
        requireRegion(source, x, y, w, h);
        requireRegion(target, tx, ty, w, h);
        dispatchBlur(pass, f, source, 0, x, y, w, h, target, 0, tx, ty, sigmaX, sigmaY, edge, false);
    }

    /**
     * Skia's scale-then-blur: the region {@code (x, y, w, h)} of {@code source} blurred by {@code sigma} source texels,
     * reduced first, box averaged, by however many times smaller {@code target} is: 1, 2 or 4, {@link #blurScale} the
     * one to pick. The result covers the aligned blocks over the region, at {@code (x / scale, y / scale)}, so one
     * normalised uv means the same place in both.
     *
     * <pre>{@code
     * CgGraphTexture blurred = ...;                       // source's size / CgGpuOps.blurScale(sigma), its format
     * CgGpuOps.blurScaled(pass, capture, 0, top, w, band, blurred, sigma, CgGpuOps.Edge.MIRROR);
     * }</pre>
     *
     * <ul>
     *   <li>Two taps are one bilinear fetch (Skia's linear-sampled kernel): half the reads of {@link #blur}, within the
     *       filter's weight precision (about 1/256) of the exact Gaussian. For a picture; {@link #blur} for a value.</li>
     *   <li>A reduced texel past the region's edge averages the edge's texels: nothing outside the region is read.</li>
     *   <li>Sigma at the reduced size is at most four texels: a blur past {@code 3 * 4} reduced texels is narrower than
     *       asked, still normalised.</li>
     * </ul>
     */
    public static void blurScaled(CgComputePass pass, CgGraphTexture source, int x, int y, int w, int h,
                                  CgGraphTexture target, float sigma, Edge edge) {
        blurScaled(pass, source, x, y, w, h, target, sigma, sigma, edge);
    }

    /**
     * As {@link #blurScaled(CgComputePass, CgGraphTexture, int, int, int, int, CgGraphTexture, float, Edge)}, with a
     * sigma per axis; 0 on one leaves that axis sharp, so a blur along one direction costs one pass.
     *
     * <pre>{@code
     * CgGpuOps.blurScaled(pass, capture, 0, top, w, band, blurred, 0f, 6f, CgGpuOps.Edge.CLAMP);   // vertical only
     * }</pre>
     */
    public static void blurScaled(CgComputePass pass, CgGraphTexture source, int x, int y, int w, int h,
                                  CgGraphTexture target, float sigmaX, float sigmaY, Edge edge) {
        float sigma = Math.max(sigmaX, sigmaY);
        if (!(sigma > 0f) || Float.isInfinite(sigma) || sigmaX < 0f || sigmaY < 0f) {
            throw new IllegalArgumentException("a sigma of " + sigmaX + " by " + sigmaY);
        }
        int sw = source.getWidth(), sh = source.getHeight();
        int scale = 0;
        for (int s = 1; s <= MAX_SCALE && scale == 0; s *= 2) {
            if (target.getWidth() == (sw + s - 1) / s && target.getHeight() == (sh + s - 1) / s) scale = s;
        }
        if (scale == 0) throw new IllegalArgumentException(target + " is not " + source + " divided by 1, 2 or 4");
        if (scale == 1) {
            int f = imageType(source);
            if (imageType(target) != f) throw new IllegalArgumentException(source + " and " + target + " differ in format");
            requireRegion(source, x, y, w, h);
            dispatchBlur(pass, f, source, 0, x, y, w, h, target, 0, x, y, sigmaX, sigmaY, edge, true);
            return;
        }
        int f = imageType(source);
        if (imageType(target) != f) throw new IllegalArgumentException(source + " and " + target + " differ in format");
        requireRegion(source, x, y, w, h);
        int tx0 = x / scale, ty0 = y / scale;
        int tw = (x + w + scale - 1) / scale - tx0, th = (y + h + scale - 1) / scale - ty0;
        pass.dispatch(reduceKernel(f), tw, th, 1).texture("_Src", source).image(TARGETS[f], target)
                .set("_Region", tx0, ty0, tx0 + tw, ty0 + th).set("_Source", x, y, x + w, y + h).set("_Scale", scale);
        dispatchBlur(pass, f, target, 0, tx0, ty0, tw, th, target, 0, tx0, ty0,
                Math.min(sigmaX / scale, MAX_WORKING_SIGMA), Math.min(sigmaY / scale, MAX_WORKING_SIGMA), edge, true);
    }

    /** The reduction {@link #blurScaled} wants for {@code sigma}: 1, 2 or 4, keeping the reduced sigma at most 4. */
    public static int blurScale(float sigma) {
        int scale = 1;
        while (sigma / scale > MAX_WORKING_SIGMA && scale < MAX_SCALE) scale *= 2;
        return scale;
    }

    /**
     * The region {@code (x, y, w, h)} of level 0 of {@code source}, in texels and fractional, shrunk into the rect
     * {@code (tx, ty, tw, th)} of level 0 of {@code target} (its format): each target texel the average of the source
     * area it covers, a texel the area's edge cuts weighted by the part inside. An area resample, Pillow's {@code BOX}
     * and OpenCV's {@code INTER_AREA}: at any ratio, nothing skipped and nothing read twice. A thumbnail, a preview, a
     * picture fitted into a smaller box.
     *
     * <pre>{@code
     * CgGpuOps.shrink(pass, window, 0, 0, 1920, 1080, thumbnail, 0, 0, 192, 108);       // a tenth, every texel averaged
     * CgGpuOps.shrink(pass, page, 12.5f, 40f, 300.25f, 200f, preview, 0, 0, 96, 64);   // a box of a page, at its edges
     * }</pre>
     *
     * <ul>
     *   <li>Rows are the texture's own, as every image op's: row 0 at the bottom of a framebuffer's colour.</li>
     *   <li>Nothing outside the region is read. The region is no smaller than the rect: it shrinks.</li>
     *   <li>Past {@code 32} source texels a target texel on an axis it runs twice, through scratch, up to 1024.</li>
     * </ul>
     */
    public static void shrink(CgComputePass pass, CgGraphTexture source, float x, float y, float w, float h,
                              CgGraphTexture target, int tx, int ty, int tw, int th) {
        int f = imageType(source);
        if (imageType(target) != f) throw new IllegalArgumentException(source + " and " + target + " differ in format");
        requireRegion(target, tx, ty, tw, th);
        if (!(x >= 0f) || !(y >= 0f) || !(x + w <= source.getWidth()) || !(y + h <= source.getHeight())
                || !(w >= tw) || !(h >= th)) {
            throw new IllegalArgumentException("region " + x + "," + y + " " + w + "x" + h + " of " + source
                    + " shrunk to " + tw + "x" + th + ": it must lie inside it and be no smaller");
        }
        if (w > (float) tw * MAX_SHRINK * MAX_SHRINK || h > (float) th * MAX_SHRINK * MAX_SHRINK) {
            throw new IllegalArgumentException(w + "x" + h + " shrunk to " + tw + "x" + th + ": past "
                    + MAX_SHRINK * MAX_SHRINK + " times on an axis");
        }
        if (w > (float) tw * MAX_SHRINK || h > (float) th * MAX_SHRINK) {
            int mw = Math.max(tw, (int) Math.ceil(w / MAX_SHRINK)), mh = Math.max(th, (int) Math.ceil(h / MAX_SHRINK));
            CgGraphTexture mid = pass.recording().scratch("ops.shrink", scratchSize(mw), scratchSize(mh), SCRATCH[f]);
            dispatchShrink(pass, f, source, x, y, w, h, mid, 0, 0, mw, mh);
            source = mid;
            x = 0f;
            y = 0f;
            w = mw;
            h = mh;
        }
        dispatchShrink(pass, f, source, x, y, w, h, target, tx, ty, tw, th);
    }

    private static void dispatchShrink(CgComputePass pass, int f, CgGraphTexture source, float x, float y, float w,
                                       float h, CgGraphTexture target, int tx, int ty, int tw, int th) {
        pass.dispatch(shrinkKernel(f), tw, th, 1).texture("_Src", source).image(TARGETS[f], target)
                .set("_Region", tx, ty, tx + tw, ty + th).set("_Source", x, y, x + w, y + h);
    }

    private static void requireRegion(CgGraphTexture texture, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > texture.getWidth() || y + h > texture.getHeight()) {
            throw new IllegalArgumentException("region " + x + "," + y + " " + w + "x" + h + " outside " + texture);
        }
    }

    /** Across into a scratch the region's size, then down into place; taps kept inside the region by {@code edge}. */
    private static void dispatchBlur(CgComputePass pass, int f, CgGraphTexture source, int sourceLevel,
                                     int x, int y, int w, int h, CgGraphTexture target, int targetLevel,
                                     int tx, int ty, float sigmaX, float sigmaY, Edge edge, boolean linear) {
        int mirror = edge == Edge.MIRROR ? 1 : 0;
        CgGraphTexture across = pass.recording().scratch("ops.blur", scratchSize(w), scratchSize(h), SCRATCH[f]);
        pass.dispatch(blurKernel(f, false, linear), w, h, 1).texture("_Src", source).set("_Level", sourceLevel)
                .image(TARGETS[f], across).set("_Radius", (int) Math.ceil(3 * sigmaX)).set("_Sigma", sigmaX)
                .set("_Region", x, y, x + w, y + h).set("_Target", 0, 0, 0, 0).set("_Mirror", mirror);
        pass.dispatch(blurKernel(f, true, linear), w, h, 1).texture("_Src", across).set("_Level", 0)
                .image(TARGETS[f], target, targetLevel, -1).set("_Radius", (int) Math.ceil(3 * sigmaY))
                .set("_Sigma", sigmaY).set("_Region", 0, 0, w, h).set("_Target", tx, ty, 0, 0).set("_Mirror", mirror);
    }

    /**
     * A scratch's extent for a region {@code n} texels across: the next of 64, 96, 128, 192, 256 ... (half octaves), so
     * a region that changes size each frame reuses a few pooled textures rather than making one a frame. Each op reads
     * and writes its region only.
     */
    static int scratchSize(int n) {
        int size = 64;
        while (size < n) size = (size & (size - 1)) == 0 ? size + size / 2 : size / 3 * 4;
        return size;
    }

    /** Level {@code level}'s extent of an axis of {@code size} texels at level 0. */
    private static int size(int size, int level) {
        return Math.max(1, size >> level);
    }

    /** Its colour format's index in {@link #IMAGE_TYPES}. */
    private static int imageType(CgGraphTexture texture) {
        CgFrameBufferFormat format = texture.format();
        CgTextureType type = format == null ? null : format.getColorSlot(0);
        int f = IMAGE_TYPES.indexOf(type);
        if (f < 0) throw new IllegalArgumentException(texture + " is " + type + ": the image ops take " + IMAGE_TYPES);
        return f;
    }

    private static CgKernel downsampleKernel(int type, Filter filter) {
        CgKernel k = DOWNSAMPLE_VARIANTS[type][filter.ordinal()];
        if (k == null) {
            k = Files.image().kernel(DOWNSAMPLE_KERNELS[type]);
            if (filter != Filter.AVERAGE) k = k.withKeywords(filter.name());
            DOWNSAMPLE_VARIANTS[type][filter.ordinal()] = k;
        }
        return k;
    }

    private static CgKernel reduceKernel(int type) {
        CgKernel k = REDUCE_VARIANTS[type];
        if (k == null) REDUCE_VARIANTS[type] = k = Files.image().kernel(REDUCE_KERNELS[type]);
        return k;
    }

    private static CgKernel shrinkKernel(int type) {
        CgKernel k = SHRINK_VARIANTS[type];
        if (k == null) SHRINK_VARIANTS[type] = k = Files.image().kernel(SHRINK_KERNELS[type]);
        return k;
    }

    private static CgKernel blurKernel(int type, boolean vertical, boolean linear) {
        int at = (vertical ? 1 : 0) + (linear ? 2 : 0);
        CgKernel k = BLUR_VARIANTS[type][at];
        if (k == null) {
            k = Files.image().kernel(BLUR_KERNELS[type]);
            if (vertical && linear) k = k.withKeywords("VERTICAL", "LINEAR");
            else if (vertical) k = k.withKeywords("VERTICAL");
            else if (linear) k = k.withKeywords("LINEAR");
            BLUR_VARIANTS[type][at] = k;
        }
        return k;
    }

    // ── Rows to the CPU ──────────────────────────────────────────────────────

    /**
     * The rows of {@code buffer} a count says were written, read back with the count: an event stream a kernel appends
     * to, on the CPU a few frames later and never a stall. {@code sink} gets the count as the GPU wrote it and its rows
     * of {@code stride} bytes from {@code offset}, at most the capacity. Recorded after what writes them.
     *
     * <pre>{@code
     * // Each landing a kernel appended: x, y, z and a speed, 16 bytes
     * CgGpuOps.readRows(recording, landings, 0, 16, CgGpuCount.at(counts, 2, MAX_LANDINGS), (count, rows) -> {
     *     for (int at = 0; at < rows.limit(); at += 16) dust(rows.getFloat(at), rows.getFloat(at + 4), rows.getFloat(at + 8));
     *     if (Integer.compareUnsigned(count, MAX_LANDINGS) > 0) dropped += count - MAX_LANDINGS;
     * });
     * }</pre>
     *
     * <ul>
     *   <li>It moves the capacity's rows whatever the count, since a copy's size is fixed when it is recorded: size the
     *       capacity to the stream.</li>
     *   <li>Both buffers need {@code COPY}. A fixed count reads that many rows and delivers it as the count.</li>
     *   <li>The request answered is the rows'; it is done once the sink has run.</li>
     * </ul>
     */
    public static CgRequest readRows(CgRecording recording, CgGraphBuffer buffer, long offset, int stride,
                                     CgGpuCount count, Rows sink) {
        int capacity = count.capacity();
        if (stride <= 0 || capacity == 0) {
            throw new IllegalArgumentException(capacity + " rows of " + stride + " bytes of " + buffer);
        }
        RowsReadback rows = new RowsReadback(sink, stride, capacity);
        if (count.onGpu()) recording.readback(count.buffer(), count.word() * 4L, 4, rows.count);
        return recording.readback(buffer, offset, (long) capacity * stride, rows);
    }

    /**
     * A count's readback, then its rows': the graph keeps the creation order of passes nothing orders otherwise, and
     * {@link CgReadback} delivers oldest first, so the count has arrived when the rows do.
     */
    static final class RowsReadback implements CgReadback.Sink {
        private final Rows sink;
        private final int stride, capacity;
        private int written;
        @Nullable private String lost;

        final CgReadback.Sink count = new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer data) {
                written = data.getInt(0);
            }

            @Override
            public void failed(String reason) {
                lost = reason;
            }
        };

        RowsReadback(Rows sink, int stride, int capacity) {
            this.sink = sink;
            this.stride = stride;
            this.capacity = capacity;
            this.written = capacity;
        }

        @Override
        public void accept(ByteBuffer data) {
            if (lost != null) {
                sink.failed("its count never arrived: " + lost);
                return;
            }
            int rows = Integer.compareUnsigned(written, capacity) < 0 ? written : capacity;
            data.limit(rows * stride);
            sink.accept(written, data);
        }

        @Override
        public void failed(String reason) {
            sink.failed(lost != null ? "its count never arrived: " + lost : reason);
        }
    }

    // ── Counts, scratch, kernels ─────────────────────────────────────────────

    /**
     * Binds the count: a GPU count's word, or for a fixed one {@code_CountAt} left at -1 and COUNT bound, unread, to
     * {@code unread} (a buffer the dispatch binds anyway). Bound first, so a read logs before the dispatch's writes.
     */
    private static CgDispatch counted(CgDispatch dispatch, CgGpuCount count, CgGraphBuffer unread) {
        if (count.onGpu()) {
            return dispatch.bind("COUNT", count.buffer()).set("_CountAt", count.word()).set("_Capacity", count.capacity());
        }
        return dispatch.bind("COUNT", unread).set("_Capacity", count.capacity());
    }

    private static void distinct(CgGraphBuffer read, CgGraphBuffer written) {
        if (read == written) throw new IllegalArgumentException(read + " is both read and written");
    }

    /** {@code count} words of scratch, the pass's recording's: the same buffer each frame it is reused. */
    private static CgGraphBuffer words(CgComputePass pass, String name, int count) {
        return pass.recording().scratch(name, Math.max(1, count) * 4L, CgBufferUsage.STORAGE);
    }

    private static String[] levels(String prefix) {
        String[] names = new String[8];
        for (int i = 0; i < names.length; i++) names[i] = prefix + i;
        return names;
    }

    private static CgKernel scanKernel(int kernel, Fold fold, Element element, boolean flags, boolean inclusive) {
        int at = ((fold.ordinal() * 3 + element.ordinal()) * 2 + (flags ? 1 : 0)) * 2 + (inclusive ? 1 : 0);
        CgKernel k = SCAN_VARIANTS[kernel][at];
        if (k == null) {
            List<String> keywords = new ArrayList<>(4);
            if (fold != Fold.SUM) keywords.add(fold.name());
            if (element != Element.UINT) keywords.add(element.name());
            if (flags) keywords.add("FLAGS");
            if (inclusive) keywords.add("INCLUSIVE");
            k = Files.scan().kernel(SCAN_KERNELS[kernel]).withKeywords(keywords.toArray(new String[0]));
            SCAN_VARIANTS[kernel][at] = k;
        }
        return k;
    }

    private static CgKernel sortKernel(int kernel, Element element, Order order) {
        int at = element.ordinal() * 2 + order.ordinal();
        CgKernel k = SORT_VARIANTS[kernel][at];
        if (k == null) {
            List<String> keywords = new ArrayList<>(2);
            if (element != Element.UINT) keywords.add(element.name());
            if (order == Order.DESCENDING) keywords.add("DESCENDING");
            k = Files.sort().kernel(SORT_KERNELS[kernel]).withKeywords(keywords.toArray(new String[0]));
            SORT_VARIANTS[kernel][at] = k;
        }
        return k;
    }

    /** The ops' kernel files, each loaded the first time an op needs it. */
    private static final class Files {
        private static volatile CgCompute fill, scan, sort, histogram, image, cull, expand, pyramid;

        static CgCompute fill() {
            CgCompute f = fill;
            if (f != null) return f;
            synchronized (Files.class) {
                if (fill == null) fill = CgCompute.load(FILL_PATH);
                return fill;
            }
        }

        static CgCompute scan() {
            CgCompute f = scan;
            if (f != null) return f;
            synchronized (Files.class) {
                if (scan == null) scan = CgCompute.load(SCAN_PATH);
                return scan;
            }
        }

        static CgCompute sort() {
            CgCompute f = sort;
            if (f != null) return f;
            synchronized (Files.class) {
                if (sort == null) sort = CgCompute.load(SORT_PATH);
                return sort;
            }
        }

        static CgCompute histogram() {
            CgCompute f = histogram;
            if (f != null) return f;
            synchronized (Files.class) {
                if (histogram == null) histogram = CgCompute.load(HISTOGRAM_PATH);
                return histogram;
            }
        }

        static CgCompute cull() {
            CgCompute f = cull;
            if (f != null) return f;
            synchronized (Files.class) {
                if (cull == null) cull = CgCompute.load(CULL_PATH);
                return cull;
            }
        }

        static CgCompute expand() {
            CgCompute f = expand;
            if (f != null) return f;
            synchronized (Files.class) {
                if (expand == null) expand = CgCompute.load(EXPAND_PATH);
                return expand;
            }
        }

        static CgCompute image() {
            CgCompute f = image;
            if (f != null) return f;
            synchronized (Files.class) {
                if (image == null) image = CgCompute.load(IMAGE_PATH);
                return image;
            }
        }

        static CgCompute pyramid() {
            CgCompute f = pyramid;
            if (f != null) return f;
            synchronized (Files.class) {
                if (pyramid == null) pyramid = CgCompute.load(PYRAMID_PATH);
                return pyramid;
            }
        }
    }
}
