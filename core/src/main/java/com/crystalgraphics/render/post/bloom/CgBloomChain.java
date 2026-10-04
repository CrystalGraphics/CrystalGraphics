package com.crystalgraphics.render.post.bloom;

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
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;

import java.util.Arrays;

/**
 * Bloom's chain as raster passes, Call of Duty: Advanced Warfare's scheme as Bevy and Filament run it: the emission
 * downsampled level by level by the 13-tap filter (Karis's average on the first step), then each level, from the
 * smallest up, tent-filtered and added onto the one above. Level 0 then holds the whole glow, so the composite reads
 * it once.
 *
 * <p>The top level is at most {@link #TOP} texels tall whatever the screen (Bevy's {@code max_mip_dimension}), and the
 * levels go down to about {@link #SMALLEST}, so the glow reaches as far at every resolution. An emission more than
 * twice the top is first halved by Castaño's 4-tap Gaussian (Filament's first step) until it is within 2×.</p>
 *
 * <p>Each level's share of the glow is set in the upsample's blend ({@code up.shader}): level {@code k} keeps its
 * weight of its own downsample and adds the tent of the level below it. The weights are {@link #WEIGHTS} aligned to
 * the smallest level, so a level finer than the old chain's top (the 512 one at 1080p) keeps none of its own.</p>
 *
 * <p>By tier: Low draws 4×4 boxes down and four taps up, at most 4 levels; High and up weigh the first step by
 * Karis's average.</p>
 */
final class CgBloomChain {

