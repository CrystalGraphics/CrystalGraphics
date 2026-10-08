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
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.trace.CgGpuTrace;

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

    /** The firing's chain, its glow in level 0: published for a later effect (the debug view) to read. */
    public static final CgFrameKey<CgGraphTexture> CHAIN = CgFrameKey.of("crystalgraphics:bloom_chain", CgGraphTexture.class);

    private static final String EXCESS = "crystalgraphics:shaders/post/bloom/excess.shader";
    private static final CgFrameBufferFormat EXCESS_FORMAT = CgFrameBufferFormat.builder("cg_bloom_excess")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_EXCESS = CgGpuTrace.name("post.bloom.excess");

    private final CgBloomChain chain = new CgBloomChain();
    private float intensity = 1f, tintR = 1f, tintG = 1f, tintB = 1f, threshold = 1f;
    private CgMaterial excessMaterial;
    private CgGraphTexture excess, boundScene;
    private float boundThreshold = Float.NaN;
    private final CgPassConstants excessConstants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private Mode mode = Mode.ADDITIVE;
    /** {@code -Dcrystalgraphics.post.bloom.linear=true} starts it in linear light: the two forms side by side. */
    private boolean linear = Boolean.getBoolean("crystalgraphics.post.bloom.linear");

    /** How strongly what emits blooms: 1 by default, 0 for none. */
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
        return intensity * post.settings().bloom() > 0f
                && (post.resources().has(CgFrameKeys.EMISSION) || post.resources().has(CgFrameKeys.SCENE));
    }

    @Override
    public void record(CgPostContext post) {
        CgGraphTexture scene = post.resources().get(CgFrameKeys.SCENE);
        // Bent as the scene beneath was, or the glow sits unbent over every haze; the scene is bent already.
        CgGraphTexture emission = scene != null ? excess(post, scene) : post.distorted(post.resources().get(CgFrameKeys.EMISSION));
        if (emission == null) return;
        CgGraphTexture glow = chain.record(post.recording(), emission, post.constants(), CgGraphicsSettings.QUALITY.get());
        if (glow == null) return;
        post.resources().put(CHAIN, glow);
        // A volume scales it: an effect's moment brightening every glow.
        post.composite().bloom(glow, intensity * post.settings().bloom(), tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
        if (linear) post.composite().linear();
    }

    /** The scene's light past the threshold, at half its size; null while the pass's program is not ready. */
    private CgGraphTexture excess(CgPostContext post, CgGraphTexture scene) {
        int w = Math.max(1, scene.getWidth() / 2), h = Math.max(1, scene.getHeight() / 2);
        if (excess == null || excess.getWidth() != w || excess.getHeight() != h) {
            excess = CgGraphTexture.transientTexture("cg_bloom_excess", new CgTextureDesc(w, h, EXCESS_FORMAT));
        }
        if (excessMaterial == null) excessMaterial = CgMaterial.newInstance(EXCESS);
        if (scene != boundScene || threshold != boundThreshold) {
            float t = threshold;
            excessMaterial.applyProperties(b -> b.sampler("_Scene", 0, scene).set1f("_Threshold", t));
            boundScene = scene;
            boundThreshold = t;
        }
        CgPipeline pipeline = excessMaterial.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return null;
        post.constants().write(block, 0);
        excessConstants.read(block, 0).resolution(w, h);
        CgRasterPass pass = post.recording().raster(excess, CgLoad.clear(0f, 0f, 0f, 0f), excessConstants, null, CgOrder.SORTED)
                .timed(GPU_EXCESS);
        CgChunkBuilder chunks = post.recording().chunks().begin();
        chunks.draw(pipeline, excessMaterial.captureBindings(post.recording().bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
        return excess;
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        chain.release();
        excessMaterial = null;
        excess = boundScene = null;
        boundThreshold = Float.NaN;
    }
}
