package com.crystalgraphics.compute.ops;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.ops.CgGpuOps.Element;
import com.crystalgraphics.compute.ops.CgGpuOps.Fold;
import com.crystalgraphics.compute.ops.CgGpuOps.Order;
import com.crystalgraphics.compute.ops.CgGpuOps.Scan;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
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
 * GPU, and checks each answer against Java bit for bit: floats included, since every tier folds in the same order.
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
                pass.dispatch(check.kernel("Rng"), DRAWN).bind("DRAWN", CgGraphBuffer.imported("drawn", drawn, DRAWN * 16L))
                        .set("_Seed", SEED);
                pass.end();
                CgImmediate.execute(rec);

                for (Case c : cases) c.expect(failures);
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
            }
            return new Result(CgCapabilities.detect().computeTier().name(), List.copyOf(failures));
        }
    }

    /** One count: its inputs, every op's output buffer, and what each must hold. */
    private static final class Case {
        final String name;
        final int n, capacity, words;
        /** The count's word on the GPU, -1 for a fixed count. */
        final int countWord;
        final int[] uints, ints, floats, flags, points, cells;
        final int gpuUints, gpuInts, gpuFloats, gpuFlags, gpuPoints, count, fill, iota, copy, args, results, scanA, scanB,
                scanC, indices, values, sortedKeys, sortedValues, sortedFloats, sortedInts, cellKeys, cellValues, bins;

        Case(String name, int n, int capacity, int countWord) {
            this.name = name;
            this.n = n;
            this.capacity = capacity;
            this.countWord = countWord;
            words = capacity + PAD;
            uints = new int[words];
            ints = new int[words];
            floats = new int[words];
            flags = new int[words];
            points = new int[4 * words];
            cells = new int[words];
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
            }
            gpuUints = buffer(uints);
            gpuInts = buffer(ints);
            gpuFloats = buffer(floats);
            gpuFlags = buffer(flags);
            gpuPoints = buffer(points);
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
        }

        void record(CgComputePass pass) {
            CgGpuCount at = countWord < 0 ? CgGpuCount.of(n) : CgGpuCount.at(imported("count", count, 3), 1, capacity);
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
            for (int b : new int[] {gpuUints, gpuInts, gpuFloats, gpuFlags, gpuPoints, count, fill, iota, copy, args,
                    results, scanA, scanB, scanC, indices, values, sortedKeys, sortedValues, sortedFloats, sortedInts,
                    cellKeys, cellValues, bins}) {
                CgGL.glDeleteBuffers(b);
            }
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