    /** Each level's share of the glow, widest last: the old composite's sum of levels 1 to 5. */
    static final float[] WEIGHTS = {0.30f, 0.25f, 0.20f, 0.15f, 0.10f};
    /** The top level's greatest height, in texels. */
    static final int TOP = 512;
    /** About the smallest level's height: today's reach, a 17th of the screen. */
    static final int SMALLEST = 16;
    private static final int MAX_LEVELS = 6, LOW_LEVELS = 4, MAX_HALVINGS = 4;
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("cg_bloom_chain")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final CgFrameBufferFormat PREFILTER_FORMAT = CgFrameBufferFormat.builder("cg_bloom_prefilter")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final String DOWN = "crystalgraphics:shaders/post/bloom/down.shader";
    private static final String UP = "crystalgraphics:shaders/post/bloom/up.shader";
    private static final String PREFILTER = "crystalgraphics:shaders/post/bloom/prefilter.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.bloom.chain");

    private CgGraphTexture chain, prefilter;
    private int halvings;
    /** Per level: what draws it down from the level above (level 0's from the emission), and up from the one below. */
    private final CgMaterial[] down = new CgMaterial[MAX_LEVELS], up = new CgMaterial[MAX_LEVELS];
    private final CgMaterial[] halve = new CgMaterial[MAX_HALVINGS];
    private final float[] weights = new float[MAX_LEVELS];
    /** What the materials' properties and keywords were set for, so they are set again only when one changes. */
    private CgGraphTexture boundEmission, boundChain, boundPrefilter;
    private CgQuality boundTier;
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];

    /**
     * Records the chain from {@code emission} under the frame's {@code frame} constants, with {@code tier}'s filters;
     * answers it, its glow in level 0. Null, recording nothing, while a pass's program is not ready: a level never
     * cleared would be blended over as the pool left it.
     */
    CgGraphTexture record(CgRecording recording, CgGraphTexture emission, CgPassConstants frame, CgQuality tier) {
        int ew = emission.getWidth(), eh = emission.getHeight();
        int halvings = 0;
        while (halvings < MAX_HALVINGS && (eh >> halvings) > 2 * TOP) halvings++;
        if (halvings > 0) {
            int pw = Math.max(1, ew / 2), ph = Math.max(1, eh / 2);
            if (prefilter == null || prefilter.getWidth() != pw || prefilter.getHeight() != ph || prefilter.getLevels() != halvings) {
                prefilter = CgGraphTexture.transientTexture("cg_bloom_prefilter", new CgTextureDesc(pw, ph, PREFILTER_FORMAT, halvings));
            }
        }
        this.halvings = halvings;
        int sw = Math.max(1, ew >> halvings), sh = Math.max(1, eh >> halvings);
        int h = Math.min(TOP, sh), w = Math.max(1, Math.round(sw * (float) h / sh));
        int byReach = 1 + Math.max(0, (int) Math.round(Math.log((double) h / SMALLEST) / Math.log(2)));
        int levels = Math.min(Math.min(tier == CgQuality.LOW ? LOW_LEVELS : MAX_LEVELS, byReach), CgTexture.fullChain(w, h));
        if (chain == null || chain.getWidth() != w || chain.getHeight() != h || chain.getLevels() != levels) {
            chain = CgGraphTexture.transientTexture("cg_bloom_chain", new CgTextureDesc(w, h, FORMAT, levels));
        }
        ensureMaterials(emission, levels, tier);
        for (int j = 0; j < halvings; j++) if (halve[j].pipeline(CgInstanceKind.OBJECT) == null) return null;
        for (int k = 0; k < levels; k++) {
            if (down[k].pipeline(CgInstanceKind.OBJECT) == null) return null;
            if (k < levels - 1 && up[k].pipeline(CgInstanceKind.OBJECT) == null) return null;
        }
        frame.write(block, 0);
        for (int j = 0; j < halvings; j++) {
            draw(recording, prefilter, j, CgLoad.clear(0f, 0f, 0f, 0f), halve[j]);
        }
        for (int k = 0; k < levels; k++) {
            draw(recording, chain, k, CgLoad.clear(0f, 0f, 0f, 0f), down[k]);
        }
        for (int k = levels - 2; k >= 0; k--) {
            draw(recording, chain, k, CgLoad.load(), up[k]);
        }
        return chain;
    }

    /** Forgets its materials, which the material registry frees with the context. */
    void release() {
        Arrays.fill(down, null);
        Arrays.fill(up, null);
        Arrays.fill(halve, null);
        boundEmission = boundChain = boundPrefilter = null;
        boundTier = null;
        chain = prefilter = null;
    }

    private void draw(CgRecording recording, CgGraphTexture target, int level, CgLoad load, CgMaterial material) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        constants.read(block, 0).resolution(Math.max(1, target.getWidth() >> level), Math.max(1, target.getHeight() >> level));
        CgRasterPass pass = recording.raster(target, level, load, constants, null, CgOrder.SORTED).timed(GPU);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /**
     * Each pass's material, its source and keywords set: a halving from the emission or the one before, down from the
     * last of those or the level above, up from the level below. Made once; set again only when a texture is made anew
     * or the tier changes.
     */
    private void ensureMaterials(CgGraphTexture emission, int levels, CgQuality tier) {
        CgGraphTexture pre = halvings > 0 ? prefilter : null;
        if (emission == boundEmission && chain == boundChain && pre == boundPrefilter && tier == boundTier) return;
        boundEmission = emission;
        boundChain = chain;
        boundPrefilter = pre;
        boundTier = tier;
        boolean cheap = tier == CgQuality.LOW, karis = tier.atLeast(CgQuality.HIGH);
        for (int j = 0; j < halvings; j++) {
            if (halve[j] == null) halve[j] = CgMaterial.newInstance(PREFILTER);
            halve[j].toggleKeyword("KARIS", karis && j == 0);
            CgTexture source = j == 0 ? emission : prefilter.level(j - 1);
            halve[j].applyProperties(b -> b.sampler("_Source", 0, source));
        }
        weigh(levels);
        for (int k = 0; k < levels; k++) {
            if (down[k] == null) down[k] = CgMaterial.newInstance(DOWN);
            down[k].toggleKeyword("CHEAP", cheap);
            down[k].toggleKeyword("KARIS", !cheap && karis && k == 0 && halvings == 0);
            CgTexture source = k > 0 ? chain.level(k - 1) : halvings > 0 ? prefilter.level(halvings - 1) : emission;
            down[k].applyProperties(b -> b.sampler("_Source", 0, source));
            if (k == levels - 1) continue;
            if (up[k] == null) up[k] = CgMaterial.newInstance(UP);
            up[k].toggleKeyword("CHEAP", cheap);
            CgTexture below = chain.level(k + 1);
            // The deepest level is added as itself, at its own share; every other comes in whole, already weighted.
            float weight = k == levels - 2 ? weights[k + 1] : 1f, keep = weights[k];
            up[k].applyProperties(b -> b.sampler("_Source", 0, below).set1f("_Weight", weight).set1f("_Keep", keep));
        }
    }

    /** {@link #WEIGHTS} aligned to the smallest of {@code levels}, a finer level taking none, summing to 1. */
    private void weigh(int levels) {
        float sum = 0f;
        for (int k = 0; k < levels; k++) {
            int j = k - (levels - WEIGHTS.length);
            weights[k] = j >= 0 ? WEIGHTS[j] : 0f;
            sum += weights[k];
        }
        for (int k = 0; k < levels; k++) weights[k] /= sum;
    }
}
