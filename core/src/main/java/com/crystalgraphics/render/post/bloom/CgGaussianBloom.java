package com.crystalgraphics.render.post.bloom;

import java.util.Arrays;

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
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Unreal's Gaussian bloom ({@code BloomMethod} Standard), as raster passes: the half-size source downsampled to a 64th
 * by Unreal's high-quality chain (four bilinear taps, a 4x4 box), then from the 64th up each layer a separable Gaussian
 * of its level, its vertical pass adding the layer before. Level 0 of what it answers holds the glow, which the
 * composite adds whole.
 *
 * <p>Follows 5.1's {@code AddGaussianBloomPasses} (PostProcessBloomSetup.cpp): layer {@code i} blurs stage
 * {@code i - 1} with a kernel of {@code Bloom<i>Size * BloomSizeScale} percent of that stage's width, its weights times
 * {@code Bloom<i>Tint * BloomIntensity / 6}; and {@code Compute1DGaussianFilterKernel} (PostProcessWeightedSampleSum.cpp)
 * for the taps, and its fast blur ({@code r.FastBlurThreshold} 7): from a radius of 7 texels the horizontal pass draws
 * at half width, which the vertical pass reads bilinearly.</p>
 */
final class CgGaussianBloom {

    /** Unreal's stages: the half-size scene, then down to a 64th. */
    static final int STAGES = 6;
    /** SM5's most taps a pass ({@code MAX_FILTER_COMPILE_TIME_SAMPLES}). */
    static final int MAX_TAPS = 32;
    /** {@code r.FastBlurThreshold}: the radius in texels from which the horizontal pass draws at half width. */
    static final double FAST_BLUR_RADIUS = 7.0;
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("cg_bloom_gaussian")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final String DOWN = "crystalgraphics:shaders/post/bloom/down.shader";
    private static final String GAUSSIAN = "crystalgraphics:shaders/post/bloom/gaussian.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_DOWN = CgGpuTrace.name("post.bloom.gaussian.down");
    private static final int[] GPU_LAYER = new int[STAGES];

    static {
        for (int stage = 0; stage < STAGES; stage++) GPU_LAYER[stage] = CgGpuTrace.name("post.bloom.gaussian.layer" + (stage + 1));
    }

    /**
     * A texture a stage, as Unreal's: stages 1 to 5 ({@code down[k]} stage {@code k + 1}); each layer's horizontal pass,
     * at full width and, for a fast blur, at half; each layer's sum. Levels of one texture cost a level pin a pass.
     */
    private final CgGraphTexture[] down = new CgGraphTexture[STAGES - 1], across = new CgGraphTexture[STAGES],
            acrossHalf = new CgGraphTexture[STAGES], layers = new CgGraphTexture[STAGES];
    private int width, height;
    /** Per stage: whether its horizontal pass draws at half width. */
    private final boolean[] fast = new boolean[STAGES];
    private final CgMaterial[] downs = new CgMaterial[STAGES - 1], horizontal = new CgMaterial[STAGES],
            vertical = new CgMaterial[STAGES];
    private CgGraphTexture boundSource;
    /** Each pass's taps (offset u, offset v, weight, 0) and what it was set for, to set again only on a change. */
    private final float[][] taps = new float[STAGES * 2][MAX_TAPS * 4];
    /** Per stage: what its taps were set for (percent, width, height, tint), then the stage it adds (-1 none). */
    private final float[] bound = new float[STAGES * 7];
    private final float[] block = new float[CgPassConstants.FLOATS];
    private final CgPassConstants constants = new CgPassConstants();
    private final double[] kernel = new double[MAX_TAPS * 2];

