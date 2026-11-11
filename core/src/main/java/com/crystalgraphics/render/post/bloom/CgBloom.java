package com.crystalgraphics.render.post.bloom;

import java.util.Arrays;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.stage.CgFrameKey;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.world.CgGradeLayers;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * The post stack's bloom, Unreal's Gaussian layers ({@code BloomMethod} Standard): the HDR scene's ({@link CgFrameKeys#SCENE})
 * light past {@link #threshold}, halved, spread by six layers of separable Gaussians and added over the picture by the
 * composite, in linear light. Its settings are global, as every engine's are; {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none
 *
 * // Bevy's energy-conserving mode: the picture dimmed by the share the glow takes, so nothing gets brighter overall
 * CgPostStack.get().bloom().mode(CgBloom.Mode.ENERGY_CONSERVING).intensity(0.15f);
 * CgPostStack.get().bloom().tint(1f, 0.85f, 0.7f);   // a warm glow
 * CgPostStack.get().bloom().threshold(0.8f);         // light past 0.8 blooms, not past 1
 *
 * // A look of Unreal's: its layers, sizes and threshold as Unreal reads them
 * CgPostStack.get().volume(5, new CgPostSettings().gaussianBloom().bloomLayer(4, 12f, 0.1f, 0.1f, 0.1f));
 * }</pre>
 *
 * <ul>
 *   <li>Unless a volume asks for {@link CgPostSettings#gaussianBloom()}, it blooms by the engine's own layers
 *       ({@link #ENGINE_SIZES}): each channel's excess past {@link #threshold}, spread as the chain it replaced spread it,
 *       times a volume's {@code bloom}. A look that asks gets Unreal's: its layers, its {@code BloomThreshold} and its
 *       setup.</li>
 *   <li>Each graded layer ({@link CgGradeLayers}) blooms its own light by its own look, in the same dispatches as the
 *       frame's: one chain however many looks.</li>
 *   <li>Nothing emitted this firing, nothing recorded.</li>
 *   <li>Settings are read on the render thread and may be written from any: a float, read at most a frame late.</li>
 * </ul>
 */
public final class CgBloom implements CgPostEffect {

    /** How the glow meets the picture. */
    public enum Mode {
        /** Added: {@code picture + glow * intensity}. */
        ADDITIVE,
        /** Mixed in: {@code picture * (1 - intensity) + glow * intensity}, intensity 0 to 1 (Bevy's 0.15 is subtle). */
        ENERGY_CONSERVING
    }

    /**
     * The firing's bloom stages, the glow first and null past the last, a texture each: published for a later effect (the
     * debug view) to read. Read only.
     */
    public static final CgFrameKey<CgGraphTexture[]> LEVELS = CgFrameKey.of("crystalgraphics:bloom_levels", CgGraphTexture[].class);

    /** {@link #intensity(float)}'s default. */
    public static final float INTENSITY = 1f;

    /**
     * The engine's layers, percent of the screen's width at a size scale of 1: fitted to the chain they replaced (CoD:
     * Advanced Warfare's, 2026-11-04) by a model of both round one hot texel, within 3% at every radius.
     */
    static final float[] ENGINE_SIZES = {0f, 2.687f, 4.575f, 8.525f, 16.264f, 38.25f};
    /** And their tints at a {@code bloom} of 1, the composite taking a sixth of each: the chain's level weights. */
    static final float[] ENGINE_TINTS = {0f, 1.8368f, 1.4459f, 1.2088f, 0.9742f, 0.6965f};

    /** Unreal's stages: the half-size scene, then down to a 64th. */
    static final int STAGES = 6;
    /** SM5's most taps a pass ({@code MAX_FILTER_COMPILE_TIME_SAMPLES}). */
    static final int MAX_TAPS = 32;
    /** {@code r.FastBlurThreshold}: the radius in texels from which the horizontal pass draws at half width. */
    static final double FAST_BLUR_RADIUS = 7.0;
    /**
     * The shortest stage a layer blurs, in texels. Unreal's stages go to a 64th at any size, so below 1080p the last is a
     * few texels tall and its bilinear read smears a wide layer over the screen (54 times the chain's glow at a third of
     * the height, at 480p); a layer whose stage would be shorter blurs the deepest one this tall. Every stage passes at 1080p.
     */
    static final int SHORTEST_STAGE = 8;
    /** The blooms one chain runs: the frame's and each graded layer's ({@link CgGradeLayers#MAX}). */
    static final int BLOOMS = 1 + CgGradeLayers.MAX;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/post/bloom.compute";
    /** Unreal's bloom format; written from kernels as the media's R16F is. */
    private static final CgFormat FORMAT = CgFormat.RG11B10_UFLOAT;
    private static final int GPU_BLOOM = CgGpuTrace.name("post.bloom");

    private final Chain chain = new Chain();
    /** The engine's layers, for the frame and for each graded layer: {@link #engine} sets their bloom and threshold. */
    private final CgPostSettings engine = engineLayers();
    private final CgPostSettings[] layerEngine = {engineLayers(), engineLayers()};
    private float intensity = INTENSITY, tintR = 1f, tintG = 1f, tintB = 1f, threshold = 1f;
    private Mode mode = Mode.ADDITIVE;

    /** How strongly what emits blooms: {@value #INTENSITY} by default, 0 for none. */
    public CgBloom intensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    public CgBloom mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Mode mode() {
        return mode;
    }

    /** Multiplies the glow's colour: white by default. */
    public CgBloom tint(float r, float g, float b) {
        tintR = r;
        tintG = g;
        tintB = b;
        return this;
    }

    /**
     * Under the HDR scene, the linear brightness past which light blooms: 1 by default, the screen's white. A look of
     * Unreal's blooms past its own {@link CgPostSettings#bloomThreshold}.
     */
    public CgBloom threshold(float threshold) {
        this.threshold = Math.max(0f, threshold);
        return this;
    }

    public float threshold() {
        return threshold;
    }

    /** Starts the chain's kernels, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute kernels = CgCompute.load(KERNELS);
        for (String k : Chain.NAMES) kernels.kernel(k).precache();
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_COMPOSITE;
    }

    @Override
    public boolean active(CgPostContext post) {
        return intensity > 0f && (post.resources().has(CgGradeLayers.KEY) || post.settings().bloom() > 0f
                && post.resources().has(CgFrameKeys.SCENE));
    }

    @Override
    public void record(CgPostContext post) {
        CgGraphTexture scene = post.resources().get(CgFrameKeys.SCENE);
        if (scene == null) return;
        CgGradeLayers graded = post.resources().get(CgGradeLayers.KEY);
        Chain c = chain;
        c.begin(scene, graded);
        int frame = -1;
        if (post.settings().bloom() > 0f) frame = c.add(Chain.FRAME, shape(post.settings(), engine, threshold));
        int[] layer = {-1, -1};
        for (int k = 0; graded != null && k < CgGradeLayers.MAX; k++) {
            CgPostSettings look = graded.look(k);
            if (look == null || look.bloom() <= 0f) continue;
            float t = look.overrides(CgPostSettings.BLOOM_THRESHOLD) ? look.bloomThreshold() : threshold;
            layer[k] = c.add(Chain.LAYER0 + k, shape(look, layerEngine[k], t));
        }
        if (!c.record(post.recording())) return;
        if (frame >= 0) {
            post.resources().put(LEVELS, c.levels(frame));
            post.composite().bloom(c.glow(frame), intensity, tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
            post.composite().linear();
        }
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (layer[k] >= 0) post.composite().layerBloom(k, c.glow(layer[k]), intensity);
        }
    }

    /** {@code look} where it asks for Unreal's layers, else {@code engine} at its bloom and {@code threshold}. */
    private static CgPostSettings shape(CgPostSettings look, CgPostSettings engine, float threshold) {
        if (look.bloomsGaussian()) return look;
        return engine.bloom(look.bloom()).bloomThreshold(threshold);
    }

    /** The engine's layers on each channel's excess, at a size scale of 1. */
    private static CgPostSettings engineLayers() {
        CgPostSettings s = new CgPostSettings().gaussianBloom().bloomExcess().bloomSizeScale(1f);
        for (int l = 1; l <= STAGES; l++) {
            float t = ENGINE_TINTS[l - 1];
            s.bloomLayer(l, ENGINE_SIZES[l - 1], t, t, t);
        }
        return s;
    }

    /** {@code GetBlurRadius}: a kernel of {@code percent} of a {@code width}-texel viewport, as a radius in texels. */
    static double radius(int width, float percent) {
        return width * percent * 0.01 * 0.5;
    }

    /**
     * {@code Compute1DGaussianFilterKernel} at {@code r.Filter.SizeScale} 1 and no cross: offsets in texels and
     * normalised weights into {@code out} (offset, weight pairs), the taps answered. Unreal's bilinear pairing, the last
     * tap on its texel's centre.
     */
    static int kernel(double radius, double[] out) {
        double clamped = Math.max(1e-8, Math.min(radius, MAX_TAPS - 1));
        int integer = (int) Math.min(Math.ceil(clamped), MAX_TAPS - 1);
        int count = 0;
        double sum = 0;
        for (int i = -integer; i <= integer; i += 2) {
            double w0 = normal(i, clamped), w1 = i != integer ? normal(i + 1, clamped) : 0;
            double total = w0 + w1;
            out[count * 2] = i + w1 / total;
            out[count * 2 + 1] = total;
            sum += total;
            count++;
        }
        for (int i = 0; i < count; i++) out[i * 2 + 1] /= sum;
        return count;
    }

    /** {@code NormalDistributionUnscaled} with no cross: {@code exp(-16.7 (x / sigma)^2)}. */
    private static double normal(double x, double sigma) {
        double d = Math.abs(x) / sigma;
        return Math.exp(-16.7 * d * d);
    }

    /**
     * Unreal's Gaussian layers as compute, every bloom of the firing in one chain of dispatches ({@code bloom.compute}):
     * each bloom's source at half size, downsampled to a 64th by Unreal's high-quality chain (four bilinear taps, a 4x4
     * box), then from the 64th up each layer a separable Gaussian of its stage, its vertical pass adding the layer
     * before. Stage 0's layer holds the glow, which the composite adds whole. Each bloom has its own targets and its own
     * rows; a dispatch's z is the bloom.
     *
     * <p>Follows 5.1's {@code AddGaussianBloomPasses} (PostProcessBloomSetup.cpp): layer {@code i} blurs stage
     * {@code i - 1} with a kernel of {@code Bloom<i>Size * BloomSizeScale} percent of that stage's width, its weights times
     * {@code Bloom<i>Tint * BloomIntensity / 6}; {@code Compute1DGaussianFilterKernel} (PostProcessWeightedSampleSum.cpp)
     * for the taps, and its fast blur ({@code r.FastBlurThreshold} 7): from a radius of 7 texels the horizontal pass
     * draws at half width, which the vertical pass reads bilinearly. Unlike Unreal's, a layer blurs no stage shorter
     * than {@link #SHORTEST_STAGE}: its stage is {@code at[layer]}.</p>
     */
    private static final class Chain {

        static final String[] NAMES = {"Source", "Down", "Across", "Layer"};
        /** What a bloom reads: the frame's scene less the graded layers' light, or a graded layer's own. */
        static final int FRAME = 0, LAYER0 = 1;
        /** {@code bloom.compute}'s rows: a bloom's source rows, then a block a stage and bloom. */
        static final int SOURCE_ROWS = 2, BLOCK = 2 + 2 * MAX_TAPS, ROWS = SOURCE_ROWS * BLOOMS + STAGES * BLOOMS * BLOCK;
        private static final String[] OUT = {"OUT0", "OUT1", "OUT2"}, IN = {"_In0", "_In1", "_In2"},
                ADD = {"_Add0", "_Add1", "_Add2"};

        private final Bloom[] blooms = {new Bloom(), new Bloom(), new Bloom()};
        private int count;
        private CgGraphTexture scene;
        @Nullable
        private CgGradeLayers graded;
        private final float[] rows = new float[ROWS * 4], uploaded = new float[ROWS * 4];
        private CgGraphBuffer buffer;
        private final CgKernel[] kernels = new CgKernel[NAMES.length];
        private final double[] kernel = new double[MAX_TAPS * 2];
        /** A 1x1 target a dispatch binds where it runs fewer blooms than it declares. */
        private CgGraphTexture unused;

        void begin(CgGraphTexture scene, @Nullable CgGradeLayers graded) {
            this.scene = scene;
            this.graded = graded;
            count = 0;
        }

        /** Adds a bloom of {@code source} ({@link #FRAME}, or {@link #LAYER0} + k) by {@code look}; its index. */
        int add(int source, CgPostSettings look) {
            Bloom b = blooms[count];
            b.source = source;
            b.look = look;
            return count++;
        }

        CgGraphTexture glow(int b) {
            return blooms[b].layers[0];
        }

        CgGraphTexture[] levels(int b) {
            return blooms[b].layers;
        }

        /** Records every bloom added since {@link #begin} in one compute pass; false, recording nothing, where no layer has a size. */
        boolean record(CgRecording recording) {
            if (count == 0) return false;
            for (int k = 0; k < NAMES.length; k++) if (kernels[k] == null) kernels[k] = CgCompute.load(KERNELS).kernel(NAMES[k]);
            int width = 1, height = 1, deepest = 0;
            boolean any = false;
            for (int i = 0; i < count; i++) {
                Bloom b = blooms[i];
                b.size(sourceOf(b.source));
                width = Math.max(width, b.width);
                height = Math.max(height, b.height);
                deepest = Math.max(deepest, b.deepest);   // a bloom with fewer stages reads none of the deeper ones
                any |= b.weigh(this, i);
            }
            if (!any) return false;
            if (buffer == null) {
                buffer = CgGraphBuffer.persistent("cg_bloom.rows", CgBufferDesc.of(ROWS * 16L, CgBufferUsage.STORAGE,
                        CgBufferUsage.COPY));
                Arrays.fill(uploaded, Float.NaN);
            }
            if (!Arrays.equals(rows, uploaded)) {
                recording.update(buffer, 0, rows, 0, rows.length);
                System.arraycopy(rows, 0, uploaded, 0, rows.length);
            }
            if (unused == null) unused = CgGraphTexture.transientTexture("cg_bloom_unused", new CgTextureDesc(1, 1, FORMAT));
            CgComputePass pass = recording.compute("cg.bloom").timed(GPU_BLOOM);
            CgDispatch d = pass.dispatch(kernels[0], width, height, count).bind("ROWS", buffer).texture("_Scene", scene);
            if (graded != null && graded.layer(0) != null) d.texture("_Layer0", graded.layer(0));
            if (graded != null && graded.layer(1) != null) d.texture("_Layer1", graded.layer(1));
            outs(d, -1, Pass.SOURCE);
            for (int k = 0; k < deepest; k++) {
                d = pass.dispatch(kernels[1], Math.max(1, width >> (k + 1)), Math.max(1, height >> (k + 1)), count);
                ins(d, k, Pass.DOWN);
                outs(d, k, Pass.DOWN);
            }
            for (int stage = STAGES - 1; stage >= 0; stage--) {
                if (!drawsAny(stage)) continue;
                int sw = 1, sh = 1;
                for (int i = 0; i < count; i++) {
                    sw = Math.max(sw, blooms[i].layers[stage].getWidth());
                    sh = Math.max(sh, blooms[i].layers[stage].getHeight());
                }
                d = pass.dispatch(kernels[2], sw, sh, count).bind("ROWS", buffer).set("_Stage", stage);
                ins(d, stage, Pass.ACROSS);
                outs(d, stage, Pass.ACROSS);
                d = pass.dispatch(kernels[3], sw, sh, count).bind("ROWS", buffer).set("_Stage", stage);
                ins(d, stage, Pass.LAYER);
                outs(d, stage, Pass.LAYER);
            }
            pass.end();
            return true;
        }

        private enum Pass { SOURCE, DOWN, ACROSS, LAYER }

        private boolean drawsAny(int stage) {
            for (int i = 0; i < count; i++) if (blooms[i].drawn[stage]) return true;
            return false;
        }

        /** Each bloom's input at {@code stage} (or downsample {@code stage}) bound; a bloom slot not run binds nothing. */
        private void ins(CgDispatch d, int stage, Pass p) {
            for (int i = 0; i < count; i++) {
                Bloom b = blooms[i];
                switch (p) {
                    case DOWN -> d.texture(IN[i], stage == 0 ? b.half : b.down[stage - 1]);
                    case ACROSS -> d.texture(IN[i], b.level(stage));
                    case LAYER -> {
                        d.texture(IN[i], b.fast[stage] ? b.acrossHalf[stage] : b.across[stage]);
                        if (b.before[stage] >= 0) d.texture(ADD[i], b.layers[b.before[stage]]);
                    }
                    default -> { }
                }
            }
        }

        /** Each bloom's output bound, and every slot past the blooms run to the unused 1x1. */
        private void outs(CgDispatch d, int stage, Pass p) {
            for (int i = 0; i < BLOOMS; i++) {
                if (i >= count) {
                    d.image(OUT[i], unused);
                    continue;
                }
                Bloom b = blooms[i];
                d.image(OUT[i], switch (p) {
                    case SOURCE -> b.half;
                    case DOWN -> b.down[stage];
                    case ACROSS -> b.fast[stage] ? b.acrossHalf[stage] : b.across[stage];
                    case LAYER -> b.layers[stage];
                });
            }
        }

        private CgGraphTexture sourceOf(int source) {
            if (source == FRAME || graded == null) return scene;
            CgGraphTexture layer = graded.layer(source - LAYER0);
            return layer != null ? layer : scene;
        }

        /** Row {@code row}'s four floats. */
        void put(int row, float x, float y, float z, float w) {
            rows[row * 4] = x;
            rows[row * 4 + 1] = y;
            rows[row * 4 + 2] = z;
            rows[row * 4 + 3] = w;
        }

        void release() {
            for (Bloom b : blooms) b.release();
            Arrays.fill(kernels, null);
            buffer = null;
            unused = null;
            scene = null;
            graded = null;
            count = 0;
        }

        /** One bloom's targets, its stages' state, and what its rows were last written for. */
        private final class Bloom {
            int source;
            CgPostSettings look;
            CgGraphTexture half;
            final CgGraphTexture[] down = new CgGraphTexture[STAGES - 1], across = new CgGraphTexture[STAGES],
                    acrossHalf = new CgGraphTexture[STAGES], layers = new CgGraphTexture[STAGES];
            int width, height, deepest;
            /** The stage each layer blurs. */
            final int[] at = new int[STAGES];
            final boolean[] fast = new boolean[STAGES], drawn = new boolean[STAGES];
            /** The stage each stage's vertical pass adds, -1 none. */
            final int[] before = new int[STAGES];
            /** Per stage: what its rows were written for (percent, width, height, tint, before, slot), NaN for never. */
            final float[] bound = new float[STAGES * 8];

            /** Its targets for a source of {@code source}'s size: made again when the size moved. */
            void size(CgGraphTexture source) {
                int w = Math.max(1, source.getWidth() / 2), h = Math.max(1, source.getHeight() / 2);
                if (half != null && width == w && height == h) return;
                width = w;
                height = h;
                half = CgGraphTexture.transientTexture("cg_bloom_half", new CgTextureDesc(w, h, FORMAT));
                deepest = 0;
                while (deepest < STAGES - 1 && (h >> (deepest + 1)) >= SHORTEST_STAGE) deepest++;
                for (int stage = 0; stage < STAGES; stage++) {
                    at[stage] = Math.min(stage, deepest);
                    if (stage > 0) {
                        down[stage - 1] = CgGraphTexture.transientTexture("cg_bloom_down",
                                new CgTextureDesc(Math.max(1, w >> stage), Math.max(1, h >> stage), FORMAT));
                    }
                    int sw = Math.max(1, w >> at[stage]), sh = Math.max(1, h >> at[stage]);
                    across[stage] = CgGraphTexture.transientTexture("cg_bloom_across", new CgTextureDesc(sw, sh, FORMAT));
                    acrossHalf[stage] = CgGraphTexture.transientTexture("cg_bloom_across_half",
                            new CgTextureDesc(Math.max(1, sw / 2), sh, FORMAT));
                    layers[stage] = CgGraphTexture.transientTexture("cg_bloom_layers", new CgTextureDesc(sw, sh, FORMAT));
                }
                Arrays.fill(bound, Float.NaN);
            }

            /** The level a stage's horizontal pass blurs: the half-size source, or a downsample. */
            CgGraphTexture level(int stage) {
                return at[stage] == 0 ? half : down[at[stage] - 1];
            }

            /**
             * Writes its rows as slot {@code slot}: its source's, and from the 64th in, each stage it draws (its layer has
             * a size, or it is the half-size stage under one that drew) with its taps and tint. False where none draws.
             */
            boolean weigh(Chain chain, int slot) {
                boolean unreal = !look.bloomsExcess();
                // The excess form subtracts its threshold, so Unreal's -1 (the whole scene) is 0 there
                float threshold = unreal ? look.bloomThreshold() : Math.max(0f, look.bloomThreshold());
                boolean frame = source == FRAME && graded != null;
                chain.put(slot * SOURCE_ROWS, source, threshold, unreal ? 1f : 0f, 0f);
                chain.put(slot * SOURCE_ROWS + 1, frame && graded.layer(0) != null ? 1f : 0f,
                        frame && graded.layer(1) != null ? 1f : 0f, 0f, 0f);
                float tintScale = look.bloom() / STAGES;
                int last = -1;
                for (int stage = STAGES - 1; stage >= 0; stage--) {
                    drawn[stage] = look.bloomSize(stage + 1) * look.bloomSizeScale() > 1e-8f || stage == 0 && last >= 0;
                    before[stage] = drawn[stage] ? last : -1;
                    if (!drawn[stage]) {
                        chain.put(block(stage, slot), -1f, 0f, 0f, 0f);
                        bound[stage * 8] = Float.NaN;   // its rows now say so: drawn again, they are written again
                        continue;
                    }
                    taps(chain, slot, stage, tintScale);
                    last = stage;
                }
                return last >= 0;
            }

            private int block(int stage, int slot) {
                return SOURCE_ROWS * BLOOMS + (stage * BLOOMS + slot) * BLOCK;
            }

            /** Stage {@code stage}'s info, tint and taps as slot {@code slot}, recomputed when what they depend on moved. */
            private void taps(Chain chain, int slot, int stage, float tintScale) {
                int w = layers[stage].getWidth(), h = layers[stage].getHeight();
                float percent = look.bloomSize(stage + 1) * look.bloomSizeScale();
                float r = look.bloomTint(stage + 1, 0) * tintScale, g = look.bloomTint(stage + 1, 1) * tintScale,
                        b = look.bloomTint(stage + 1, 2) * tintScale;
                int at = stage * 8;
                if (bound[at] == percent && bound[at + 1] == w && bound[at + 2] == h && bound[at + 3] == r
                        && bound[at + 4] == g && bound[at + 5] == b && bound[at + 6] == before[stage] && bound[at + 7] == slot) {
                    return;
                }
                bound[at] = percent;
                bound[at + 1] = w;
                bound[at + 2] = h;
                bound[at + 3] = r;
                bound[at + 4] = g;
                bound[at + 5] = b;
                bound[at + 6] = before[stage];
                bound[at + 7] = slot;
                double radius = radius(w, percent);
                int n = percent > 1e-8f ? kernel(radius, chain.kernel) : 0;
                fast[stage] = n > 0 && radius >= FAST_BLUR_RADIUS;
                int row = block(stage, slot);
                chain.put(row, n, fast[stage] ? 1f : 0f, before[stage] >= 0 ? 1f : 0f, 0f);
                chain.put(row + 1, r, g, b, 0f);
                // Offsets stay in the source's texels: a half-width target's texel centres fall between two of them
                for (int i = 0; i < MAX_TAPS; i++) {
                    boolean on = i < n;
                    chain.put(row + 2 + i, on ? (float) (chain.kernel[i * 2] / w) : 0f, 0f,
                            on ? (float) chain.kernel[i * 2 + 1] : 0f, 0f);
                    chain.put(row + 2 + MAX_TAPS + i, 0f, on ? (float) (chain.kernel[i * 2] / h) : 0f,
                            on ? (float) chain.kernel[i * 2 + 1] : 0f, 0f);
                }
            }

            void release() {
                half = null;
                Arrays.fill(down, null);
                Arrays.fill(across, null);
                Arrays.fill(acrossHalf, null);
                Arrays.fill(layers, null);
                Arrays.fill(bound, Float.NaN);
                look = null;
            }
        }
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        chain.release();
    }
}
