package com.crystalgraphics.compute.ops;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * What every GPU-driven consumer otherwise writes for itself (gpu-compute C7): fills, sequences, copies, reductions,
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
    private static final int RADIX_COUNT = 0, RADIX_SCATTER = 1, RADIX_SCATTER_KEYS = 2, SORT_COUNT = 3, SORT_REDUCE = 4,
            SORT_SCAN = 5, SORT_SCAN_ADD = 6, SORT_SCATTER = 7, SORT_SCATTER_KEYS = 8;
    private static final String[] SORT_KERNELS = {"RadixCount", "RadixScatter", "RadixScatterKeys", "SortCount",
            "SortReduce", "SortScan", "SortScanAdd", "SortScatter", "SortScatterKeys"};
    /** The compute-only sort's keys a block, and its work groups at most: few enough for one group to scan their sums. */
    private static final int SORT_BLOCK = 512, SORT_GROUPS = 1024;
    /** The compute-only histogram's bins at most (held in shared memory), its work groups at most, and keys a group. */
    private static final int GROUP_BINS = 1024, HISTOGRAM_GROUPS = 256, HISTOGRAM_GROUP_KEYS = 4096;
    private static final CgKernel[][] SORT_VARIANTS = new CgKernel[SORT_KERNELS.length][3 * 2];
    private static final String[] SUMS = levels("ops.sums."), PREFIXES = levels("ops.prefixes.");
    private static final String IMAGE_PATH = "crystalgraphics:shaders/env/compute/ops/image.compute";
    private static final String CULL_PATH = "crystalgraphics:shaders/env/compute/ops/cull.compute";
    private static final String INDIRECT_ARGS = "crystalgraphics:shaders/env/compute/args.compute";
    private static final String EXPAND_PATH = "crystalgraphics:shaders/env/compute/ops/expand.compute";
    private static final String PYRAMID_SHADER = "crystalgraphics:shaders/depth_pyramid.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    /** An object record's bytes: what each instance and each kept record of a cull is. */
    private static final int RECORD_BYTES = CgInstanceKind.OBJECT.floats() * 4;
    /** Each instance buffer's keys start at a multiple of this in a batched cull: a binding offset is a whole 256 bytes. */
    private static final int KEY_ALIGN = 64;
    /** Per image type: its kernels and images in image.compute. */
    static final String[] DOWNSAMPLE_KERNELS = {"DownsampleRgba8", "DownsampleRgba16f", "DownsampleR16f", "DownsampleR32f"},
            BLUR_KERNELS = {"BlurRgba8", "BlurRgba16f", "BlurR16f", "BlurR32f"},
            SOURCES = {"SRC_RGBA8", "SRC_RGBA16F", "SRC_R16F", "SRC_R32F"},
            TARGETS = {"DST_RGBA8", "DST_RGBA16F", "DST_R16F", "DST_R32F"};
    /** A blur's scratch, per image type. */
    private static final CgFrameBufferFormat[] SCRATCH = new CgFrameBufferFormat[IMAGE_TYPES.size()];
    private static final CgKernel[][] DOWNSAMPLE_VARIANTS = new CgKernel[IMAGE_TYPES.size()][Filter.values().length];
    private static final CgKernel[][] BLUR_VARIANTS = new CgKernel[IMAGE_TYPES.size()][2];

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
    public static void histogram(CgComputePass pass, CgGraphBuffer keys, CgGpuCount count, CgGraphBuffer bins,
                                 int binCount, int shift) {
        if (shift < 0 || shift > 31) throw new IllegalArgumentException("a shift of " + shift);
        if (binCount < 1) throw new IllegalArgumentException(binCount + " bins");
        distinct(keys, bins);
        fill(pass, bins, 0, CgGpuCount.of(binCount));
        int capacity = count.capacity();
        if (capacity == 0) return;
        CgKernel grouped = Files.histogram().kernel("HistogramGroups");
        CgDispatch dispatch;
        if (binCount <= GROUP_BINS && grouped.runs()) {
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
     * frame does not build them: below compute a sort is a dozen lowered programs. Render thread.
     *
     * <pre>{@code
     * CgGpuOps.prepareSort(Element.UINT, Order.ASCENDING);       // on a loading screen, or as the effect is made
     * CgGpuOps.prepareScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
     * CgGpuOps.prepareHistogram();
     * }</pre>
     *
     * A sort by bits ({@link #sort(CgComputePass, int, Order, CgGraphBuffer, CgGraphBuffer, CgGpuCount)}) is
     * {@code Element.UINT}'s.
     */
    public static void prepareSort(Element element, Order order) {
        boolean grouped = sortKernel(SORT_COUNT, element, order).runs();
        int[] kernels = grouped
                ? new int[]{SORT_COUNT, SORT_REDUCE, SORT_SCAN, SORT_SCAN_ADD, SORT_SCATTER, SORT_SCATTER_KEYS}
                : new int[]{RADIX_COUNT, RADIX_SCATTER, RADIX_SCATTER_KEYS};
        for (int k : kernels) prepare(sortKernel(k, element, order));
        if (!grouped) prepareScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
        prepare(Files.fill().kernel("Copy"));
    }

    /** {@link #prepareSort}, for a scan. */
    public static void prepareScan(Scan scan, Fold fold, Element element) {
        prepare(scanKernel(REDUCE_STEP, fold, element, false, false));
        prepare(scanKernel(SCAN_BLOCK, fold, element, false, scan == Scan.INCLUSIVE));
    }

    /** {@link #prepareSort}, for {@link #fill}, {@link #iota} and {@link #copy}. */
    public static void prepareFill() {
        prepare(Files.fill().kernel("Fill"));
        prepare(Files.fill().kernel("Iota"));
        prepare(Files.fill().kernel("Copy"));
    }

    /** {@link #prepareSort}, for a histogram. */
    public static void prepareHistogram() {
        prepare(Files.fill().kernel("Fill"));
        prepare(Files.histogram().kernel("HistogramGroups"));
        prepare(Files.histogram().kernel("Histogram"));
    }

    /**
     * {@link #prepareSort}, for the kernel writing every indirect draw's command: what a draw's {@code .indirect} or
     * {@code .instances} costs at its first frame otherwise.
     */
    public static void prepareIndirect() {
        prepare(CgCompute.load(INDIRECT_ARGS).kernel("DrawArgs"));
    }

    /** {@link #prepareSort}, for {@link #cull}; with {@code ordered}, its {@link CgCull#ordered} form too. */
    public static void prepareCull(boolean ordered) {
        prepareIndirect();
        prepare(Files.fill().kernel("FillAt"));
        prepare(Files.cull().kernel("Cull"));
        prepare(Files.cull().kernel("CullKey"));
        prepare(Files.cull().kernel("CullGather"));
        prepareSort(Element.UINT, Order.ASCENDING);
        prepareHistogram();
        prepareScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
        if (!ordered) return;
        prepare(Files.cull().kernel("CullFlags"));
        prepare(Files.cull().kernel("CullPlace"));
        for (int k : new int[]{REDUCE_STEP, SCAN_BLOCK, COMPACT_INDICES, COMPACT_COUNT}) {
            prepare(scanKernel(k, Fold.SUM, Element.UINT, k == REDUCE_STEP || k == SCAN_BLOCK, false));
        }
        prepareScan(Scan.EXCLUSIVE, Fold.SUM, Element.UINT);
    }

    /** {@link #prepareSort}, for {@link #depthPyramid}: its seed material and its chain's kernel. */
    public static void prepareDepthPyramid() {
        CgMaterial.load(PYRAMID_SHADER).prepare();
        prepare(downsampleKernel(IMAGE_TYPES.indexOf(PYRAMID_FORMAT.getColorSlot(0)), Filter.MAX));
    }

    private static void prepare(CgKernel kernel) {
        if (kernel.runs()) kernel.prepare();
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
     * whatever should hide the instances has drawn.
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
        CgMaterial seed = CgMaterial.load(PYRAMID_SHADER);
        CgPipeline pipeline = seed.pipeline(CgInstanceKind.OBJECT);
        CgRasterPass pass = recording.raster(pyramid, CgLoad.load(), constants, null, CgOrder.LOOKBACK)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, depthOf);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, seed.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
        CgComputePass chain = recording.compute("cg.depthPyramid");
        downsample(chain, pyramid, Filter.MAX);
        chain.end();
    }

    /**
     * Least significant digit first, four bits a pass, ping-ponging through scratch; an odd number of passes ends in
     * scratch and is copied back. Both forms are stable, so they put every key in the same place.
     *
     * <p>Where the compute-only kernels run (FidelityFX Parallel Sort's), each work group counts the digits of its
     * run of 512-key blocks, the counts are scanned digit-major in two levels, and each group sorts every 128 of its
     * keys by the digit in shared memory before writing them out. Elsewhere each pass counts the digits of every
     * block of 32, scans the counts with {@link #scanLevel}, and ranks each element within its block.
     */
    private static void radixSort(CgComputePass pass, Element element, Order order, int bits, CgGraphBuffer keys,
                                  @Nullable CgGraphBuffer values, CgGpuCount count) {
        if (values != null) distinct(keys, values);
        int capacity = count.capacity();
        if (capacity <= 1) return;
        boolean grouped = sortKernel(SORT_COUNT, element, order).runs();
        int blocks = grouped ? (capacity + SORT_BLOCK - 1) / SORT_BLOCK : (capacity + 31) / 32;
        int groups = Math.min(blocks, SORT_GROUPS), reduce = (groups + SORT_BLOCK - 1) / SORT_BLOCK;
        int cells = 16 * (grouped ? groups : blocks), passes = (bits + 3) / 4;
        CgGpuCount cellCount = CgGpuCount.of(cells);
        CgGraphBuffer digits = words(pass, "ops.sort.digits", cells);
        CgGraphBuffer offsets = grouped ? null : words(pass, "ops.sort.offsets", cells);
        CgGraphBuffer reduced = grouped ? words(pass, "ops.sort.reduced", 16 * reduce) : null;
        CgGraphBuffer keysIn = keys, keysOut = words(pass, "ops.sort.keys", capacity);
        CgGraphBuffer valuesIn = values, valuesOut = values == null ? null : words(pass, "ops.sort.values", capacity);
        for (int p = 0; p < passes; p++) {
            CgDispatch scatter;
            if (grouped) {
                groupRuns(counted(pass.dispatchGroups(sortKernel(SORT_COUNT, element, order), groups, 1, 1), count,
                        keysIn), blocks, groups).bind("KEYS", keysIn).bind("TABLE", digits).set("_Shift", 4 * p);
                scanGroups(pass, digits, reduced, groups, reduce);
                scatter = groupRuns(counted(pass.dispatchGroups(sortKernel(valuesIn == null ? SORT_SCATTER_KEYS
                        : SORT_SCATTER, element, order), groups, 1, 1), count, keysIn), blocks, groups)
                        .bind("TABLE", digits);
            } else {
                counted(pass.dispatch(sortKernel(RADIX_COUNT, element, order), cells), count, keysIn)
                        .bind("KEYS", keysIn).bind("CELLS", digits).set("_Shift", 4 * p).set("_Blocks", blocks);
                scanLevel(pass, cellCount, Fold.SUM, Element.UINT, false, false, digits, offsets, 0, cells);
                scatter = counted(pass.dispatch(sortKernel(valuesIn == null ? RADIX_SCATTER_KEYS : RADIX_SCATTER,
                        element, order), capacity), count, keysIn).bind("OFFSETS", offsets).set("_Blocks", blocks);
            }
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

    /** How the compute-only sort shares {@code blocks} blocks of 512 among {@code groups} work groups. */
    private static CgDispatch groupRuns(CgDispatch dispatch, int blocks, int groups) {
        return dispatch.set("_Groups", groups).set("_GroupBlocks", blocks / groups).set("_ExtraGroups", blocks % groups);
    }

    /**
     * The compute-only sort's counts, scanned digit-major in place: each digit's groups summed per 512, those sums
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
        int radius = (int) Math.ceil(3 * sigma);
        CgGraphTexture across = pass.recording().scratch("ops.blur", w, h, SCRATCH[f]);
        pass.dispatch(blurKernel(f, false), w, h, 1).image(SOURCES[f], source, sourceLevel, -1)
                .image(TARGETS[f], across).set("_Radius", radius).set("_Sigma", sigma);
        pass.dispatch(blurKernel(f, true), w, h, 1).image(SOURCES[f], across)
                .image(TARGETS[f], target, targetLevel, -1).set("_Radius", radius).set("_Sigma", sigma);
    }

    /** Level {@code level}'s extent of an axis of {@code size} texels at level 0. */
    private static int size(int size, int level) {
        return Math.max(1, size >> level);
    }

    /** Its colour format's index in {@link #IMAGE_TYPES}. */
    private static int imageType(CgGraphTexture texture) {
        CgTextureDesc desc = texture.desc();
        CgFrameBuffer framebuffer = texture.framebuffer();
        CgFrameBufferFormat format = desc != null ? desc.format() : framebuffer != null ? framebuffer.getFormat() : null;
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

    private static CgKernel blurKernel(int type, boolean vertical) {
        CgKernel k = BLUR_VARIANTS[type][vertical ? 1 : 0];
        if (k == null) {
            k = Files.image().kernel(BLUR_KERNELS[type]);
            if (vertical) k = k.withKeywords("VERTICAL");
            BLUR_VARIANTS[type][vertical ? 1 : 0] = k;
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

    /** The ops' kernel files, each loaded with its Java bodies the first time an op needs it. */
    private static final class Files {
        private static volatile CgCompute fill, scan, sort, histogram, image, cull, expand;

        static CgCompute fill() {
            CgCompute f = fill;
            if (f != null) return f;
            synchronized (Files.class) {
                if (fill == null) fill = CgGpuOpsBodies.fill(CgCompute.load(FILL_PATH));
                return fill;
            }
        }

        static CgCompute scan() {
            CgCompute f = scan;
            if (f != null) return f;
            synchronized (Files.class) {
                if (scan == null) scan = CgGpuOpsBodies.scan(CgCompute.load(SCAN_PATH));
                return scan;
            }
        }

        static CgCompute sort() {
            CgCompute f = sort;
            if (f != null) return f;
            synchronized (Files.class) {
                if (sort == null) sort = CgGpuOpsBodies.sort(CgCompute.load(SORT_PATH));
                return sort;
            }
        }

        static CgCompute histogram() {
            CgCompute f = histogram;
            if (f != null) return f;
            synchronized (Files.class) {
                if (histogram == null) histogram = CgGpuOpsBodies.histogram(CgCompute.load(HISTOGRAM_PATH));
                return histogram;
            }
        }

        static CgCompute cull() {
            CgCompute f = cull;
            if (f != null) return f;
            synchronized (Files.class) {
                if (cull == null) cull = CgGpuOpsBodies.cull(CgCompute.load(CULL_PATH));
                return cull;
            }
        }

        static CgCompute expand() {
            CgCompute f = expand;
            if (f != null) return f;
            synchronized (Files.class) {
                if (expand == null) expand = CgGpuOpsBodies.expand(CgCompute.load(EXPAND_PATH));
                return expand;
            }
        }

        static CgCompute image() {
            CgCompute f = image;
            if (f != null) return f;
            synchronized (Files.class) {
                if (image == null) image = CgGpuOpsBodies.image(CgCompute.load(IMAGE_PATH));
                return image;
            }
        }
    }
}
