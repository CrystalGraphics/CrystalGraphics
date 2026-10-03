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

/**
 * Runs a kernel of every shape on this context and checks every buffer and image against values worked out in Java:
 * whether kernels give the right answers here, at whatever tier the context runs them.
 *
 * <pre>{@code
 * CgComputeSelfTest.Result result = CgComputeSelfTest.run();   // render thread, a live context
 * if (!result.passed()) result.failures().forEach(System.out::println);
 * }</pre>
 *
 * <p>A shipped jar runs it once, at the end of the first frame, with {@code -Dcrystalgraphics.compute.selfTest=true},
 * and logs {@code [crystalgraphics] compute self-test G40: PASS} beside the GPU report; {@code prodSmoke} gathers the
 * line. The harness's {@code compute-tiers} scene runs it at whatever tier it is forced to.</p>
 *
 * <ul>
 *   <li>It writes and reads back its own buffers and images, so it waits on the GPU: a diagnosis, never a frame's work.</li>
 *   <li>A kernel no tier can run must be refused where it is recorded; the test checks that refusal too.</li>
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

    /** Runs it once per process when {@code -Dcrystalgraphics.compute.selfTest=true}, logging the verdict. Render thread. */
    public static void runIfAsked() {
        if (ran || !Boolean.getBoolean("crystalgraphics.compute.selfTest")) return;
        ran = true;
        try {
            Result result = run();
            if (result.passed()) LOG.info("[crystalgraphics] compute self-test {} ({})", result.verdict(), result.forms().trim());
            else LOG.error("[crystalgraphics] compute self-test {} ({})", result.verdict(), result.forms().trim());
        } catch (RuntimeException | LinkageError failed) {
            LOG.error("[crystalgraphics] compute self-test " + CgCapabilities.detect().computeTier() + ": FAIL, threw " + failed, failed);
        }
    }

    /** Every kernel run and checked on the current context. Render thread; GL state is put back. */
    public static Result run() {
        try (CgGlScope ignored = CgGlState.saveAll()) {
            return new CgComputeSelfTest.Run().run();
        }
    }

    /** One run: its buffers, images and failures. */
    private static final class Run {
        private final CgCompute kernels = CgCompute.load(PATH);
        private final List<String> failures = new ArrayList<>();
        private final String tier = CgCapabilities.detect().computeTier().name();

        Result run() {
            giveBodies();
            StringBuilder forms = new StringBuilder();
            for (String name : List.of("Map", "Gather", "Append", "Histogram", "Paint", "Bin", "Doubled")) {
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
                pass.end();
                CgImmediate.execute(rec);

                expectWords("FLOATS", read(floats, N * 2), floats(N * 2, w -> {
                    int i = w / 2;
                    if (i % 5 == 0) return w % 2 == 0 ? i : -i;
                    return w % 2 == 0 ? 2 * i : 2 * i - 7;
                }));
                expectPairs(read(pairs, N * 8));
                expectWords("GATHERED", read(gathered, N), floats(N, i -> 3 * ((7 * i) % N) - 7 + 3 * i - 7));
                expectSpawns(read(spawnCount, 1)[0], read(spawns, SPAWN_CAPACITY * 2));
                expectWords("BINS", read(bins, 8), words(8, b -> 48));
                expectWords("MINS", read(mins, 4), floats(4, k -> k - 10));
                expectWords("MAXS", read(maxs, 4), words(4, k -> 2 * (60 + k)));
                expectWords("STORED", read(stored, N * 4), words(N * 4, w -> {
                    int j = w / 4, c = w % 4;
                    return c == 3 ? 7 : 63 - j + c;
                }));
                expectWords("COUNTED", read(counted, COUNTED), words(COUNTED, i -> i < 128 ? i + 100 : 0));
                expectWords("HALVES", read(halves, 2), new int[] {N / 2, N / 2});
                expectWords("BIG", read(big, BIG), words(BIG, i -> 3 * i + 1));
                expectMany(read(manyCount, 1)[0], read(many, MANY));
                expectWords("DOUBLED", read(doubled, COUNTED), words(COUNTED, i -> i < 128 ? 2 * (i + 100) : 0));
                expectWords("AFTER", read(after, COUNTED), words(COUNTED, i -> (i < 128 ? 2 * (i + 100) : 0) + 1));
                expectPicture(picture);
                expectAccum(accum);
                List<String> errors = drainErrors();
                if (!errors.isEmpty()) failures.add("GL errors " + errors);
            } finally {
                for (int b : new int[] {ints, floats, pairs, gathered, spawns, spawnCount, bins, mins, maxs, stored, counted,
                        halves, args, big, many, manyCount, doubled, after}) {
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

        /** A kernel that can run nowhere is refused where its dispatch is recorded, naming what stops it; on compute it runs. */
        private void expectStuck() {
            boolean compute = tier.equals("V") || tier.equals("G43");
            CgComputePass probe = new CgRecording().compute("stuck");
            try {
                probe.dispatch(kernels.kernel("Stuck"), N);
                if (!compute) failures.add("Stuck was recorded at " + tier + ", where nothing can run it");
            } catch (IllegalStateException e) {
                String want = tier.equals("CPU") ? "kernel.cpu" : "shared memory (stuck)";
                if (compute) failures.add("Stuck was refused on compute: " + e.getMessage());
                else if (!e.getMessage().contains(want)) failures.add("Stuck's refusal names no '" + want + "': " + e.getMessage());
            }
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
            ByteBuffer pixels = readPixels(picture, PICTURE, CgGL.GL_UNSIGNED_BYTE, 4);
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
            ByteBuffer pixels = readPixels(accum, ACCUM, CgGL.GL_FLOAT, 16);
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

    private static CgFrameBuffer image(String name, int size, CgFrameBufferFormat format, float r, float g, float b, float a) {
        CgFrameBuffer fbo = CgFrameBuffer.createOwned("compute_self_test_" + name, size, size, format);
        fbo.bind();
        CgGL.glViewport(0, 0, size, size);
        CgGL.glClearColor(r, g, b, a);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        fbo.unbind();
        return fbo;
    }

    private static ByteBuffer readPixels(CgFrameBuffer fbo, int size, int type, int bytesPerTexel) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * bytesPerTexel).order(ByteOrder.nativeOrder());
        fbo.bind();
        CgGL.glReadPixels(0, 0, size, size, CgGL.GL_RGBA, type, pixels);
        fbo.unbind();
        return pixels;
    }
}
