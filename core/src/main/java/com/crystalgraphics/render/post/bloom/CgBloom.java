package com.crystalgraphics.render.post.bloom;

import java.util.Arrays;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
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

/**
 * The post stack's bloom, Unreal's Gaussian layers ({@code BloomMethod} Standard): the HDR scene's ({@link CgFrameKeys#SCENE})
 * light past {@link #threshold}, halved, spread by six layers of separable Gaussians and added over the picture by the
 * composite. Its settings are global, as every engine's are; {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none
 *
 * // Bevy's energy-conserving mode: the picture dimmed by the share the glow takes, so nothing gets brighter overall
 * CgPostStack.get().bloom().mode(CgBloom.Mode.ENERGY_CONSERVING).intensity(0.15f);
 * CgPostStack.get().bloom().tint(1f, 0.85f, 0.7f);   // a warm glow
 * CgPostStack.get().bloom().linear(true);            // composite in linear light, at the cost of a copy of the target
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

    private static final String EXCESS = "crystalgraphics:shaders/post/bloom/excess.shader";
    private static final String DOWN = "crystalgraphics:shaders/post/bloom/down.shader";
    private static final String GAUSSIAN = "crystalgraphics:shaders/post/bloom/gaussian.shader";
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("cg_bloom")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_EXCESS = CgGpuTrace.name("post.bloom.excess");
    private static final int GPU_DOWN = CgGpuTrace.name("post.bloom.down");
    private static final int[] GPU_LAYER = new int[STAGES];
    private static final CgLoad CLEAR = CgLoad.clear(0f, 0f, 0f, 0f);

    static {
        for (int stage = 0; stage < STAGES; stage++) GPU_LAYER[stage] = CgGpuTrace.name("post.bloom.layer" + (stage + 1));
    }

    private static final CgVar.Bool LINEAR = CgVar.bool("crystalgraphics.post.bloom.linear", false, CgVar.DEV,
            "Bloom starts composited in linear light (the copy form), to compare with the default blend form; L cycles"
                    + " blend, linear and off in any harness scene with a 3D camera.");

    private final Source source = new Source("cg_bloom_excess");
    private final Layers layers = new Layers();
    /** Each graded layer's own bloom, from its own light, by its look. */
    private final Source[] layerSource = {new Source("cg_bloom_layer0"), new Source("cg_bloom_layer1")};
    private final Layers[] layerLayers = {new Layers(), new Layers()};
    /** The engine's layers, for the frame and for each graded layer: {@link #engine} sets their bloom and threshold. */
    private final CgPostSettings engine = engineLayers();
    private final CgPostSettings[] layerEngine = {engineLayers(), engineLayers()};
    private float intensity = INTENSITY, tintR = 1f, tintG = 1f, tintB = 1f, threshold = 1f;
    private Mode mode = Mode.ADDITIVE;
    private boolean linear = LINEAR.get();

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
     * Whether it composites in linear light: decoding the target from sRGB, adding, and encoding again, which needs a
     * copy of the target. Off by default: the glow is added in the target's encoding, an even step in perceived
     * brightness, so over dark pixels it is fainter than linear light would make it and over bright ones stronger
     * (a glow of 0.1 over an encoded 0.2 shows 0.073 linear where linear light gives 0.133; over 0.8, 0.787 for 0.704).
     */
    public CgBloom linear(boolean linear) {
        this.linear = linear;
        return this;
    }

    public boolean linear() {
        return linear;
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
        CgGradeLayers graded = scene != null ? post.resources().get(CgGradeLayers.KEY) : null;
        if (graded != null) recordLayers(post, graded);
        if (post.settings().bloom() <= 0f || scene == null) return;
        CgPostSettings look = shape(post.settings(), engine, threshold);
        CgGraphTexture half = source.record(post, scene, look, graded);
        CgGraphTexture glow = half == null ? null : layers.record(post.recording(), half, look, post.constants());
        if (glow == null) return;
        post.resources().put(LEVELS, layers.levels());
        post.composite().bloom(glow, intensity, tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
        if (linear) post.composite().linear();
    }

    /** Each graded layer's bloom, from its light alone and by its look: Unreal's where it asks, else the engine's layers. */
    private void recordLayers(CgPostContext post, CgGradeLayers graded) {
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            CgPostSettings look = graded.look(k);
            if (look == null || look.bloom() <= 0f) continue;
            float t = look.overrides(CgPostSettings.BLOOM_THRESHOLD) ? look.bloomThreshold() : threshold;
            CgPostSettings shaped = shape(look, layerEngine[k], t);
            CgGraphTexture half = layerSource[k].record(post, graded.layer(k), shaped, null);
            CgGraphTexture glow = half == null ? null : layerLayers[k].record(post.recording(), half, shaped, post.constants());
            if (glow != null) post.composite().layerBloom(k, glow, intensity);
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

    private static void draw(CgRecording recording, CgGraphTexture target, CgMaterial material, CgLoad load,
                             CgPassConstants constants, float[] block, int zone) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        constants.read(block, 0).resolution(target.getWidth(), target.getHeight());
        CgRasterPass pass = recording.raster(target, load, constants, null, CgOrder.SORTED).timed(zone);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /**
     * Bloom's source: a scene's light at half its size, less every graded layer's light in {@code layers}; each channel's
     * excess past the threshold ({@link CgPostSettings#bloomsExcess}) or Unreal's setup on its 2x2 average.
     */
    private static final class Source {
        private final String name;
        private final CgPassConstants constants = new CgPassConstants();
        private final float[] block = new float[CgPassConstants.FLOATS];
        private CgMaterial material;
        private CgGraphTexture target, boundScene, boundLayer0, boundLayer1;
        private float boundThreshold = Float.NaN;
        private boolean boundSetup, boundLayers;

        Source(String name) {
            this.name = name;
        }

        /** Null while the pass's program is not ready. */
        @Nullable
        CgGraphTexture record(CgPostContext post, CgGraphTexture scene, CgPostSettings look, @Nullable CgGradeLayers layers) {
            int w = Math.max(1, scene.getWidth() / 2), h = Math.max(1, scene.getHeight() / 2);
            if (target == null || target.getWidth() != w || target.getHeight() != h) {
                target = CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, FORMAT));
            }
            if (material == null) material = CgMaterial.newInstance(EXCESS);
            boolean unreal = !look.bloomsExcess();
            // The excess form subtracts its threshold, so Unreal's -1 (the whole scene) is 0 there
            float threshold = unreal ? look.bloomThreshold() : Math.max(0f, look.bloomThreshold());
            CgGraphTexture l0 = layers != null ? layers.layer(0) : null, l1 = layers != null ? layers.layer(1) : null;
            boolean less = l0 != null || l1 != null;
            if (scene != boundScene || threshold != boundThreshold || unreal != boundSetup || less != boundLayers
                    || l0 != boundLayer0 || l1 != boundLayer1) {
                material.toggleKeyword("UNREAL_SETUP", unreal);
                material.toggleKeyword("LAYERS", less);
                material.applyProperties(b -> {
                    b.sampler("_Scene", 0, scene).set1f("_Threshold", threshold)
                            .vec4("_Layers", l0 != null ? 1f : 0f, l1 != null ? 1f : 0f, 0f, 0f);
                    if (l0 != null) b.sampler("_Layer0", 0, l0);
                    if (l1 != null) b.sampler("_Layer1", 0, l1);
                });
                boundScene = scene;
                boundThreshold = threshold;
                boundSetup = unreal;
                boundLayers = less;
                boundLayer0 = l0;
                boundLayer1 = l1;
            }
            if (material.pipeline(CgInstanceKind.OBJECT) == null) return null;
            post.constants().write(block, 0);
            draw(post.recording(), target, material, CLEAR, constants, block, GPU_EXCESS);
            return target;
        }

        void release() {
            material = null;
            target = boundScene = boundLayer0 = boundLayer1 = null;
            boundThreshold = Float.NaN;
        }
    }

    /**
     * Unreal's Gaussian layers as raster passes: the half-size source downsampled to a 64th by Unreal's high-quality
     * chain (four bilinear taps, a 4x4 box), then from the 64th up each layer a separable Gaussian of its stage, its
     * vertical pass adding the layer before. Stage 0's layer holds the glow, which the composite adds whole.
     *
     * <p>Follows 5.1's {@code AddGaussianBloomPasses} (PostProcessBloomSetup.cpp): layer {@code i} blurs stage
     * {@code i - 1} with a kernel of {@code Bloom<i>Size * BloomSizeScale} percent of that stage's width, its weights times
     * {@code Bloom<i>Tint * BloomIntensity / 6}; and {@code Compute1DGaussianFilterKernel} (PostProcessWeightedSampleSum.cpp)
     * for the taps, and its fast blur ({@code r.FastBlurThreshold} 7): from a radius of 7 texels the horizontal pass draws
     * at half width, which the vertical pass reads bilinearly.</p>
     *
     * <p>A texture a stage, as Unreal's: stages 1 to 5 ({@code down[k]} stage {@code k + 1}); each layer's horizontal
     * pass, at full width and, for a fast blur, at half; each layer's sum. Levels of one texture cost a level pin a pass.
     * Unlike Unreal's, a layer blurs no stage shorter than {@link #SHORTEST_STAGE}: its stage is {@code at[layer]}.</p>
     */
    private static final class Layers {
        private final CgGraphTexture[] down = new CgGraphTexture[STAGES - 1], across = new CgGraphTexture[STAGES],
                acrossHalf = new CgGraphTexture[STAGES], layers = new CgGraphTexture[STAGES];
        private int width, height;
        /** The stage each layer blurs, and the deepest stage drawn: past it, no downsample draws. */
        private final int[] at = new int[STAGES];
        private int deepest;
        /** Per stage: whether its horizontal pass draws at half width. */
        private final boolean[] fast = new boolean[STAGES];
        private final CgMaterial[] downs = new CgMaterial[STAGES - 1], horizontal = new CgMaterial[STAGES],
                vertical = new CgMaterial[STAGES];
        private CgGraphTexture boundSource;
        /** Each pass's taps (offset u, offset v, weight, 0). */
        private final float[][] taps = new float[STAGES * 2][MAX_TAPS * 4];
        /** Per stage: what its taps were set for (percent, width, height, tint), then the stage it adds (-1 none). */
        private final float[] bound = new float[STAGES * 7];
        private final float[] block = new float[CgPassConstants.FLOATS];
        private final CgPassConstants constants = new CgPassConstants();
        private final double[] kernel = new double[MAX_TAPS * 2];

        /**
         * Records the layers from {@code source} under {@code settings}' layers; answers the texture holding the glow, or
         * null, recording nothing, while a program is not ready or no layer has a size.
         */
        @Nullable
        CgGraphTexture record(CgRecording recording, CgGraphTexture source, CgPostSettings settings, CgPassConstants frame) {
            int w = source.getWidth(), h = source.getHeight();
            if (layers[0] == null || width != w || height != h) {
                width = w;
                height = h;
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
                boundSource = null;
            }
            ensureMaterials(source);
            float tintScale = settings.bloom() / STAGES;
            // From the 64th in: each layer with a size blurs its stage, adding the one before; the half-size stage always
            // draws, with no taps of its own where its layer has no size, so the glow is stage 0's as Unreal's last pass is.
            int before = -1;
            for (int stage = STAGES - 1; stage >= 0; stage--) {
                if (!drawn(settings, stage, before)) {
                    bound[stage * 7 + 6] = Float.NEGATIVE_INFINITY;
                    continue;
                }
                if (!weigh(stage, settings, tintScale, before)) return null;
                before = stage;
            }
            if (before < 0) return null;
            for (int k = 0; k < deepest; k++) if (downs[k].pipeline(CgInstanceKind.OBJECT) == null) return null;
            for (int stage = 0; stage < STAGES; stage++) {
                if (bound[stage * 7 + 6] == Float.NEGATIVE_INFINITY) continue;
                if (horizontal[stage].pipeline(CgInstanceKind.OBJECT) == null || vertical[stage].pipeline(CgInstanceKind.OBJECT) == null) {
                    return null;
                }
            }
            frame.write(block, 0);
            // Unreal's ENoAction: one opaque triangle covers the target, so a clear is a wasted write (3 us a pass)
            for (int k = 0; k < deepest; k++) draw(recording, down[k], downs[k], CgLoad.load(), constants, block, GPU_DOWN);
            for (int stage = STAGES - 1; stage >= 0; stage--) {
                if (bound[stage * 7 + 6] == Float.NEGATIVE_INFINITY) continue;
                draw(recording, (fast[stage] ? acrossHalf : across)[stage], horizontal[stage], CgLoad.load(), constants, block,
                        GPU_LAYER[stage]);
                draw(recording, layers[stage], vertical[stage], CgLoad.load(), constants, block, GPU_LAYER[stage]);
            }
            return layers[0];
        }

        /** Each layer's sum, the glow first: what the last {@link #record} drew. Never changed by the caller. */
        CgGraphTexture[] levels() {
            return layers;
        }

        /** Whether {@code stage} draws: its layer has a size, or it is the half-size stage under one that drew. */
        private static boolean drawn(CgPostSettings s, int stage, int before) {
            return s.bloomSize(stage + 1) * s.bloomSizeScale() > 1e-8f || stage == 0 && before >= 0;
        }

        /** Forgets its materials, which the material registry frees with the context. */
        void release() {
            Arrays.fill(downs, null);
            Arrays.fill(horizontal, null);
            Arrays.fill(vertical, null);
            boundSource = null;
            Arrays.fill(down, null);
            Arrays.fill(across, null);
            Arrays.fill(acrossHalf, null);
            Arrays.fill(layers, null);
            Arrays.fill(bound, Float.NaN);
        }

        /** Each pass's material, its source set: made once, set again when its textures are made anew; the vertical's in weigh. */
        private void ensureMaterials(CgGraphTexture source) {
            if (source == boundSource && downs[0] != null) return;
            boundSource = source;
            Arrays.fill(bound, Float.NaN);
            for (int k = 0; k < STAGES - 1; k++) {
                if (downs[k] == null) downs[k] = CgMaterial.newInstance(DOWN);
                CgTexture from = k == 0 ? source : down[k - 1];
                downs[k].applyProperties(b -> b.sampler("_Source", 0, from));
            }
            for (int stage = 0; stage < STAGES; stage++) {
                if (horizontal[stage] == null) horizontal[stage] = CgMaterial.newInstance(GAUSSIAN);
                if (vertical[stage] == null) vertical[stage] = CgMaterial.newInstance(GAUSSIAN);
                CgTexture level = at[stage] == 0 ? source : down[at[stage] - 1];
                horizontal[stage].applyProperties(b -> b.sampler("_Source", 0, level));
            }
        }

        /**
         * Sets stage {@code stage}'s two passes for its layer, adding stage {@code before} (-1 none), when its size, the
         * size scale, its tint, the stage's size or what it adds moved; answers false while a material is not made.
         */
        private boolean weigh(int stage, CgPostSettings s, float tintScale, int before) {
            CgMaterial h = horizontal[stage], v = vertical[stage];
            if (h == null || v == null) return false;
            int w = layers[stage].getWidth(), hgt = layers[stage].getHeight();
            float percent = s.bloomSize(stage + 1) * s.bloomSizeScale();
            float r = s.bloomTint(stage + 1, 0) * tintScale, g = s.bloomTint(stage + 1, 1) * tintScale,
                    b = s.bloomTint(stage + 1, 2) * tintScale;
            int at = stage * 7;
            if (bound[at] == percent && bound[at + 1] == w && bound[at + 2] == hgt && bound[at + 3] == r && bound[at + 4] == g
                    && bound[at + 5] == b && bound[at + 6] == before) {
                return true;
            }
            bound[at] = percent;
            bound[at + 1] = w;
            bound[at + 2] = hgt;
            bound[at + 3] = r;
            bound[at + 4] = g;
            bound[at + 5] = b;
            bound[at + 6] = before;
            double radius = radius(w, percent);
            int count = percent > 1e-8f ? kernel(radius, kernel) : 0;
            fast[stage] = count > 0 && radius >= FAST_BLUR_RADIUS;
            // Offsets stay in the source's texels: a half-width target's texel centres fall between two of them
            CgTexture acrossTarget = (fast[stage] ? acrossHalf : across)[stage];
            float[] sideways = taps[stage * 2], downward = taps[stage * 2 + 1];
            Arrays.fill(sideways, 0f);
            Arrays.fill(downward, 0f);
            for (int i = 0; i < count; i++) {
                sideways[i * 4] = (float) (kernel[i * 2] / w);
                sideways[i * 4 + 2] = (float) kernel[i * 2 + 1];
                downward[i * 4 + 1] = (float) (kernel[i * 2] / hgt);
                downward[i * 4 + 2] = (float) kernel[i * 2 + 1];
            }
            float n = count;
            h.applyProperties(m -> m.array("_Taps", sideways).set1f("_Count", n).vec4("_Tint", 1f, 1f, 1f, 1f));
            v.applyProperties(m -> m.sampler("_Source", 0, acrossTarget).array("_Taps", downward).set1f("_Count", n)
                    .vec4("_Tint", r, g, b, 1f));
            // Unreal's Additive input: the layer before it, sampled bilinearly
            v.toggleKeyword("ADDITIVE", before >= 0);
            if (before >= 0) {
                CgTexture added = layers[before];
                v.applyProperties(m -> m.sampler("_Additive", 1, added));
            }
            return true;
        }
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        source.release();
        layers.release();
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            layerSource[k].release();
            layerLayers[k].release();
        }
    }
}
