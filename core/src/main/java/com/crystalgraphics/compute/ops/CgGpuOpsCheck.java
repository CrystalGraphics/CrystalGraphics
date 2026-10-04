package com.crystalgraphics.compute.ops;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.ops.CgGpuOps.Element;
import com.crystalgraphics.compute.ops.CgGpuOps.Filter;
import com.crystalgraphics.compute.ops.CgGpuOps.Fold;
import com.crystalgraphics.compute.ops.CgGpuOps.Order;
import com.crystalgraphics.compute.ops.CgGpuOps.Scan;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntBinaryOperator;

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
 *       {@code [crystalgraphics] gpu ops check G40: PASS}; the harness's {@code gpu-ops} scene runs it at any tier.</li>
 *   <li>It reads its buffers back, so it waits on the GPU: a diagnosis, never a frame's work.</li>
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
    private static final float SIGMA = 1.6f, LEVEL_SIGMA = 0.8f;
    private static boolean ran;

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

    /** Runs it once per process when {@code -Dcrystalgraphics.compute.selfTest=true}, logging the verdict. Render thread. */
    public static void runIfAsked() {
        if (ran || !Boolean.getBoolean("crystalgraphics.compute.selfTest")) return;
        ran = true;
        try {
            Result result = run();
            if (result.passed()) LOG.info("[crystalgraphics] gpu ops check {}", result.verdict());
            else LOG.error("[crystalgraphics] gpu ops check {}", result.verdict());
        } catch (RuntimeException | LinkageError failed) {
            LOG.error("[crystalgraphics] gpu ops check " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    /** Every op run and checked on the current context. Render thread; GL state is put back. */
    public static Result run() {
        try (CgGlScope ignored = CgGlState.saveAll()) {
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
            check.kernel("Rng").cpu(d -> {
                CgCpuBuffer drawn = d.buffer("DRAWN");
                int seed = d.propertyInt("_Seed");
                int[] r = new int[4];
                for (int e = d.first(); e < d.end(); e++) {
                    CgRng.rng4(seed, e, 3, 1, r);
                    for (int c = 0; c < 4; c++) drawn.setInt(e, c, r[c]);
                }
            });
            drainErrors();
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

                for (Case c : cases) c.expect(failures);
                for (ImageCase c : images) c.expect(failures);
                int[] r = new int[4], want = new int[DRAWN * 4];
                for (int e = 0; e < DRAWN; e++) {
                    CgRng.rng4(SEED, e, 3, 1, r);
                    System.arraycopy(r, 0, want, e * 4, 4);
                }
                expectWords(failures, "rng", read(drawn, DRAWN * 4), want);
                List<String> errors = drainErrors();
                if (!errors.isEmpty()) failures.add("GL errors " + errors);
            } finally {
                CgGL.glDeleteBuffers(drawn);
                for (Case c : cases) c.delete();
                for (ImageCase c : images) c.delete();
            }
            return new Result(CgCapabilities.detect().computeTier().name(), List.copyOf(failures));
        }
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

        void expect(List<String> failures) {
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
            expectWords(failures, name + " args", read(args, 8),
                    new int[] {SENTINEL, (n + 63) / 64, 1, 1, SENTINEL, SENTINEL, SENTINEL, SENTINEL});
            int[] box = {0x7F800000, 0x7F800000, 0x7F800000, 0xFF800000, 0xFF800000, 0xFF800000};
            for (int e = 0; e < n; e++) {
                for (int c = 0; c < 3; c++) {
                    box[c] = Float.intBitsToFloat(points[4 * e + c]) < Float.intBitsToFloat(box[c]) ? points[4 * e + c] : box[c];
                    box[3 + c] = maxFloat.applyAsInt(box[3 + c], points[4 * e + c]);
                }
            }
            expectWords(failures, name + " results", read(results, RESULTS), new int[] {
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
            expectWords(failures, name + " histogram", read(bins, 64), binWords);

            int[] starts = scan(lengths, n, sum, 0, false);
            int total = n == 0 ? 0 : starts[n - 1] + lengths[n - 1];
            expectWords(failures, name + " expand total", read(count, 3), new int[] {SENTINEL, countWord, total});
            int[] pairs = new int[2 * outElements];
            Arrays.fill(pairs, SENTINEL);
            for (int r = 0, j = 0; r < n && j < outElements; r++) {
                for (int k = 0; k < lengths[r] && j < outElements; k++, j++) {
                    pairs[2 * j] = r;
                    pairs[2 * j + 1] = k;
                }
            }
            expectWords(failures, name + " expand", read(expanded, 2 * outElements), pairs);
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
            expectWords(failures, name + " " + op, read(buffer, words), full);
        }

        void delete() {
            for (int b : new int[] {gpuUints, gpuInts, gpuFloats, gpuFlags, gpuPoints, gpuLengths, count, fill, iota, copy,
                    args, results, scanA, scanB, scanC, indices, values, sortedKeys, sortedValues, sortedFloats, sortedInts,
                    cellKeys, cellValues, bins, expanded}) {
                CgGL.glDeleteBuffers(b);
            }
        }
    }

    /**
     * One format at one size: a chain per filter, a blur into another texture, and level 1 of a chain blurred in place.
     * Inputs are read back as stored, so an expectation starts from the format's own values.
     */
    private static final class ImageCase {
        final String name;
        final CgTextureType type;
        final int w, h, channels;
        /** One rounding to the format, at a value of 1: a texel may differ by it per write. */
        final double ulp;
        final CgFrameBuffer[] chains = new CgFrameBuffer[Filter.values().length];
        final CgFrameBuffer blurSource, blurTarget, levelBlur;

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
        }

        CgFrameBuffer texture(CgFrameBufferFormat format, int levels, float[] data) {
            CgFrameBuffer fb = CgFrameBuffer.createOwned("ops-check " + name, w, h, format, levels);
            ByteBuffer pixels = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
            for (float v : data) pixels.putFloat(v);
            pixels.flip();
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
            CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
            return fb;
        }

        void record(CgComputePass pass) {
            for (Filter filter : Filter.values()) {
                CgGpuOps.downsample(pass, CgGraphTexture.imported("chain", chains[filter.ordinal()]), filter);
            }
            CgGpuOps.blur(pass, CgGraphTexture.imported("blur source", blurSource),
                    CgGraphTexture.imported("blur target", blurTarget), SIGMA);
            CgGraphTexture level = CgGraphTexture.imported("level blur", levelBlur);
            CgGpuOps.downsample(pass, level, 0, 1, Filter.AVERAGE);
            CgGpuOps.blur(pass, level, 1, level, 1, LEVEL_SIGMA);
        }

        void expect(List<String> failures) {
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
            int n = size(w, level) * size(h, level) * 4;
            ByteBuffer pixels = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
            CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, level, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
            float[] out = new float[n];
            for (int i = 0; i < n; i++) out[i] = pixels.getFloat(i * 4);
            return out;
        }

        static int size(int size, int level) {
            return Math.max(1, size >> level);
        }

        void delete() {
            for (CgFrameBuffer chain : chains) chain.delete();
            blurSource.delete();
            blurTarget.delete();
            levelBlur.delete();
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

    private static List<String> drainErrors() {
        List<String> errors = new ArrayList<>();
        for (int error, guard = 0; (error = CgGL.glGetError()) != CgGL.GL_NO_ERROR && guard < 16; guard++) {
            errors.add("0x" + Integer.toHexString(error));
        }
        return errors;
    }

    /** A buffer of {@code count} sentinels. */
    private static int buffer(int count) {
        int[] words = new int[count];
        Arrays.fill(words, SENTINEL);
        return buffer(words);
    }

    private static int buffer(int[] words) {
        ByteBuffer data = ByteBuffer.allocateDirect(words.length * 4).order(ByteOrder.nativeOrder());
        for (int w : words) data.putInt(w);
        data.flip();
        int buffer = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, data, CgGL.GL_DYNAMIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        return buffer;
    }

    private static int[] read(int buffer, int count) {
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, buffer);
        ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_COPY_READ_BUFFER, 0, count * 4L, CgGL.GL_MAP_READ_BIT, null);
        int[] words = new int[count];
        mapped.order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) words[i] = mapped.getInt(i * 4);
        CgGL.glUnmapBuffer(CgGL.GL_COPY_READ_BUFFER);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        return words;
    }
}
