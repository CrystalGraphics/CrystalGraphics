package com.crystalgraphics.render.post.bloom;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.stage.CgFrameKey;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.world.CgGradeLayers;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * The post stack's bloom: what the scene emits ({@link CgFrameKeys#EMISSION}), or under the HDR scene
 * ({@link CgFrameKeys#SCENE}) its light past {@link #threshold}, spread wide by a chain of raster passes
 * ({@link CgBloomChain}) and added over the picture by the composite. Its settings are global, as every engine's are;
 * {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none: the world renderer's emission pass is then culled too
 *
 * // Bevy's energy-conserving mode: the picture dimmed by the share the glow takes, so nothing gets brighter overall
 * CgPostStack.get().bloom().mode(CgBloom.Mode.ENERGY_CONSERVING).intensity(0.15f);
 * CgPostStack.get().bloom().tint(1f, 0.85f, 0.7f);   // a warm glow
 * CgPostStack.get().bloom().linear(true);            // composite in linear light, at the cost of a copy of the target
 * CgPostStack.get().bloom().threshold(0.8f);         // under the HDR scene: light past 0.8 blooms, not past 1
 * }</pre>
 *
 * <ul>
 *   <li>Every tier blooms: Low a cheaper chain from a quarter-size emission, Ultra a full-size one
 *       ({@link CgBloomChain}).</li>
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
     * The firing's bloom levels, the glow first and null past the last, a texture each: published for a later effect (the
     * debug view) to read. Read only.
     */
    public static final CgFrameKey<CgGraphTexture[]> CHAIN = CgFrameKey.of("crystalgraphics:bloom_chain", CgGraphTexture[].class);

    private static final String EXCESS = "crystalgraphics:shaders/post/bloom/excess.shader";
    private static final CgFrameBufferFormat EXCESS_FORMAT = CgFrameBufferFormat.builder("cg_bloom_excess")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_EXCESS = CgGpuTrace.name("post.bloom.excess");
    private static final CgLoad CLEAR = CgLoad.clear(0f, 0f, 0f, 0f);

    /** {@link #intensity(float)}'s default. */
    public static final float INTENSITY = 1f;

    private final CgBloomChain chain = new CgBloomChain();
    private final CgGaussianBloom gaussian = new CgGaussianBloom();
    /** Each graded layer's own bloom, from its own light: its look's Gaussian layers or the chain. */
    private final CgBloomChain[] layerChains = {new CgBloomChain(), new CgBloomChain()};
    private final CgGaussianBloom[] layerGaussians = {new CgGaussianBloom(), new CgGaussianBloom()};
    private final Excess excess = new Excess("cg_bloom_excess");
    private final Excess[] layerExcess = {new Excess("cg_bloom_layer0"), new Excess("cg_bloom_layer1")};
    private float intensity = INTENSITY, tintR = 1f, tintG = 1f, tintB = 1f, threshold = 1f;
    private final CgPassConstants excessConstants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private Mode mode = Mode.ADDITIVE;
    private static final CgVar.Bool LINEAR = CgVar.bool("crystalgraphics.post.bloom.linear", false, CgVar.DEV,
            "Bloom starts composited in linear light (the copy form), to compare with the default blend form; L cycles"
                    + " blend, linear and off in any harness scene with a 3D camera.");
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

    /** Under the HDR scene, the linear brightness past which light blooms: 1 by default, the screen's white. */
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
                && (post.resources().has(CgFrameKeys.EMISSION) || post.resources().has(CgFrameKeys.SCENE)));
    }

    @Override
    public void record(CgPostContext post) {
        CgGraphTexture scene = post.resources().get(CgFrameKeys.SCENE);
        CgGradeLayers layers = scene != null ? post.resources().get(CgGradeLayers.KEY) : null;
        if (layers != null) recordLayers(post, layers);
        if (post.settings().bloom() <= 0f) return;
        if (post.settings().bloomsGaussian()) {
            // Unreal's layers: the setup on the half-size scene, or the emission halved; their tints carry the intensity
            CgGraphTexture half = scene != null ? excess.record(post, scene, true, post.settings().bloomThreshold(), layers)
                    : excess.record(post, post.distorted(post.resources().get(CgFrameKeys.EMISSION)), true, -1f, null);
            CgGraphTexture glow = half == null ? null : gaussian.record(post.recording(), half, post.settings(), post.constants());
            if (glow == null) return;
            post.resources().put(CHAIN, gaussian.levels());
            post.composite().bloom(glow, intensity, tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
            if (linear) post.composite().linear();
            return;
        }
        // Bent as the scene beneath was, or the glow sits unbent over every haze; the scene is bent already.
        CgGraphTexture emission = scene != null ? excess.record(post, scene, false, threshold, layers)
                : post.distorted(post.resources().get(CgFrameKeys.EMISSION));
        if (emission == null) return;
        CgGraphTexture glow = chain.record(post.recording(), emission, post.constants(), CgGraphicsSettings.QUALITY.get());
        if (glow == null) return;
        post.resources().put(CHAIN, chain.levels());
        // A volume scales it: an effect's moment brightening every glow.
        post.composite().bloom(glow, intensity * post.settings().bloom(), tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
        if (linear) post.composite().linear();
    }

    /**
     * Each graded layer's bloom, from its light alone and by its look: Unreal's Gaussian layers at its threshold where the
     * look asks, else the chain at its threshold (the engine's unless it sets one) times its {@code bloom}.
     */
    private void recordLayers(CgPostContext post, CgGradeLayers layers) {
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            CgPostSettings look = layers.look(k);
            if (look == null || look.bloom() <= 0f) continue;
            CgGraphTexture layer = layers.layer(k), glow;
            if (look.bloomsGaussian()) {
                CgGraphTexture half = layerExcess[k].record(post, layer, true, look.bloomThreshold(), null);
                glow = half == null ? null : layerGaussians[k].record(post.recording(), half, look, post.constants());
                if (glow != null) post.composite().layerBloom(k, glow, intensity);
            } else {
                float t = look.overrides(CgPostSettings.BLOOM_THRESHOLD) ? look.bloomThreshold() : threshold;
                CgGraphTexture source = layerExcess[k].record(post, layer, false, t, null);
                glow = source == null ? null : layerChains[k].record(post.recording(), source, post.constants(), CgGraphicsSettings.QUALITY.get());
                if (glow != null) post.composite().layerBloom(k, glow, intensity * look.bloom());
            }
        }
    }

    /** A source of bloom: a scene's light past a threshold at half its size, its material and target its own. */
    private final class Excess {
        private final String name;
        private CgMaterial material;
        private CgGraphTexture target, boundScene, boundLayer0, boundLayer1;
        private float boundThreshold = Float.NaN;
        private boolean boundSetup, boundLayers;

        Excess(String name) {
            this.name = name;
        }

        /**
         * {@code scene}'s light past {@code threshold}, or with {@code unreal} Unreal's bloom setup on its 2x2 average
         * (whole at -1), less every graded layer's light in {@code layers}; null while the pass's program is not ready.
         */
        @Nullable
        CgGraphTexture record(CgPostContext post, CgGraphTexture scene, boolean unreal, float threshold, @Nullable CgGradeLayers layers) {
            int w = Math.max(1, scene.getWidth() / 2), h = Math.max(1, scene.getHeight() / 2);
            if (target == null || target.getWidth() != w || target.getHeight() != h) {
                target = CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, EXCESS_FORMAT));
            }
            if (material == null) material = CgMaterial.newInstance(EXCESS);
            CgGraphTexture l0 = layers != null ? layers.layer(0) : null, l1 = layers != null ? layers.layer(1) : null;
            boolean less = l0 != null || l1 != null;
            if (scene != boundScene || threshold != boundThreshold || unreal != boundSetup || less != boundLayers
                    || l0 != boundLayer0 || l1 != boundLayer1) {
                float t = threshold;
                material.toggleKeyword("UNREAL_SETUP", unreal);
                material.toggleKeyword("LAYERS", less);
                material.applyProperties(b -> {
                    b.sampler("_Scene", 0, scene).set1f("_Threshold", t).vec4("_Layers", l0 != null ? 1f : 0f, l1 != null ? 1f : 0f, 0f, 0f);
                    if (l0 != null) b.sampler("_Layer0", 0, l0);
                    if (l1 != null) b.sampler("_Layer1", 0, l1);
                });
                boundScene = scene;
                boundThreshold = t;
                boundSetup = unreal;
                boundLayers = less;
                boundLayer0 = l0;
                boundLayer1 = l1;
            }
            CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) return null;
            post.constants().write(block, 0);
            excessConstants.read(block, 0).resolution(w, h);
            CgRasterPass pass = post.recording().raster(target, CLEAR, excessConstants, null, CgOrder.SORTED).timed(GPU_EXCESS);
            CgChunkBuilder chunks = post.recording().chunks().begin();
            chunks.draw(pipeline, material.captureBindings(post.recording().bindings()), FULLSCREEN);
            chunks.instance();
            pass.add(chunks.end());
            pass.end();
            return target;
        }

        void release() {
            material = null;
            target = boundScene = boundLayer0 = boundLayer1 = null;
            boundThreshold = Float.NaN;
        }
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        chain.release();
        gaussian.release();
        excess.release();
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            layerChains[k].release();
            layerGaussians[k].release();
            layerExcess[k].release();
        }
    }
}
