package com.crystalgraphics.compute.ops;

import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.gpu.resource.CgTexelConvert;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.compute.CgComputeSelfTest;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgReplayedReads;
import com.crystalgraphics.compute.ops.CgGpuOps.Element;
import com.crystalgraphics.compute.ops.CgGpuOps.Filter;
import com.crystalgraphics.compute.ops.CgGpuOps.Fold;
import com.crystalgraphics.compute.ops.CgGpuOps.Order;
import com.crystalgraphics.compute.ops.CgGpuOps.Scan;
import com.crystalgraphics.gpu.resource.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.compute.CgCheckBuffers;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

/**
 * Runs every {@link CgGpuOps} op on this context, at counts from none to tens of thousands, fixed and read from the
 * GPU, and checks each answer against Java bit for bit: floats included, since every tier folds in the same order. The
 * image ops run on every format they take at odd and even sizes, each level checked against Java from the level above
 * as the GPU wrote it, within a rounding of the format.
 *
 * <pre>{@code
 * CgGpuOpsCheck.Result result = CgGpuOpsCheck.run();   // render thread, a live context
 * if (!result.passed()) result.failures().forEach(System.out::println);
 * }</pre>
 *
 * <ul>
 *   <li>With {@code -Dcrystalgraphics.compute.selfTest=true} it runs once beside the compute self-test and logs
 *       {@code [crystalgraphics] gpu ops check G40: PASS} once its reads have arrived ({@code CgReplayedReads}), so no
 *       frame waits; the harness's {@code gpu-ops} scene runs it at any tier.</li>
 *   <li>{@link #run()} reads its buffers back at once, so it waits on the GPU: a diagnosis, never a frame's work, and
 *       refused where the host submits.</li>
 *   <li>Every output starts as a sentinel: a word past the count that changed is a failure.</li>
 * </ul>
 */
public final class CgGpuOpsCheck {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final String PATH = "crystalgraphics:shaders/env/compute/ops/check.compute";
    private static final int[] COUNTS = {0, 1, 16, 17, 300, 5000, 70000};
    /** Words past every buffer's capacity, which nothing may write. */
    private static final int PAD = 16;
    private static final int SENTINEL = 0xC0FFEE11, DRAWN = 1000, SEED = 0x1234567, RESULTS = 16, BINS = 50;
    private static final int[][] IMAGE_SIZES = {{16, 16}, {13, 7}, {1, 9}, {37, 64}};
    private static final float SIGMA = 1.6f, LEVEL_SIGMA = 0.8f, SCALED_SIGMA = 5f;
    private static boolean ran;
    /** The first run's reads, while they arrive. */
    private static CgReplayedReads requested;

    /**
     * @param tier     the tier the context ran the ops at
     * @param failures every answer that differed, empty when it passed
     */
    public record Result(String tier, List<String> failures) {
        public boolean passed() { return failures.isEmpty(); }

        /** {@code G40: PASS}, or {@code G40: FAIL, 2 differences: ...}. */
        public String verdict() {
            return tier + (passed() ? ": PASS" : ": FAIL, " + failures.size() + " differences: " + String.join("; ", failures));
        }
    }

    private CgGpuOpsCheck() {}

