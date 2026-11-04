package com.crystalgraphics.render.post.bloom;

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
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * The post stack's bloom, Unreal's Gaussian ({@link CgGaussianBloom}): the HDR scene's ({@link CgFrameKeys#SCENE})
 * light past a threshold, spread by six layers and added over the picture by the composite. Its settings are global,
 * as every engine's are; {@code CgPostStack.get().bloom()} is the instance. A post volume shapes the layers
 * ({@code CgPostSettings.bloomLayer}) and scales it ({@code bloom}).
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none
 * CgPostStack.get().bloom().tint(1f, 0.85f, 0.7f);   // a warm glow
 * CgPostStack.get().bloom().threshold(0.8f);         // light past 0.8 blooms, not past 1
 * }</pre>
 *
 * <ul>
 *   <li>A volume shaping the bloom ({@code CgPostSettings.shapesBloom}) blooms by its {@code bloomThreshold}, Unreal's
 *       -1 (the whole scene) unless set; any other frame by {@link #threshold()}, past white.</li>
 *   <li>A graded layer blooms by its own look, from its own light.</li>
 *   <li>Nothing in the scene past the threshold, nothing added. No scene this firing, nothing recorded.</li>
 *   <li>Settings are read on the render thread and may be written from any: a float, read at most a frame late.</li>
 * </ul>
 */
public final class CgBloom implements CgPostEffect {

    /**
     * The firing's bloom stages, the glow first and null past the last, a texture each: published for a later effect (the
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

    private final CgGaussianBloom gaussian = new CgGaussianBloom();
    /** Each graded layer's own bloom, from its own light, by its look's layers. */
    private final CgGaussianBloom[] layerGaussians = {new CgGaussianBloom(), new CgGaussianBloom()};
    private final Excess excess = new Excess("cg_bloom_excess");
    private final Excess[] layerExcess = {new Excess("cg_bloom_layer0"), new Excess("cg_bloom_layer1")};
    private float intensity = INTENSITY, tintR = 1f, tintG = 1f, tintB = 1f, threshold = 1f;
    private final CgPassConstants excessConstants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];

    /** How strongly what emits blooms: {@value #INTENSITY} by default, 0 for none. */
    public CgBloom intensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    /** Multiplies the glow's colour: white by default. */
    public CgBloom tint(float r, float g, float b) {
        tintR = r;
        tintG = g;
        tintB = b;
        return this;
    }

    /** The linear brightness past which light blooms where no volume sets one: 1 by default, the screen's white. */
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
        CgGradeLayers layers = scene != null ? post.resources().get(CgGradeLayers.KEY) : null;
        if (layers != null) recordLayers(post, layers);
        if (post.settings().bloom() <= 0f || scene == null) return;
        // Unreal's setup on the half-size scene, less the graded layers' light; the layers' tints carry the intensity
        CgGraphTexture half = excess.record(post, scene, thresholdOf(post.settings()), layers);
        CgGraphTexture glow = half == null ? null : gaussian.record(post.recording(), half, post.settings(), post.constants());
        if (glow == null) return;
        post.resources().put(CHAIN, gaussian.levels());
        post.composite().bloom(glow, intensity, tintR, tintG, tintB);
    }

    /** Each graded layer's bloom, from its light alone and by its look. */
    private void recordLayers(CgPostContext post, CgGradeLayers layers) {
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            CgPostSettings look = layers.look(k);
            if (look == null || look.bloom() <= 0f) continue;
            CgGraphTexture half = layerExcess[k].record(post, layers.layer(k), thresholdOf(look), null);
            CgGraphTexture glow = half == null ? null : layerGaussians[k].record(post.recording(), half, look, post.constants());
            if (glow != null) post.composite().layerBloom(k, glow, intensity);
        }
    }

    private float thresholdOf(CgPostSettings settings) {
        return settings.shapesBloom() ? settings.bloomThreshold() : threshold;
    }

    /** A source of bloom: a scene's light past a threshold at half its size, its material and target its own. */
    private final class Excess {
        private final String name;
        private CgMaterial material;
        private CgGraphTexture target, boundScene, boundLayer0, boundLayer1;
        private float boundThreshold = Float.NaN;
        private boolean boundLayers;

        Excess(String name) {
            this.name = name;
        }

        /**
         * Unreal's bloom setup on {@code scene}'s 2x2 average past {@code threshold} (whole at -1), less every graded
         * layer's light in {@code layers}; null while the pass's program is not ready.
         */
        @Nullable
        CgGraphTexture record(CgPostContext post, CgGraphTexture scene, float threshold, @Nullable CgGradeLayers layers) {
            int w = Math.max(1, scene.getWidth() / 2), h = Math.max(1, scene.getHeight() / 2);
            if (target == null || target.getWidth() != w || target.getHeight() != h) {
                target = CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, EXCESS_FORMAT));
            }
            if (material == null) {
                material = CgMaterial.newInstance(EXCESS);
                material.toggleKeyword("UNREAL_SETUP", true);
            }
            CgGraphTexture l0 = layers != null ? layers.layer(0) : null, l1 = layers != null ? layers.layer(1) : null;
            boolean less = l0 != null || l1 != null;
            if (scene != boundScene || threshold != boundThreshold || less != boundLayers || l0 != boundLayer0
                    || l1 != boundLayer1) {
                float t = threshold;
                material.toggleKeyword("LAYERS", less);
                material.applyProperties(b -> {
                    b.sampler("_Scene", 0, scene).set1f("_Threshold", t).vec4("_Layers", l0 != null ? 1f : 0f, l1 != null ? 1f : 0f, 0f, 0f);
                    if (l0 != null) b.sampler("_Layer0", 0, l0);
                    if (l1 != null) b.sampler("_Layer1", 0, l1);
                });
                boundScene = scene;
                boundThreshold = t;
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
        gaussian.release();
        excess.release();
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            layerGaussians[k].release();
            layerExcess[k].release();
        }
    }
}
