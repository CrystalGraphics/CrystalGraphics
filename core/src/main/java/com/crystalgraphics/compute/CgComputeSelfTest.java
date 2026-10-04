package com.crystalgraphics.compute;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.cpu.CgCpuImage;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs a kernel of every shape on this context and checks every buffer and image against values worked out in Java:
 * whether kernels give the right answers here, at whatever tier the context runs them.
 *
 * <pre>{@code
 * CgComputeSelfTest.Result result = CgComputeSelfTest.run();   // render thread, a live context
 * if (!result.passed()) result.failures().forEach(System.out::println);
 * }</pre>
 *
 * <p>A shipped jar runs it once with {@code -Dcrystalgraphics.compute.selfTest=true}, from the end of the first frame,
 * and logs {@code [crystalgraphics] compute self-test G40: PASS} a frame or two later, once its reads have arrived
 * ({@link CgReplayedReads}); {@code prodSmoke} gathers the line. The harness's {@code compute-tiers} scene runs it at
 * whatever tier it is forced to.</p>
 *
 * <ul>
 *   <li>{@link #run()} reads back its own buffers and images at once, so it waits on the GPU: a diagnosis, never a
 *       frame's work, and refused where the host submits. {@link #runIfAsked()} never waits.</li>
 *   <li>A kernel some tier cannot run must be refused where it is recorded, on every machine; a {@code compute_only}
 *       one below compute too. The test checks both refusals.</li>
 *   <li>The builtins newer than GLSL 3.30 run polyfilled below 4.00 and 4.20, and must give the bits Java works
 *       out.</li>
 * </ul>
 */
public final class CgComputeSelfTest {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final String PATH = "crystalgraphics:shaders/env/compute/self_test.compute";
    private static final int N = 64, SPAWN_CAPACITY = 128, COUNTED = 256, PICTURE = 64, ACCUM = 16;
    /** Enough elements for the CPU tier to split them into ranges, and the appends they make. */
    private static final int BIG = 200_000, MANY = (BIG + 2) / 3;
    private static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("compute_self_test_rgba8")
            .color(0, CgTextureType.RGBA8).build();
    private static final CgFrameBufferFormat RGBA32F = CgFrameBufferFormat.builder("compute_self_test_rgba32f")
            .color(0, CgTextureType.RGBA32F).build();
    private static boolean ran;
    /** The first run's reads, while they arrive. */
    private static CgReplayedReads requested;

    /**
     * @param tier     the tier the context ran kernels at
     * @param forms    each kernel's form, {@code Name=HOW} and the fallback that ran in brackets
     * @param failures every value that differed, empty when it passed
     */
    public record Result(String tier, String forms, List<String> failures) {
        public boolean passed() { return failures.isEmpty(); }

        /** {@code G40: PASS}, or {@code G40: FAIL, 2 differences: ...}. */
        public String verdict() {
            return tier + (passed() ? ": PASS" : ": FAIL, " + failures.size() + " differences: " + String.join("; ", failures));
        }
    }

    private CgComputeSelfTest() {}

    /**
     * With {@code -Dcrystalgraphics.compute.selfTest=true}, once per process: run with every read requested, then run
     * again once they have arrived, a frame or two later, and log the verdict. No frame waits on the GPU, so it runs
     * where the host submits too. Render thread, once a frame.
     */
    public static void runIfAsked() {
        if (!Boolean.getBoolean("crystalgraphics.compute.selfTest")) return;
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
            LOG.error("[crystalgraphics] compute self-test " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    private static void report(Supplier<Result> run, String lost) {
        try {
            Result result = run.get();
            if (lost != null) LOG.error("[crystalgraphics] compute self-test {}: FAIL, a read never arrived: {}", result.tier(), lost);
            else if (result.passed()) LOG.info("[crystalgraphics] compute self-test {} ({})", result.verdict(), result.forms().trim());
            else LOG.error("[crystalgraphics] compute self-test {} ({})", result.verdict(), result.forms().trim());
        } catch (RuntimeException | LinkageError failed) {
            LOG.error("[crystalgraphics] compute self-test " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    /** Every kernel run and checked on the current context, waiting on the GPU. Render thread; GL state is put back. */
    public static Result run() {
        return run(CgReplayedReads.immediate());
    }

    /** {@link #run()}, reading through {@code reads}. */
    public static Result run(CgReplayedReads reads) {
        try (CgGlScope ignored = CgGlState.saveAll()) {
            return new CgComputeSelfTest.Run(reads).run();
        }
    }

    /** One run: its buffers, images and failures. */
    private static final class Run {
        private final CgCompute kernels = CgCompute.load(PATH);
        private final List<String> failures = new ArrayList<>();
        private final String tier = CgCapabilities.detect().computeTier().name();
        private final CgReplayedReads reads;

        Run(CgReplayedReads reads) {
            this.reads = reads;
        }

        Result run() {
            giveBodies();
            StringBuilder forms = new StringBuilder();
            for (String name : List.of("Map", "Gather", "Append", "Histogram", "Paint", "Bin", "Doubled", "Bits")) {
                CgKernelForm form = kernels.kernel(name).form();
                forms.append(' ').append(name).append('=').append(form.how())
                        .append(form.runs().name().equals(name) ? "" : "(" + form.runs().name() + ")");
            }
            drainErrors();

            int ints = buffer(words(N, i -> 3 * i - 7));
            int floats = buffer(floats(N * 2, w -> w % 2 == 0 ? w / 2 : -(w / 2)));
            int pairs = buffer(pairs());
            int gathered = buffer(new int[N]);
            int spawns = buffer(new int[SPAWN_CAPACITY * 2]);
            int spawnCount = buffer(new int[] {0xdead});
            int bins = buffer(new int[8]);
            int mins = buffer(floats(4, w -> 1000));
            int maxs = buffer(words(4, w -> -1000));
            int stored = buffer(new int[N * 4]);
            int counted = buffer(new int[COUNTED]);
            int halves = buffer(new int[2]);
            int args = buffer(new int[] {1, 1, 1, 2, 1, 1});
            int big = buffer(new int[BIG]);
            int many = buffer(new int[MANY + 16]);
            int manyCount = buffer(new int[] {0xdead});
            int doubled = buffer(new int[COUNTED]);
            int after = buffer(new int[COUNTED]);
            int only = buffer(new int[N]);
            int bits = buffer(new int[N * BIT_WORDS]);
            CgFrameBuffer picture = image("picture", PICTURE, RGBA8, 0f, 0f, 0f, 1f);
            CgFrameBuffer accum = image("accum", ACCUM, RGBA32F, 0f, 0f, 0f, 0f);
            try {
                CgGraphBuffer gInts = CgGraphBuffer.imported("ints", ints, N * 4);
                CgGraphBuffer gFloats = CgGraphBuffer.imported("floats", floats, N * 8);
                CgGraphBuffer gPairs = CgGraphBuffer.imported("pairs", pairs, N * 32);
                CgGraphBuffer gGathered = CgGraphBuffer.imported("gathered", gathered, N * 4);
                CgGraphBuffer gSpawns = CgGraphBuffer.imported("spawns", spawns, SPAWN_CAPACITY * 8);
                CgGraphBuffer gSpawnCount = CgGraphBuffer.imported("spawn-count", spawnCount, 4);
                CgGraphBuffer gBins = CgGraphBuffer.imported("bins", bins, 32);
                CgGraphBuffer gMins = CgGraphBuffer.imported("mins", mins, 16);
                CgGraphBuffer gMaxs = CgGraphBuffer.imported("maxs", maxs, 16);
                CgGraphBuffer gStored = CgGraphBuffer.imported("stored", stored, N * 16);
                CgGraphBuffer gCounted = CgGraphBuffer.imported("counted", counted, COUNTED * 4);
                CgGraphBuffer gHalves = CgGraphBuffer.imported("halves", halves, 8);
                CgGraphBuffer gArgs = CgGraphBuffer.imported("args", args, 24);
                CgGraphBuffer gBig = CgGraphBuffer.imported("big", big, BIG * 4L);
                CgGraphBuffer gMany = CgGraphBuffer.imported("many", many, (MANY + 16) * 4L);
                CgGraphBuffer gManyCount = CgGraphBuffer.imported("many-count", manyCount, 4);
                CgGraphBuffer gDoubled = CgGraphBuffer.imported("doubled", doubled, COUNTED * 4L);
                CgGraphBuffer gAfter = CgGraphBuffer.imported("after", after, COUNTED * 4L);
                CgGraphBuffer gOnly = CgGraphBuffer.imported("only", only, N * 4L);
                CgGraphBuffer gBits = CgGraphBuffer.imported("bits", bits, N * BIT_WORDS * 4L);
                CgGraphTexture gPicture = CgGraphTexture.imported("picture", picture);
                CgGraphTexture gAccum = CgGraphTexture.imported("accum", accum);

                expectStuck();
                CgRecording rec = new CgRecording();
                rec.fill(gSpawnCount, 0);
                rec.fill(gBins, 0);
                rec.fill(gHalves, 0);
                rec.fill(gManyCount, 0);
                CgComputePass pass = rec.compute("compute-self-test");
                pass.dispatch(kernels.kernel("Map"), N).bind("INTS", gInts).bind("FLOATS", gFloats);
                pass.dispatch(kernels.kernel("Pairs"), N).bind("PAIRS", gPairs);
                pass.dispatch(kernels.kernel("Gather"), N).bind("INTS", gInts).bind("GATHERED", gGathered);
                pass.dispatch(kernels.kernel("Append"), N).bind("SPAWNS", gSpawns).counter("SPAWNS", gSpawnCount, 0);
                pass.dispatch(kernels.kernel("Append"), N).bind("SPAWNS", gSpawns).counter("SPAWNS", gSpawnCount, 0);
                pass.dispatchIndirect(kernels.kernel("Append"), gArgs, 0).bind("SPAWNS", gSpawns).counter("SPAWNS", gSpawnCount, 0);
                pass.dispatch(kernels.kernel("Histogram"), N).bind("BINS", gBins);
                pass.dispatchIndirect(kernels.kernel("Histogram"), gArgs, 0).bind("BINS", gBins);
                pass.dispatch(kernels.kernel("Extremes"), N).bind("MINS", gMins).bind("MAXS", gMaxs);
                pass.dispatch(kernels.kernel("Store"), N).bind("STORED", gStored);
                pass.dispatch(kernels.kernel("Paint"), PICTURE, PICTURE, 1).image("PICTURE", gPicture);
                pass.dispatch(kernels.kernel("Accumulate"), ACCUM, ACCUM, 1).image("ACCUM", gAccum);
                pass.dispatch(kernels.kernel("Accumulate"), ACCUM, ACCUM, 1).image("ACCUM", gAccum);
                pass.dispatchIndirect(kernels.kernel("Counted"), gArgs, 12).bind("COUNTED", gCounted);
                pass.dispatch(kernels.kernel("Bin"), N).bind("HALVES", gHalves);
                pass.dispatch(kernels.kernel("Big"), BIG).bind("BIG", gBig);
                pass.dispatch(kernels.kernel("Many"), BIG).bind("MANY", gMany).counter("MANY", gManyCount, 0);
                pass.dispatch(kernels.kernel("Doubled"), COUNTED).bind("COUNTED", gCounted).bind("DOUBLED", gDoubled);
                pass.dispatch(kernels.kernel("After"), COUNTED).bind("DOUBLED", gDoubled).bind("AFTER", gAfter);
                boolean onlyRuns = expectOnly(pass);
                if (onlyRuns) pass.dispatch(kernels.kernel("Only"), N).bind("ONLY", gOnly);
                pass.dispatch(kernels.kernel("Bits"), N).bind("BITS", gBits);
                pass.end();
                CgImmediate.execute(rec);

                expectWords("FLOATS", reads.words(floats, N * 2), floats(N * 2, w -> {
                    int i = w / 2;
                    if (i % 5 == 0) return w % 2 == 0 ? i : -i;
                    return w % 2 == 0 ? 2 * i : 2 * i - 7;
                }));
                expectPairs(reads.words(pairs, N * 8));
                expectWords("GATHERED", reads.words(gathered, N), floats(N, i -> 3 * ((7 * i) % N) - 7 + 3 * i - 7));
                expectSpawns(reads.words(spawnCount, 1)[0], reads.words(spawns, SPAWN_CAPACITY * 2));
                expectWords("BINS", reads.words(bins, 8), words(8, b -> 48));
                expectWords("MINS", reads.words(mins, 4), floats(4, k -> k - 10));
                expectWords("MAXS", reads.words(maxs, 4), words(4, k -> 2 * (60 + k)));
                expectWords("STORED", reads.words(stored, N * 4), words(N * 4, w -> {
                    int j = w / 4, c = w % 4;
                    return c == 3 ? 7 : 63 - j + c;
                }));
                expectWords("COUNTED", reads.words(counted, COUNTED), words(COUNTED, i -> i < 128 ? i + 100 : 0));
                expectWords("HALVES", reads.words(halves, 2), new int[] {N / 2, N / 2});
                expectWords("BIG", reads.words(big, BIG), words(BIG, i -> 3 * i + 1));
                expectMany(reads.words(manyCount, 1)[0], reads.words(many, MANY));
                expectWords("DOUBLED", reads.words(doubled, COUNTED), words(COUNTED, i -> i < 128 ? 2 * (i + 100) : 0));
                expectWords("AFTER", reads.words(after, COUNTED), words(COUNTED, i -> (i < 128 ? 2 * (i + 100) : 0) + 1));
                if (onlyRuns) expectWords("ONLY", reads.words(only, N), words(N, i -> 5 * i + 1));
                expectWords("BITS", reads.words(bits, N * BIT_WORDS), bits(tier.equals("V") || tier.equals("G43")));
                expectPicture(picture);
                expectAccum(accum);
                List<String> errors = drainErrors();
                if (!errors.isEmpty()) failures.add("GL errors " + errors);
            } finally {
                for (int b : new int[] {ints, floats, pairs, gathered, spawns, spawnCount, bins, mins, maxs, stored, counted,
                        halves, args, big, many, manyCount, doubled, after, only, bits}) {
                    CgGL.glDeleteBuffers(b);
                }
                picture.delete();
                accum.delete();
            }
            return new Result(tier, forms.toString(), List.copyOf(failures));
        }

        /** Every kernel's Java body: what the CPU tier runs. */
        private void giveBodies() {
            kernels.kernel("Map").cpu(d -> {
                CgCpuBuffer in = d.buffer("INTS"), f = d.buffer("FLOATS");
                for (int e = d.first(); e < d.end(); e++) {
                    if (e % 5 == 0) continue;
                    f.setFloat(e, 0, f.getFloat(e, 0) * 2f);
                    f.setFloat(e, 1, f.getFloat(e, 1) + in.getInt(e));
                }
            });
            kernels.kernel("Pairs").cpu(d -> {
                CgCpuBuffer p = d.buffer("PAIRS");
                int a = p.field("a").word(), b = p.field("b").word();
                for (int e = d.first(); e < d.end(); e++) {
                    for (int c = 0; c < 4; c++) {
                        p.setFloat(e, a + c, p.getFloat(e, a + c) * 2f + 1f);
                        p.setInt(e, b + c, p.getInt(e, b + c) + e);
                    }
                }
            });
            kernels.kernel("Gather").cpu(d -> {
                CgCpuBuffer in = d.buffer("INTS"), out = d.buffer("GATHERED");
                for (int e = d.first(); e < d.end(); e++) out.setFloat(e, in.getInt((e * 7) % in.length()) + in.getInt(e));
            });
            kernels.kernel("Append").cpu(d -> {
                CgCpuBuffer spawned = d.appended("SPAWNS");
                for (int e = d.first(); e < d.end(); e++) {
                    if (e % 3 == 0) {
                        int at = d.append("SPAWNS");
                        spawned.setInt(at, 0, e);
                        spawned.setInt(at, 1, e * e);
                    }
                    if (e % 9 == 0) spawned.setInt(d.append("SPAWNS"), 0, e + 1000);
                }
            });
            kernels.kernel("Histogram").cpu(d -> {
                CgCpuBuffer bins = d.buffer("BINS");
                for (int e = d.first(); e < d.end(); e++) {
                    bins.addInt(e % 8, 1);
                    bins.addInt(e % 8, 2);
                }
            });
            kernels.kernel("Extremes").cpu(d -> {
                CgCpuBuffer mins = d.buffer("MINS"), maxs = d.buffer("MAXS");
                for (int e = d.first(); e < d.end(); e++) {
                    mins.minFloat(e % 4, e - 10f);
                    maxs.maxInt(e % 4, e * 2);
                }
            });
            kernels.kernel("Store").cpu(d -> {
                CgCpuBuffer out = d.buffer("STORED");
                for (int e = d.first(); e < d.end(); e++) {
                    int j = out.length() - 1 - e;
                    out.setInt(j, 0, e);
                    out.setInt(j, 1, e + 1);
                    out.setInt(j, 2, e + 2);
                    out.setInt(j, 3, 7);
                }
            });
            kernels.kernel("Paint").cpu(d -> {
                CgCpuImage image = d.image("PICTURE");
                for (int e = d.first(); e < d.end(); e++) {
                    int x = d.x(e), y = d.y(e);
                    if ((x + y) % 2 == 0) image.store(x, y, 0, x / 63f, y / 63f, 1f, 1f);
                }
            });
            kernels.kernel("Accumulate").cpu(d -> {
                CgCpuImage image = d.image("ACCUM");
                for (int e = d.first(); e < d.end(); e++) {
                    int x = d.x(e), y = d.y(e);
                    image.store(x, y, 0, image.loadFloat(x, y, 0, 0) + 1f, image.loadFloat(x, y, 0, 1) + x,
                            image.loadFloat(x, y, 0, 2) + y, image.loadFloat(x, y, 0, 3));
                }
            });
            kernels.kernel("Counted").cpu(d -> {
                CgCpuBuffer out = d.buffer("COUNTED");
                for (int e = d.first(); e < d.end(); e++) out.setInt(e, e + 100);
            });
            kernels.kernel("Big").cpu(d -> {
                CgCpuBuffer out = d.buffer("BIG");
                for (int e = d.first(); e < d.end(); e++) out.setInt(e, 3 * e + 1);
            });
            kernels.kernel("Many").cpu(d -> {
                CgCpuBuffer spawned = d.appended("MANY");
                for (int e = d.first(); e < d.end(); e++) if (e % 3 == 0) spawned.setInt(d.append("MANY"), e);
            });
            kernels.kernel("Doubled").cpu(d -> {
                CgCpuBuffer in = d.buffer("COUNTED"), out = d.buffer("DOUBLED");
                for (int e = d.first(); e < d.end(); e++) out.setInt(e, in.getInt(e) * 2);
            });
            kernels.kernel("After").cpu(d -> {
                CgCpuBuffer in = d.buffer("DOUBLED"), out = d.buffer("AFTER");
                for (int e = d.first(); e < d.end(); e++) out.setInt(e, in.getInt(e) + 1);
            });
            kernels.kernel("Bits").cpu(d -> {
                CgCpuBuffer out = d.buffer("BITS");
                for (int e = d.first(); e < d.end(); e++) {
                    int[] w = bitsOf(e);
                    for (int k = 0; k < BIT_WORDS; k++) out.setInt(e, k, w[k]);
                }
            });
            kernels.kernel("Bin").cpu(d -> {
                CgCpuBuffer halves = d.buffer("HALVES");
                for (int e = d.first(); e < d.end(); e++) halves.addInt(e % 2, 1);
            });
        }

        private void expectPairs(int[] made) {
            int[] want = new int[N * 8];
            for (int i = 0; i < N; i++) {
                for (int c = 0; c < 4; c++) {
                    want[i * 8 + c] = Float.floatToRawIntBits(2f * (i + c) + 1f);
                    want[i * 8 + 4 + c] = i * (c + 1) + i;
                }
            }
            expectWords("PAIRS", made, want);
        }

        /** Three dispatches' appends, in any order: each of the base set three times. */
        private void expectSpawns(int count, int[] made) {
            if (count != 90) {
                failures.add("SPAWNS' count is " + count + ", not 90");
                return;
            }
            List<Long> want = new ArrayList<>(), got = new ArrayList<>();
            for (int repeat = 0; repeat < 3; repeat++) {
                for (int i = 0; i < N; i++) {
                    if (i % 3 == 0) want.add(pack(i, i * i));
                    if (i % 9 == 0) want.add(pack(i + 1000, 0));
                }
            }
            for (int i = 0; i < count; i++) got.add(pack(made[i * 2], made[i * 2 + 1]));
            want.sort(null);
            got.sort(null);
            if (!want.equals(got)) failures.add("SPAWNS holds " + got + ", not " + want);
        }

        /**
         * A kernel G40 cannot run is refused where its dispatch is recorded on every tier, this one's included, naming
         * the tier and what stops it.
         */
        private void expectStuck() {
            CgComputePass probe = new CgRecording().compute("stuck");
            try {
                probe.dispatch(kernels.kernel("Stuck"), N);
                failures.add("Stuck was recorded at " + tier + ", though G40 can run it nowhere");
            } catch (IllegalStateException e) {
                for (String want : new String[] {"tier G40", "shared memory (stuck)", "compute_only"}) {
                    if (!e.getMessage().contains(want)) failures.add("Stuck's refusal names no '" + want + "': " + e.getMessage());
                }
            }
        }

        /** A compute_only kernel runs on compute; below it, kernel.runs() is false and a dispatch is refused by name. */
        private boolean expectOnly(CgComputePass pass) {
            boolean compute = tier.equals("V") || tier.equals("G43");
            CgKernel kernel = kernels.kernel("Only");
            if (kernel.runs() != compute) failures.add("Only.runs() is " + kernel.runs() + " at " + tier);
            if (compute) return true;
            try {
                new CgRecording().compute("only").dispatch(kernel, N);
                failures.add("Only was recorded at " + tier + ", below compute");
            } catch (IllegalStateException e) {
                if (!e.getMessage().contains("compute_only")) failures.add("Only's refusal names no compute_only: " + e.getMessage());
            }
            return false;
        }

        /** Every third element of BIG appended once: in element order on the CPU tier, in any order on the GPU. */
        private void expectMany(int count, int[] made) {
            boolean ordered = tier.equals("CPU");
            if (count != MANY) {
                failures.add("MANY's count is " + count + ", not " + MANY);
                return;
            }
            int[] got = Arrays.copyOf(made, MANY);
            if (!ordered) Arrays.sort(got);
            expectWords("MANY" + (ordered ? " (in element order)" : ""), got, words(MANY, i -> 3 * i));
        }

        private void expectPicture(CgFrameBuffer picture) {
            ByteBuffer pixels = reads.pixels(picture.getId(), PICTURE, PICTURE, CgTextureType.RGBA8);
            int wrong = 0;
            String first = null;
            for (int y = 0; y < PICTURE; y++) {
                for (int x = 0; x < PICTURE; x++) {
                    int at = (y * PICTURE + x) * 4;
                    int[] made = {pixels.get(at) & 255, pixels.get(at + 1) & 255, pixels.get(at + 2) & 255, pixels.get(at + 3) & 255};
                    int[] want = (x + y) % 2 == 0
                            ? new int[] {Math.round(x / 63f * 255f), Math.round(y / 63f * 255f), 255, 255}
                            : new int[] {0, 0, 0, 255};
                    if (within(made, want, 1)) continue;
                    if (wrong++ == 0) first = "(" + x + ", " + y + ") is " + Arrays.toString(made) + ", not " + Arrays.toString(want);
                }
            }
            if (wrong > 0) failures.add("PICTURE differs in " + wrong + " texels, " + first);
        }

        private void expectAccum(CgFrameBuffer accum) {
            ByteBuffer pixels = reads.pixels(accum.getId(), ACCUM, ACCUM, CgTextureType.RGBA32F);
            int wrong = 0;
            String first = null;
            for (int y = 0; y < ACCUM; y++) {
                for (int x = 0; x < ACCUM; x++) {
                    int at = (y * ACCUM + x) * 16;
                    float[] made = {pixels.getFloat(at), pixels.getFloat(at + 4), pixels.getFloat(at + 8), pixels.getFloat(at + 12)};
                    float[] want = {2f, 2f * x, 2f * y, 0f};
                    if (Arrays.equals(made, want)) continue;
                    if (wrong++ == 0) first = "(" + x + ", " + y + ") is " + Arrays.toString(made) + ", not " + Arrays.toString(want);
                }
            }
            if (wrong > 0) failures.add("ACCUM differs in " + wrong + " texels, " + first);
        }

        private void expectWords(String name, int[] made, int[] want) {
            int wrong = 0, firstAt = -1;
            for (int i = 0; i < want.length; i++) {
                if (made[i] != want[i] && wrong++ == 0) firstAt = i;
            }
            if (wrong > 0) {
                failures.add(name + " differs in " + wrong + " of " + want.length + " words, the first at " + firstAt + ": "
                        + made[firstAt] + " (as a float " + Float.intBitsToFloat(made[firstAt]) + "), not " + want[firstAt]
                        + " (" + Float.intBitsToFloat(want[firstAt]) + ")");
            }
        }
    }

    /** Words in one {@code BitResults}: eight uvec4. */
    private static final int BIT_WORDS = 32;
    /** Where {@code BitResults.h} starts: rounding a native builtin leaves to its driver, zero on compute. */
    private static final int DRIVER_ROUNDED = 28;

    private static int[] bits(boolean compute) {
        int[] w = new int[N * BIT_WORDS];
        for (int i = 0; i < N; i++) {
            System.arraycopy(bitsOf(i), 0, w, i * BIT_WORDS, compute ? DRIVER_ROUNDED : BIT_WORDS);
        }
        return w;
    }

    /** What {@code Bits} writes for element {@code i} below compute, each builtin as GLSL defines it. */
    private static int[] bitsOf(int i) {
        int x = i * (int) 2654435761L + 12345;
        int y = (x ^ (x >>> 13)) * 1540483477;
        int insert = ((1 << 13) - 1) << 7;
        long sum = (x & 0xFFFFFFFFL) + (y & 0xFFFFFFFFL);
        long unsigned = (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
        long signed = (long) x * (long) y;
        int[] src = {x, x >>> 8, y, y >>> 8};
        float[] v = new float[4];
        for (int c = 0; c < 4; c++) v[c] = (((src[c] & 0xFF) | 1) - 128) * 0.015625f;
        int unorm4 = 0, snorm4 = 0;
        for (int c = 0; c < 4; c++) {
            unorm4 |= Math.round(clamp(v[c], 0f, 1f) * 255f) << (8 * c);
            snorm4 |= (Math.round(clamp(v[c], -1f, 1f) * 127f) & 0xFF) << (8 * c);
        }
        int unorm2 = Math.round(clamp(v[0], 0f, 1f) * 65535f) | Math.round(clamp(v[1], 0f, 1f) * 65535f) << 16;
        int snorm2 = (Math.round(clamp(v[2], -1f, 1f) * 32767f) & 0xFFFF) | (Math.round(clamp(v[3], -1f, 1f) * 32767f) & 0xFFFF) << 16;
        int snormTrip4 = 0;
        for (int c = 0; c < 4; c++) snormTrip4 |= (Math.max((byte) (y >>> (8 * c)), -127) & 0xFF) << (8 * c);
        int snormTrip2 = (Math.max((short) y, -32767) & 0xFFFF) | (Math.max((short) (y >>> 16), -32767) & 0xFFFF) << 16;
        float hx = Float.intBitsToFloat((x & 0x807FE000) | ((113 + Integer.remainderUnsigned(y, 30)) << 23));
        float hy = Float.intBitsToFloat((y & 0x807FE000) | ((113 + Integer.remainderUnsigned(x, 30)) << 23));
        int half = ((y >>> 16) & 0x83FF) | ((1 + Integer.remainderUnsigned(x >>> 8, 30)) << 10);
        int hb = Float.floatToRawIntBits(hx);
        int exponent = ((hb >>> 23) & 0xFF) - 126;
        int mantissa = (hb & 0x807FFFFF) | 0x3F000000;
        float rx = Float.intBitsToFloat((x & 0x807FFFFF) | ((100 + Integer.remainderUnsigned(y, 32)) << 23));
        float ry = Float.intBitsToFloat((y & 0x807FFFFF) | ((100 + Integer.remainderUnsigned(x, 32)) << 23));
        return new int[] {
                (x >>> 5) & ((1 << 11) - 1), (x << 20) >> 23, (x & ~insert) | ((y << 7) & insert), Integer.reverse(x),
                Integer.bitCount(x), findLsb(x), findMsb(x), y < 0 ? findMsb(~y) : findMsb(y),
                (int) sum, x - y, (int) (sum >>> 32) | (Integer.compareUnsigned(x, y) < 0 ? 2 : 0), (int) (unsigned >>> 32),
                x * y, (int) (signed >> 32), x * y, Float.floatToRawIntBits(i * 3f + 1f),
                unorm4, snorm4, unorm2, snorm2,
                x, snormTrip4, x, snormTrip2,
                halfBits(hx) | halfBits(hy) << 16, Float.floatToRawIntBits(halfFloat(half)), mantissa ^ exponent, hb,
                halfBits(rx) | halfBits(ry) << 16, Float.floatToRawIntBits(halfFloat(y & 0x83FF)), 0, 0};
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int findLsb(int v) {
        return v == 0 ? -1 : Integer.numberOfTrailingZeros(v);
    }

    private static int findMsb(int v) {
        return v == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(v);
    }

    /** A float as half-float bits, rounded to nearest even: what packHalf2x16 holds. Java 8 has no Float.floatToFloat16. */
    private static int halfBits(float f) {
        int x = Float.floatToRawIntBits(f);
        int sign = (x >>> 16) & 0x8000, e = (x >>> 23) & 0xFF, m = x & 0x7FFFFF;
        if (e == 0xFF) return sign | 0x7C00 | (m != 0 ? 0x200 | (m >>> 13) : 0);
        int ex = e - 112;
        if (ex >= 31) return sign | 0x7C00;
        if (ex <= 0) {
            if (ex < -10) return sign;
            m |= 0x800000;
            int shift = 14 - ex;
            int h = m >>> shift, rest = m & ((1 << shift) - 1), half = 1 << (shift - 1);
            if (rest > half || (rest == half && (h & 1) != 0)) h++;
            return sign | h;
        }
        int h = (ex << 10) | (m >>> 13), rest = m & 0x1FFF;
        if (rest > 0x1000 || (rest == 0x1000 && (h & 1) != 0)) h++;
        return sign | h;
    }

    private static float halfFloat(int h) {
        int sign = (h & 0x8000) << 16, e = (h >>> 10) & 0x1F, m = h & 0x3FF;
        if (e == 0) return m == 0 ? Float.intBitsToFloat(sign) : (sign != 0 ? -1f : 1f) * m * 5.9604644775390625e-8f;
        if (e == 31) return Float.intBitsToFloat(sign | 0x7F800000 | (m << 13));
        return Float.intBitsToFloat(sign | ((e + 112) << 23) | (m << 13));
    }

    /** Whether every component is within {@code tolerance}: a unorm store may round either way. */
    private static boolean within(int[] made, int[] want, int tolerance) {
        for (int c = 0; c < made.length; c++) if (Math.abs(made[c] - want[c]) > tolerance) return false;
        return true;
    }

    private static List<String> drainErrors() {
        List<String> errors = new ArrayList<>();
        for (int error, guard = 0; (error = CgGL.glGetError()) != CgGL.GL_NO_ERROR && guard < 16; guard++) {
            errors.add("0x" + Integer.toHexString(error));
        }
        return errors;
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private interface WordOf {
        int at(int index);
    }

    private static int[] words(int count, WordOf word) {
        int[] w = new int[count];
        for (int i = 0; i < count; i++) w[i] = word.at(i);
        return w;
    }

    /** Words holding floats, each the value {@code value} answers. */
    private static int[] floats(int count, WordOf value) {
        int[] w = new int[count];
        for (int i = 0; i < count; i++) w[i] = Float.floatToRawIntBits(value.at(i));
        return w;
    }

    private static int[] pairs() {
        int[] w = new int[N * 8];
        for (int i = 0; i < N; i++) {
            for (int c = 0; c < 4; c++) {
                w[i * 8 + c] = Float.floatToRawIntBits(i + c);
                w[i * 8 + 4 + c] = i * (c + 1);
            }
        }
        return w;
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

    private static CgFrameBuffer image(String name, int size, CgFrameBufferFormat format, float r, float g, float b, float a) {
        CgFrameBuffer fbo = CgFrameBuffer.createOwned("compute_self_test_" + name, size, size, format);
        fbo.bind();
        CgGL.glViewport(0, 0, size, size);
        CgGL.glClearColor(r, g, b, a);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        fbo.unbind();
        return fbo;
    }
}