    /**
     * With {@code -Dcrystalgraphics.compute.selfTest=true}, once per process: run with every read requested, then run
     * again once they have arrived, and log the verdict, as the compute self-test does. Render thread, once a frame.
     */
    public static void runIfAsked() {
        if (!CgComputeSelfTest.SELF_TEST.get()) return;
        if (requested != null) {
            if (requested.waiting()) return;
            CgReplayedReads reads = requested;
            requested = null;
            report(() -> run(reads.replay()), reads.failure());
            return;
        }
        if (ran) return;
        ran = true;
        CgReplayedReads reads = CgReplayedReads.requesting();
        try {
            run(reads);
            requested = reads;
        } catch (RuntimeException | LinkageError failed) {
            LOG.error("[crystalgraphics] gpu ops check " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    private static void report(Supplier<Result> run, String lost) {
        try {
            Result result = run.get();
            if (lost != null) LOG.error("[crystalgraphics] gpu ops check {}: FAIL, a read never arrived: {}", result.tier(), lost);
            else if (result.passed()) LOG.info("[crystalgraphics] gpu ops check {}", result.verdict());
            else LOG.error("[crystalgraphics] gpu ops check {}", result.verdict());
        } catch (RuntimeException | LinkageError failed) {
            LOG.error("[crystalgraphics] gpu ops check " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    /** Every op run and checked on the current context, waiting on the GPU. Render thread; GL state is put back. */
    public static Result run() {
        return run(CgReplayedReads.immediate());
    }

    /** {@link #run()}, reading through {@code reads}. */
    public static Result run(CgReplayedReads reads) {
        List<String> failures = new ArrayList<>();
        List<Case> cases = new ArrayList<>();
        for (int n : COUNTS) {
            cases.add(new Case("fixed " + n, n, n, -1));
            cases.add(new Case("gpu " + n, n, n + 37, n));
        }
        cases.add(new Case("gpu clamped", 300, 300, 350));
        List<ImageCase> images = new ArrayList<>();
        for (CgTextureType type : CgGpuOps.IMAGE_TYPES) for (int[] size : IMAGE_SIZES) images.add(new ImageCase(type, size[0], size[1]));
        CgCompute check = CgCompute.load(PATH);
        int drawn = buffer(DRAWN * 4);
        try {
            CgRecording rec = new CgRecording();
            CgComputePass pass = rec.compute("ops-check");
            for (Case c : cases) c.record(pass);
            for (ImageCase c : images) c.record(pass);
            pass.dispatch(check.kernel("Rng"), DRAWN).bind("DRAWN", CgGraphBuffer.imported("drawn", drawn, DRAWN * 16L))
                    .set("_Seed", SEED);
            pass.end();
            CgImmediate.execute(rec);

            for (Case c : cases) c.expect(failures, reads);
            for (ImageCase c : images) c.expect(failures, reads);
            int[] r = new int[4], want = new int[DRAWN * 4];
            for (int e = 0; e < DRAWN; e++) {
                CgRng.rng4(SEED, e, 3, 1, r);
                System.arraycopy(r, 0, want, e * 4, 4);
            }
            expectWords(failures, "rng", reads.words(drawn, DRAWN * 4), want);
        } finally {
            CgCheckBuffers.release(drawn);
            for (Case c : cases) c.delete();
            for (ImageCase c : images) c.delete();
        }
        return new Result(CgCapabilities.detect().computeTier().name(), List.copyOf(failures));
    }

    /** One count: its inputs, every op's output buffer, and what each must hold. */
    private static final class Case {
        final String name;
        final int n, capacity, words, outElements;
        /** The count's word on the GPU, -1 for a fixed count. */
        final int countWord;
        final int[] uints, ints, floats, flags, points, cells, lengths;
        final int gpuUints, gpuInts, gpuFloats, gpuFlags, gpuPoints, gpuLengths, count, fill, iota, copy, args, results,
                scanA, scanB, scanC, indices, values, sortedKeys, sortedValues, sortedFloats, sortedInts, cellKeys,
                cellValues, bins, expanded;
        /** What {@link #expect} reads through, while it runs. */
        CgReplayedReads reads;

        Case(String name, int n, int capacity, int countWord) {
            this.name = name;
            this.n = n;
            this.capacity = capacity;
            this.countWord = countWord;
            words = capacity + PAD;
            outElements = words + n / 4;   // from 300 rows up, fewer than they expand to
            uints = new int[words];
            ints = new int[words];
            floats = new int[words];
            flags = new int[words];
            points = new int[4 * words];
            cells = new int[words];
            lengths = new int[words];
            for (int i = 0; i < 4 * words; i++) {
                points[i] = Float.floatToRawIntBits((CgRng.rng(capacity, i, 1, 0) >>> 12) / 4099f - 500f);
            }
            for (int i = 0; i < words; i++) {
                uints[i] = CgRng.rng(capacity, i, 0, 0);
                ints[i] = CgRng.rng(capacity, i, 0, 1);
                floats[i] = Float.floatToRawIntBits((CgRng.rng(capacity, i, 0, 2) >>> 16) / 977f - 30f);
                int keep = CgRng.rng(capacity, i, 0, 3);
                flags[i] = Integer.remainderUnsigned(keep, 3) == 0 ? keep | 1 : 0;
                cells[i] = (CgRng.rng(capacity, i, 0, 4) & 0xFFFFF000) | Integer.remainderUnsigned(CgRng.rng(capacity, i, 0, 5), 700);
                lengths[i] = Integer.remainderUnsigned(CgRng.rng(capacity, i, 0, 6), 4);
            }
            gpuUints = buffer(uints);
            gpuInts = buffer(ints);
            gpuFloats = buffer(floats);
            gpuFlags = buffer(flags);
            gpuPoints = buffer(points);
            gpuLengths = buffer(lengths);
            count = buffer(new int[] {SENTINEL, countWord, SENTINEL});
            fill = buffer(words);
            iota = buffer(words);
            copy = buffer(words);
            args = buffer(8);
            results = buffer(RESULTS);
            scanA = buffer(words);
            scanB = buffer(words);
            scanC = buffer(words);
            indices = buffer(words);
            values = buffer(words);
            sortedKeys = buffer(head(uints));
            sortedValues = buffer(head(ints));
            sortedFloats = buffer(head(floats));
            sortedInts = buffer(head(ints));
            cellKeys = buffer(head(cells));
            cellValues = buffer(head(words(words, e -> e)));
            bins = buffer(64);
            expanded = buffer(2 * outElements);
        }

        void record(CgComputePass pass) {
            CgGraphBuffer counts = imported("count", count, 3);
            CgGpuCount at = countWord < 0 ? CgGpuCount.of(n) : CgGpuCount.at(counts, 1, capacity);
            CgGraphBuffer u = imported("uints", gpuUints, words), i = imported("ints", gpuInts, words),
                    f = imported("floats", gpuFloats, words), r = imported("results", results, RESULTS);
            CgGpuOps.fill(pass, imported("fill", fill, words), 7, at);
            CgGpuOps.iota(pass, imported("iota", iota, words), 5, 3, at);
            CgGpuOps.copy(pass, u, imported("copy", copy, words), at);
            CgGpuOps.dispatchArgs(pass, at, 64, imported("args", args, 8), 1);
            CgGpuOps.reduce(pass, Fold.SUM, Element.UINT, u, at, r, 0);
            CgGpuOps.reduce(pass, Fold.MIN, Element.INT, i, at, r, 1);
            CgGpuOps.reduce(pass, Fold.MAX, Element.FLOAT, f, at, r, 2);
            CgGpuOps.reduce(pass, Fold.SUM, Element.FLOAT, f, at, r, 3);
            CgGpuOps.reduce(pass, Fold.MAX, Element.UINT, u, at, r, 4);
            CgGpuOps.scan(pass, Scan.EXCLUSIVE, Fold.SUM, Element.UINT, u, at, imported("scanA", scanA, words));
            CgGpuOps.scan(pass, Scan.INCLUSIVE, Fold.MIN, Element.INT, i, at, imported("scanB", scanB, words));
            CgGpuOps.scan(pass, Scan.INCLUSIVE, Fold.SUM, Element.FLOAT, f, at, imported("scanC", scanC, words));
            CgGraphBuffer keep = imported("flags", gpuFlags, words);
            CgGpuOps.compact(pass, keep, null, at, imported("indices", indices, words), r, 5);
            CgGpuOps.compact(pass, keep, u, at, imported("values", values, words), r, 6);
            CgGpuOps.bounds(pass, imported("points", gpuPoints, 4 * words), 4, 0, at, r, 7);
            CgGpuOps.sort(pass, Element.UINT, Order.ASCENDING, imported("sortedKeys", sortedKeys, words),
                    imported("sortedValues", sortedValues, words), at);
            CgGpuOps.sort(pass, Element.FLOAT, Order.DESCENDING, imported("sortedFloats", sortedFloats, words), null, at);
            CgGpuOps.sort(pass, Element.INT, Order.ASCENDING, imported("sortedInts", sortedInts, words), null, at);
            CgGpuOps.sort(pass, 12, Order.ASCENDING, imported("cellKeys", cellKeys, words),
                    imported("cellValues", cellValues, words), at);
            CgGpuOps.histogram(pass, u, at, imported("bins", bins, 64), BINS, 26);
            CgGpuOps.expand(pass, imported("lengths", gpuLengths, words), at,
                    imported("expanded", expanded, 2 * outElements), counts, 2);
        }

        void expect(List<String> failures, CgReplayedReads reads) {
            this.reads = reads;
            IntBinaryOperator sum = Integer::sum, minInt = Math::min,
                    maxUint = (a, b) -> Integer.compareUnsigned(a, b) < 0 ? b : a,
                    maxFloat = (a, b) -> Float.intBitsToFloat(a) < Float.intBitsToFloat(b) ? b : a,
                    sumFloat = (a, b) -> Float.floatToRawIntBits(Float.intBitsToFloat(a) + Float.intBitsToFloat(b));
            int[] kept = new int[n];
            int keptCount = 0;
            for (int e = 0; e < n; e++) if (flags[e] != 0) kept[keptCount++] = e;
            int[] keptValues = new int[keptCount];
            for (int k = 0; k < keptCount; k++) keptValues[k] = uints[kept[k]];

            expect(failures, "fill", fill, words(n, e -> 7));
            expect(failures, "iota", iota, words(n, e -> 5 + 3 * e));
            expect(failures, "copy", copy, words(n, e -> uints[e]));
            expectWords(failures, name + " args", reads.words(args, 8),
                    new int[] {SENTINEL, (n + 63) / 64, 1, 1, SENTINEL, SENTINEL, SENTINEL, SENTINEL});
            int[] box = {0x7F800000, 0x7F800000, 0x7F800000, 0xFF800000, 0xFF800000, 0xFF800000};
            for (int e = 0; e < n; e++) {
                for (int c = 0; c < 3; c++) {
                    box[c] = Float.intBitsToFloat(points[4 * e + c]) < Float.intBitsToFloat(box[c]) ? points[4 * e + c] : box[c];
                    box[3 + c] = maxFloat.applyAsInt(box[3 + c], points[4 * e + c]);
                }
            }
            expectWords(failures, name + " results", reads.words(results, RESULTS), new int[] {
                    reduce(uints, sum, 0), reduce(ints, minInt, Integer.MAX_VALUE),
                    reduce(floats, maxFloat, 0xFF800000), reduce(floats, sumFloat, 0),
                    reduce(uints, maxUint, 0), keptCount, keptCount,
                    box[0], box[1], box[2], box[3], box[4], box[5], SENTINEL, SENTINEL, SENTINEL});
            expect(failures, "scan sum", scanA, scan(uints, n, sum, 0, false));
            expect(failures, "scan min", scanB, scan(ints, n, minInt, Integer.MAX_VALUE, true));
            expect(failures, "scan float sum", scanC, scan(floats, n, sumFloat, 0, true));
            expect(failures, "compact indices", indices, Arrays.copyOf(kept, keptCount));
            expect(failures, "compact values", values, keptValues);

            Integer[] byUint = order(Comparator.comparingInt(e -> uints[e] ^ 0x80000000));
            expect(failures, "sort uint keys", sortedKeys, sorted(byUint, uints));
            expect(failures, "sort uint values", sortedValues, sorted(byUint, ints));
            expect(failures, "sort float descending", sortedFloats,
                    sorted(order(Comparator.comparingInt(e -> ~floatOrder(floats[e]))), floats));
            expect(failures, "sort int", sortedInts, sorted(order(Comparator.comparingInt(e -> ints[e])), ints));
            Integer[] byCell = order(Comparator.comparingInt(e -> cells[e] & 0xFFF));
            expect(failures, "sort 12 bits keys", cellKeys, sorted(byCell, cells));
            expect(failures, "sort 12 bits values", cellValues, sorted(byCell, words(words, e -> e)));

            int[] histogram = new int[BINS];
            for (int e = 0; e < n; e++) histogram[Math.min(uints[e] >>> 26, BINS - 1)]++;
            int[] binWords = new int[64];
            Arrays.fill(binWords, SENTINEL);
            System.arraycopy(histogram, 0, binWords, 0, BINS);
            expectWords(failures, name + " histogram", reads.words(bins, 64), binWords);

            int[] starts = scan(lengths, n, sum, 0, false);
            int total = n == 0 ? 0 : starts[n - 1] + lengths[n - 1];
            expectWords(failures, name + " expand total", reads.words(count, 3), new int[] {SENTINEL, countWord, total});
            int[] pairs = new int[2 * outElements];
            Arrays.fill(pairs, SENTINEL);
            for (int r = 0, j = 0; r < n && j < outElements; r++) {
                for (int k = 0; k < lengths[r] && j < outElements; k++, j++) {
                    pairs[2 * j] = r;
                    pairs[2 * j + 1] = k;
                }
            }
            expectWords(failures, name + " expand", reads.words(expanded, 2 * outElements), pairs);
        }

        /** {@code of}'s first {@code n} words, then sentinels: what a sort may reorder, and what it must not touch. */
        int[] head(int[] of) {
            int[] w = new int[words];
            Arrays.fill(w, SENTINEL);
            System.arraycopy(of, 0, w, 0, n);
            return w;
        }

        /** The first {@code n} indices, stably ordered: what a stable sort moves where. */
        Integer[] order(Comparator<Integer> by) {
            Integer[] order = new Integer[n];
            for (int e = 0; e < n; e++) order[e] = e;
            Arrays.sort(order, by);
            return order;
        }

        static int[] sorted(Integer[] order, int[] of) {
            int[] out = new int[order.length];
            for (int k = 0; k < order.length; k++) out[k] = of[order[k]];
            return out;
        }

        /** A float's bits as a signed int that orders as the float does. */
        static int floatOrder(int bits) {
            return bits < 0 ? ~bits ^ 0x80000000 : bits;
        }

        /** Sixteen at a time, a level at a time, as scan.compute folds. */
        int reduce(int[] v, IntBinaryOperator op, int identity) {
            int[] level = Arrays.copyOf(v, n);
            while (level.length > CgGpuOps.BLOCK) level = blocks(level, op, identity);
            int acc = identity;
            for (int x : level) acc = op.applyAsInt(acc, x);
            return acc;
        }

        /** {@code buffer} holds {@code want}, then sentinels to its end. */
        void expect(List<String> failures, String op, int buffer, int[] want) {
            int[] full = new int[words];
            Arrays.fill(full, SENTINEL);
            System.arraycopy(want, 0, full, 0, want.length);
            expectWords(failures, name + " " + op, reads.words(buffer, words), full);
        }

        void delete() {
            for (int b : new int[] {gpuUints, gpuInts, gpuFloats, gpuFlags, gpuPoints, gpuLengths, count, fill, iota, copy,
                    args, results, scanA, scanB, scanC, indices, values, sortedKeys, sortedValues, sortedFloats, sortedInts,
                    cellKeys, cellValues, bins, expanded}) {
                CgCheckBuffers.release(b);
            }
        }
    }

    /**
     * One format at one size: a chain per filter, a blur into another texture, level 1 of a chain blurred in place, a
     * region blurred into place with mirrored edges, a region blurred at half size (scale-then-blur), a fractional
     * region shrunk to a third, and the whole image shrunk to one texel.
     * Inputs are read back as stored, so an expectation starts from the format's own values.
     */
    private static final class ImageCase {
        final String name;
        final CgTextureType type;
        final int w, h, channels;
        /** One rounding to the format, at a value of 1: a texel may differ by it per write. */
        final double ulp;
        final CgFrameBuffer[] chains = new CgFrameBuffer[Filter.values().length];
        final CgFrameBuffer blurSource, blurTarget, levelBlur, regionTarget, scaledTarget, shrinkTarget, meanTarget;
        /** The region the region and scaled blurs read, and where the region blur lands. */
        final int rx, ry, rw, rh, tx, ty;
        /** A shrink's fractional source box, and the rect it lands in at the corner of {@link #shrinkTarget}. */
        final float shx, shy, shw, shh;
        final int stw, sth;
        /** What {@link #expect} reads through, while it runs. */
        CgReplayedReads reads;

        ImageCase(CgTextureType type, int w, int h) {
            this.type = type;
            this.w = w;
            this.h = h;
            name = type + " " + w + "x" + h;
            channels = type == CgTextureType.R16F || type == CgTextureType.R32F ? 1 : 4;
            ulp = type == CgTextureType.RGBA8 ? 1.0 / 255 : type == CgTextureType.R32F ? 1e-5 : 1.0 / 1024;
            CgFrameBufferFormat format = CgFrameBufferFormat.builder("cg_ops_check_" + type.name().toLowerCase())
                    .color(0, type).build();
            float[] data = new float[w * h * 4];
            for (int i = 0; i < data.length; i++) {
                float unit = (CgRng.rng(w * 131 + h, i, 2, type.ordinal()) >>> 8) / (float) (1 << 24);
                data[i] = type == CgTextureType.RGBA8 ? unit : unit * 8f - 2f;
            }
            int full = CgTexture.fullChain(w, h);
            for (int f = 0; f < chains.length; f++) chains[f] = texture(format, full, data);
            blurSource = texture(format, 1, data);
            blurTarget = texture(format, 1, data);
            levelBlur = texture(format, full, data);
            regionTarget = texture(format, 1, data);
            scaledTarget = texture(format, 1, data, (w + 1) / 2, (h + 1) / 2);
            rw = Math.max(1, w / 2);
            rh = Math.max(1, h / 2);
            rx = w / 4;
            ry = h / 4;
            tx = w - rw;
            ty = h - rh;
            stw = Math.max(1, w / 3);
            sth = Math.max(1, h / 3);
            shw = Math.max(stw, w * 0.75f);
            shh = Math.max(sth, h * 0.7f);
            shx = (w - shw) * 0.4f;
            shy = (h - shh) * 0.6f;
            shrinkTarget = texture(format, 1, data, stw + 1, sth + 1);
            meanTarget = texture(format, 1, data, 1, 1);
        }

        CgFrameBuffer texture(CgFrameBufferFormat format, int levels, float[] data) {
            return texture(format, levels, data, w, h);
        }

        CgFrameBuffer texture(CgFrameBufferFormat format, int levels, float[] data, int width, int height) {
            CgFrameBuffer fb = CgFrameBuffer.createOwned("ops-check " + name, width, height, format, levels);
            CgGpuTexture image = fb.rhiColor(0);
            ByteBuffer pixels = CgTexelConvert.fromRgba32f(data, image.desc().format(), width * height);
            CgDeviceSection.begin();
            try {
                CgRHI.get().encoder().writeTexture(image, CgTextureRegion.of2D(0, 0, 0, width, height), pixels);
            } finally {
                CgDeviceSection.end();
            }
            return fb;
        }

        void record(CgComputePass pass) {
            for (Filter filter : Filter.values()) {
                CgGpuOps.downsample(pass, CgGraphTexture.imported("chain", chains[filter.ordinal()].external()), filter);
            }
            CgGpuOps.blur(pass, CgGraphTexture.imported("blur source", blurSource.external()),
                    CgGraphTexture.imported("blur target", blurTarget.external()), SIGMA);
            CgGraphTexture level = CgGraphTexture.imported("level blur", levelBlur.external());
            CgGpuOps.downsample(pass, level, 0, 1, Filter.AVERAGE);
            CgGpuOps.blur(pass, level, 1, level, 1, LEVEL_SIGMA);
            CgGraphTexture source = CgGraphTexture.imported("blur source", blurSource.external());
            CgGpuOps.blur(pass, source, rx, ry, rw, rh, CgGraphTexture.imported("region target", regionTarget.external()), tx, ty,
                    SIGMA, LEVEL_SIGMA, CgGpuOps.Edge.MIRROR);
            CgGpuOps.blurScaled(pass, source, rx, ry, rw, rh, CgGraphTexture.imported("scaled target", scaledTarget.external()),
                    SCALED_SIGMA, CgGpuOps.Edge.MIRROR);
            CgGpuOps.shrink(pass, source, shx, shy, shw, shh, CgGraphTexture.imported("shrink target", shrinkTarget.external()),
                    1, 1, stw, sth);
            CgGpuOps.shrink(pass, source, 0, 0, w, h, CgGraphTexture.imported("mean target", meanTarget.external()), 0, 0, 1, 1);
        }

        void expect(List<String> failures, CgReplayedReads reads) {
            this.reads = reads;
            for (Filter filter : Filter.values()) {
                CgFrameBuffer chain = chains[filter.ordinal()];
                float[] above = read(chain, 0);
                for (int l = 1; l < chain.getColorLevels(); l++) {
                    float[] made = read(chain, l);
                    compare(failures, filter + " level " + l, made, downsample(above, l - 1, filter), l, 1);
                    above = made;
                }
            }
            float[] source = read(blurSource, 0);
            compare(failures, "blur", read(blurTarget, 0), blur(blur(source, 0, SIGMA, false), 0, SIGMA, true), 0, 2);
            float[] half = downsample(read(levelBlur, 0), 0, Filter.AVERAGE);
            compare(failures, "blur level 1 in place", read(levelBlur, 1),
                    blur(blur(half, 1, LEVEL_SIGMA, false), 1, LEVEL_SIGMA, true), 1, 3);

            float[] region = crop(source, w, rx, ry, rw, rh);
            float[] mirrored = blurMirrored(blurMirrored(region, rw, rh, SIGMA, false), rw, rh, LEVEL_SIGMA, true);
            compareRegion(failures, "region blur, mirrored", crop(read(regionTarget, 0), w, tx, ty, rw, rh), mirrored,
                    rw, 2 * ulp);
            int sx = rx / 2, sy = ry / 2, sw = (rx + rw + 1) / 2 - sx, sh = (ry + rh + 1) / 2 - sy;
            float[] reduced = new float[sw * sh * 4];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    for (int c = 0; c < 4; c++) {
                        double sum = 0;
                        for (int j = 0; j < 2; j++) {
                            for (int i = 0; i < 2; i++) {
                                int px = Math.max(rx, Math.min(2 * (sx + x) + i, rx + rw - 1));
                                int py = Math.max(ry, Math.min(2 * (sy + y) + j, ry + rh - 1));
                                sum += source[(py * w + px) * 4 + c];
                            }
                        }
                        reduced[(y * sw + x) * 4 + c] = (float) (sum / 4);
                    }
                }
            }
            float scaledSigma = Math.min(SCALED_SIGMA / 2, 4f);
            float[] scaled = blurMirrored(blurMirrored(reduced, sw, sh, scaledSigma, false), sw, sh, scaledSigma, true);
            // Two taps a bilinear fetch: the filter's weights are good to about 1/256
            compareRegion(failures, "scaled blur", crop(reads.rgba(level(scaledTarget, 0), (w + 1) / 2, (h + 1) / 2, type),
                    (w + 1) / 2, sx, sy, sw, sh), scaled, sw, 2 * ulp + 1.0 / 128);

            compareRegion(failures, "shrink", crop(reads.rgba(level(shrinkTarget, 0), stw + 1, sth + 1, type), stw + 1,
                    1, 1, stw, sth), shrink(source, shx, shy, shw, shh, stw, sth), stw, 4 * ulp);
            compareRegion(failures, "shrink to one texel", reads.rgba(level(meanTarget, 0), 1, 1, type),
                    shrink(source, 0, 0, w, h, 1, 1), 1, 4 * ulp);
        }

        /** The box {@code (x, y, bw, bh)} of the source area averaged into {@code tw x th}, as image.compute's shrink. */
        float[] shrink(float[] src, double x, double y, double bw, double bh, int tw, int th) {
            double scaleX = bw / tw, scaleY = bh / th;
            float[] out = new float[tw * th * 4];
            for (int ty = 0; ty < th; ty++) {
                for (int tx = 0; tx < tw; tx++) {
                    double x0 = x + tx * scaleX, x1 = Math.min(x0 + scaleX, x + bw);
                    double y0 = y + ty * scaleY, y1 = Math.min(y0 + scaleY, y + bh);
                    for (int c = 0; c < 4; c++) {
                        double sum = 0, total = 0;
                        for (int py = (int) Math.floor(y0); py < Math.ceil(y1); py++) {
                            double wy = Math.min(y1, py + 1) - Math.max(y0, py);
                            for (int px = (int) Math.floor(x0); px < Math.ceil(x1); px++) {
                                double weight = (Math.min(x1, px + 1) - Math.max(x0, px)) * wy;
                                sum += src[(py * w + px) * 4 + c] * weight;
                                total += weight;
                            }
                        }
                        out[(ty * tw + tx) * 4 + c] = (float) (sum / total);
                    }
                }
            }
            return out;
        }

        /** The {@code rw x rh} texels at {@code (x, y)} of an RGBA image {@code width} wide. */
        static float[] crop(float[] image, int width, int x, int y, int rw, int rh) {
            float[] out = new float[rw * rh * 4];
            for (int j = 0; j < rh; j++) System.arraycopy(image, ((y + j) * width + x) * 4, out, j * rw * 4, rw * 4);
            return out;
        }

        /** One axis of the Gaussian over a whole {@code n x m} image, its edges mirrored, the edge texel repeated. */
        static float[] blurMirrored(float[] src, int n, int m, float sigma, boolean vertical) {
            int radius = (int) Math.ceil(3 * sigma), span = vertical ? m : n;
            float[] out = new float[src.length];
            for (int y = 0; y < m; y++) {
                for (int x = 0; x < n; x++) {
                    for (int c = 0; c < 4; c++) {
                        double sum = 0, total = 0;
                        for (int k = -radius; k <= radius; k++) {
                            double weight = Math.exp(-0.5 * k * k / ((double) sigma * sigma));
                            int q = Math.floorMod((vertical ? y : x) + k, 2 * span);
                            int p = q < span ? q : 2 * span - 1 - q;
                            sum += src[((vertical ? p : y) * n + (vertical ? x : p)) * 4 + c] * weight;
                            total += weight;
                        }
                        out[(y * n + x) * 4 + c] = (float) (sum / total);
                    }
                }
            }
            return out;
        }

        /** Every channel of an {@code n}-wide region within {@code tolerance}, scaled to the value's size. */
        void compareRegion(List<String> failures, String op, float[] made, float[] want, int n, double tolerance) {
            int wrong = 0, firstAt = -1;
            for (int i = 0; i < want.length; i++) {
                if (i % 4 >= channels) continue;
                if (!(Math.abs(made[i] - want[i]) <= tolerance * Math.max(1, Math.abs(want[i])) + 1e-6) && wrong++ == 0) {
                    firstAt = i;
                }
            }
            if (wrong > 0) {
                int texel = firstAt / 4;
                failures.add(name + " " + op + " differs in " + wrong + " of " + want.length / 4 * channels
                        + " channels, the first at (" + texel % n + ", " + texel / n + ")." + firstAt % 4 + ": "
                        + made[firstAt] + ", not " + want[firstAt]);
            }
        }

        /** Level {@code level + 1} from {@code src}, level {@code level}, as image.compute folds it. */
        float[] downsample(float[] src, int level, Filter filter) {
            int n = size(w, level), m = size(h, level), tw = size(w, level + 1), th = size(h, level + 1);
            float[] out = new float[tw * th * 4];
            for (int y = 0; y < th; y++) {
                for (int x = 0; x < tw; x++) {
                    double[] wx = weights(x, n), wy = weights(y, m);
                    for (int c = 0; c < 4; c++) {
                        double acc = filter == Filter.MIN ? Double.MAX_VALUE : filter == Filter.MAX ? -Double.MAX_VALUE : 0;
                        for (int j = 0; j < 3; j++) {
                            for (int i = 0; i < 3; i++) {
                                double weight = wx[i] * wy[j];
                                if (weight <= 0) continue;
                                double v = src[((2 * y + j) * n + 2 * x + i) * 4 + c];
                                acc = filter == Filter.MIN ? Math.min(acc, v) : filter == Filter.MAX ? Math.max(acc, v) : acc + v * weight;
                            }
                        }
                        out[(y * tw + x) * 4 + c] = (float) acc;
                    }
                }
            }
            return out;
        }

        static double[] weights(int t, int n) {
            if (n == 1) return new double[] {1, 0, 0};
            if ((n & 1) == 0) return new double[] {0.5, 0.5, 0};
            int half = n >> 1;
            return new double[] {(double) (half - t) / n, (double) half / n, (double) (t + 1) / n};
        }

        /** One axis of the Gaussian at level {@code level}'s size, edges clamped. */
        float[] blur(float[] src, int level, float sigma, boolean vertical) {
            int n = size(w, level), m = size(h, level), radius = (int) Math.ceil(3 * sigma);
            float[] out = new float[src.length];
            for (int y = 0; y < m; y++) {
                for (int x = 0; x < n; x++) {
                    for (int c = 0; c < 4; c++) {
                        double sum = 0, total = 0;
                        for (int k = -radius; k <= radius; k++) {
                            double weight = Math.exp(-0.5 * k * k / ((double) sigma * sigma));
                            int sx = vertical ? x : Math.max(0, Math.min(x + k, n - 1)), sy = vertical ? Math.max(0, Math.min(y + k, m - 1)) : y;
                            sum += src[(sy * n + sx) * 4 + c] * weight;
                            total += weight;
                        }
                        out[(y * n + x) * 4 + c] = (float) (sum / total);
                    }
                }
            }
            return out;
        }

        /** Every texel's channels within {@code roundings} of the format's rounding, scaled to the value's size. */
        void compare(List<String> failures, String op, float[] made, float[] want, int level, int roundings) {
            int n = size(w, level), wrong = 0, firstAt = -1;
            for (int i = 0; i < want.length; i++) {
                if (i % 4 >= channels) continue;
                double tolerance = roundings * ulp * Math.max(1, Math.abs(want[i])) + 1e-6;
                if (!(Math.abs(made[i] - want[i]) <= tolerance) && wrong++ == 0) firstAt = i;
            }
            if (wrong > 0) {
                int texel = firstAt / 4;
                failures.add(name + " " + op + " differs in " + wrong + " of " + want.length / 4 * channels
                        + " channels, the first at (" + texel % n + ", " + texel / n + ")." + firstAt % 4 + ": "
                        + made[firstAt] + ", not " + want[firstAt]);
            }
        }

        /** Level {@code level} of the colour, as RGBA floats. */
        float[] read(CgFrameBuffer fb, int level) {
            return reads.rgba(level(fb, level), size(w, level), size(h, level), type);
        }

        /** Level {@code level} of {@code fb}'s colour, as a read takes it. */
        CgTextureView level(CgFrameBuffer fb, int level) {
            return CgTextureView.attachment(fb.rhiColor(0), level, 0);
        }

        static int size(int size, int level) {
            return Math.max(1, size >> level);
        }

        void delete() {
            for (CgFrameBuffer chain : chains) chain.delete();
            blurSource.delete();
            blurTarget.delete();
            levelBlur.delete();
            regionTarget.delete();
            scaledTarget.delete();
            shrinkTarget.delete();
            meanTarget.delete();
        }
    }

    /** Each block of sixteen folded: the level above. */
    private static int[] blocks(int[] v, IntBinaryOperator op, int identity) {
        int[] above = new int[(v.length + CgGpuOps.BLOCK - 1) / CgGpuOps.BLOCK];
        for (int j = 0; j < above.length; j++) {
            int acc = identity;
            for (int e = j * CgGpuOps.BLOCK; e < Math.min(v.length, (j + 1) * CgGpuOps.BLOCK); e++) acc = op.applyAsInt(acc, v[e]);
            above[j] = acc;
        }
        return above;
    }

    /** Each element's prefix: its block's, from the scanned sums above, then the block's own elements before it. */
    private static int[] scan(int[] v, int n, IntBinaryOperator op, int identity, boolean inclusive) {
        int[] values = Arrays.copyOf(v, n), prefixes = null;
        if (n > CgGpuOps.BLOCK) {
            int[] sums = blocks(values, op, identity);
            prefixes = scan(sums, sums.length, op, identity, false);
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int acc = prefixes != null ? prefixes[i / CgGpuOps.BLOCK] : identity;
            for (int e = i & ~(CgGpuOps.BLOCK - 1); e < (inclusive ? i + 1 : i); e++) acc = op.applyAsInt(acc, values[e]);
            out[i] = acc;
        }
        return out;
    }

    private static void expectWords(List<String> failures, String name, int[] made, int[] want) {
        int wrong = 0, firstAt = -1;
        for (int i = 0; i < want.length; i++) {
            if (made[i] != want[i] && wrong++ == 0) firstAt = i;
        }
        if (wrong > 0) {
            failures.add(name + " differs in " + wrong + " of " + want.length + " words, the first at " + firstAt + ": 0x"
                    + Integer.toHexString(made[firstAt]) + " (" + Float.intBitsToFloat(made[firstAt]) + "), not 0x"
                    + Integer.toHexString(want[firstAt]) + " (" + Float.intBitsToFloat(want[firstAt]) + ")");
        }
    }

    private interface WordOf {
        int at(int index);
    }

    private static int[] words(int count, WordOf word) {
        int[] w = new int[count];
        for (int i = 0; i < count; i++) w[i] = word.at(i);
        return w;
    }

    private static CgGraphBuffer imported(String name, int buffer, int words) {
        return CgGraphBuffer.imported(name, buffer, 4L * words);
    }

    /** A buffer of {@code count} sentinels. */
    private static int buffer(int count) {
        int[] words = new int[count];
        Arrays.fill(words, SENTINEL);
        return buffer(words);
    }

    private static int buffer(int[] words) {
        return CgCheckBuffers.words(words);
    }


}