    /**
     * Records the layers from {@code source}, the half-size scene or its setup, under {@code settings}' layers; answers
     * the texture holding the glow in level 0, or null, recording nothing, while a program is not ready or no layer has
     * a size.
     */
    CgGraphTexture record(CgRecording recording, CgGraphTexture source, CgPostSettings settings, CgPassConstants frame) {
        int w = source.getWidth(), h = source.getHeight();
        if (layers[0] == null || width != w || height != h) {
            width = w;
            height = h;
            for (int stage = 0; stage < STAGES; stage++) {
                int sw = Math.max(1, w >> stage), sh = Math.max(1, h >> stage);
                if (stage > 0) down[stage - 1] = CgGraphTexture.transientTexture("cg_bloom_gaussian_down", new CgTextureDesc(sw, sh, FORMAT));
                across[stage] = CgGraphTexture.transientTexture("cg_bloom_gaussian_across", new CgTextureDesc(sw, sh, FORMAT));
                acrossHalf[stage] = CgGraphTexture.transientTexture("cg_bloom_gaussian_across_half",
                        new CgTextureDesc(Math.max(1, sw / 2), sh, FORMAT));
                layers[stage] = CgGraphTexture.transientTexture("cg_bloom_gaussian_layers", new CgTextureDesc(sw, sh, FORMAT));
            }
            boundSource = null;
        }
        ensureMaterials(source);
        float tintScale = settings.bloom() / STAGES;
        // From the 64th in: each layer with a size blurs its stage, adding the one before; the half-size stage always
        // draws, with no taps of its own where its layer has no size, so the glow is level 0 as Unreal's last pass is.
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
        for (CgMaterial m : downs) if (m.pipeline(CgInstanceKind.OBJECT) == null) return null;
        for (int stage = 0; stage < STAGES; stage++) {
            if (bound[stage * 7 + 6] == Float.NEGATIVE_INFINITY) continue;
            if (horizontal[stage].pipeline(CgInstanceKind.OBJECT) == null || vertical[stage].pipeline(CgInstanceKind.OBJECT) == null) {
                return null;
            }
        }
        frame.write(block, 0);
        for (int k = 0; k < STAGES - 1; k++) draw(recording, down[k], downs[k], GPU_DOWN);
        for (int stage = STAGES - 1; stage >= 0; stage--) {
            if (bound[stage * 7 + 6] == Float.NEGATIVE_INFINITY) continue;
            draw(recording, (fast[stage] ? acrossHalf : across)[stage], horizontal[stage], GPU_LAYER[stage]);
            draw(recording, layers[stage], vertical[stage], GPU_LAYER[stage]);
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

    private void draw(CgRecording recording, CgGraphTexture target, CgMaterial material, int zone) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        constants.read(block, 0).resolution(target.getWidth(), target.getHeight());
        // Unreal's ENoAction: one opaque triangle covers the target, so a clear is a wasted write (3 us a pass)
        CgRasterPass pass = recording.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED).timed(zone);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
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
            CgTexture level = stage == 0 ? source : down[stage - 1];
            horizontal[stage].applyProperties(b -> b.sampler("_Source", 0, level));
        }
    }

    /**
     * Sets stage {@code stage}'s two passes for its layer, adding stage {@code before} (-1 none), when its size, the size
     * scale, its tint, the stage's size or what it adds moved; answers false while a material is not made. A stage set
     * by none this frame is marked undrawn.
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
        CgTexture across = (fast[stage] ? acrossHalf : this.across)[stage];
        float[] sideways = taps[stage * 2], down = taps[stage * 2 + 1];
        Arrays.fill(sideways, 0f);
        Arrays.fill(down, 0f);
        for (int i = 0; i < count; i++) {
            sideways[i * 4] = (float) (kernel[i * 2] / w);
            sideways[i * 4 + 2] = (float) kernel[i * 2 + 1];
            down[i * 4 + 1] = (float) (kernel[i * 2] / hgt);
            down[i * 4 + 2] = (float) kernel[i * 2 + 1];
        }
        float n = count;
        h.applyProperties(m -> m.array("_Taps", sideways).set1f("_Count", n).vec4("_Tint", 1f, 1f, 1f, 1f));
        v.applyProperties(m -> m.sampler("_Source", 0, across).array("_Taps", down).set1f("_Count", n).vec4("_Tint", r, g, b, 1f));
        // Unreal's Additive input: the layer before it, sampled bilinearly
        v.toggleKeyword("ADDITIVE", before >= 0);
        if (before >= 0) {
            CgTexture added = layers[before];
            v.applyProperties(m -> m.sampler("_Additive", 1, added));
        }
        return true;
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
}
