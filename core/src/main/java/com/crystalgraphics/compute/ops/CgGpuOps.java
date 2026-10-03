package com.crystalgraphics.compute.ops;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What every GPU-driven consumer otherwise writes for itself (gpu-compute C7): fills, sequences, copies, reductions,
 * scans, compaction, sorting, histograms and bounds, dispatched into the caller's compute pass and run on every tier
 * (as compute, lowered below it, or by Java bodies on the CPU tier) with the same answer on each.
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
    private static final int RADIX_COUNT = 0, RADIX_SCATTER = 1, RADIX_SCATTER_KEYS = 2;
    private static final String[] SORT_KERNELS = {"RadixCount", "RadixScatter", "RadixScatterKeys"};
    private static final CgKernel[][] SORT_VARIANTS = new CgKernel[SORT_KERNELS.length][3 * 2];
    private static final String[] SUMS = levels("ops.sums."), PREFIXES = levels("ops.prefixes.");

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
        CgGraphBuffer places = words("ops.compact.places", count.capacity());
        scanLevel(pass, count, Fold.SUM, Element.UINT, true, false, flags, places, 0, count.capacity());
        CgDispatch keep = values == null
                ? pass.dispatch(scanKernel(COMPACT_INDICES, Fold.SUM, Element.UINT, false, false), count.capacity())
                : pass.dispatch(scanKernel(COMPACT_VALUES, Fold.SUM, Element.UINT, false, false), count.capacity());
        counted(keep, count, flags).bind("SRC", flags).bind("PREFIX", places).bind("DST", out);
        if (values != null) keep.bind("VALUES", values);
        counted(pass.dispatch(scanKernel(COMPACT_COUNT, Fold.SUM, Element.UINT, false, false), 1), count, flags)
                .bind("SRC", flags).bind("PREFIX", places).bind("DST", outCount).set("_At", word);
    }

    /** Level by level into scratch, then the last level's fold at {@code word}. Level 0 reads a word of each record. */
    private static void reduceInto(CgComputePass pass, CgGpuCount count, Fold fold, Element element,
                                   CgGraphBuffer values, int stride, int offset, CgGraphBuffer result, int word) {
        CgGraphBuffer src = values;
        int level = 0;
        for (int n = count.capacity(); n > BLOCK; n = (n + BLOCK - 1) / BLOCK, level++) {
            int above = (n + BLOCK - 1) / BLOCK;
            CgGraphBuffer sums = words(SUMS[level], above);
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
            CgGraphBuffer sums = words(SUMS[level], above);
            prefixes = words(PREFIXES[level], above);
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
        if (count.capacity() == 0) return;
        counted(pass.dispatch(Files.histogram().kernel("Histogram"), count.capacity()), count, keys)
                .bind("KEYS", keys).bind("BINS", bins).set("_Shift", shift).set("_Bins", binCount);
    }

    /**
     * Least significant digit first, four bits a pass, ping-ponging through scratch: each pass counts the digits of
     * every block of 32, scans the counts digit-major, and moves each element to its place. An odd number of passes
     * ends in scratch and is copied back.
     */
    private static void radixSort(CgComputePass pass, Element element, Order order, int bits, CgGraphBuffer keys,
                                  @Nullable CgGraphBuffer values, CgGpuCount count) {
        if (values != null) distinct(keys, values);
        int capacity = count.capacity();
        if (capacity <= 1) return;
        int blocks = (capacity + 31) / 32, cells = 16 * blocks, passes = (bits + 3) / 4;
        CgGpuCount cellCount = CgGpuCount.of(cells);
        CgGraphBuffer digits = words("ops.sort.digits", cells), offsets = words("ops.sort.offsets", cells);
        CgGraphBuffer keysIn = keys, keysOut = words("ops.sort.keys", capacity);
        CgGraphBuffer valuesIn = values, valuesOut = values == null ? null : words("ops.sort.values", capacity);
        for (int p = 0; p < passes; p++) {
            counted(pass.dispatch(sortKernel(RADIX_COUNT, element, order), cells), count, keysIn)
                    .bind("KEYS", keysIn).bind("CELLS", digits).set("_Shift", 4 * p).set("_Blocks", blocks);
            scanLevel(pass, cellCount, Fold.SUM, Element.UINT, false, false, digits, offsets, 0, cells);
            CgDispatch scatter = counted(valuesIn == null
                    ? pass.dispatch(sortKernel(RADIX_SCATTER_KEYS, element, order), capacity)
                    : pass.dispatch(sortKernel(RADIX_SCATTER, element, order), capacity), count, keysIn);
            scatter.bind("KEYS", keysIn).bind("OFFSETS", offsets).bind("KEYS_OUT", keysOut)
                    .set("_Shift", 4 * p).set("_Blocks", blocks);
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

    private static CgGraphBuffer words(String name, int count) {
        return CgGraphBuffer.transientBuffer(name, CgBufferDesc.elements(Math.max(1, count), 4, CgBufferUsage.STORAGE));
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
        private static volatile CgCompute fill, scan, sort, histogram;

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
    }
}
