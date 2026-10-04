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
import com.crystalgraphics.trace.CgGpuTrace;

import java.util.Arrays;

/**
 * Bloom's chain as raster passes, Call of Duty: Advanced Warfare's scheme as Bevy and Filament run it: the emission
 * downsampled level by level by the 13-tap filter (Karis's average on the first step), then each level, from the
 * smallest up, tent-filtered and added onto the one above. Level 0 then holds the whole glow, so the composite reads
 * it once. Nine passes for five levels and no scratch textures.
 *
 * <p>Each level's share of the glow is set in the upsample's blend ({@code up.shader}): level {@code k} keeps
 * {@code WEIGHTS[k]} of its own downsample and adds the tent of the level below it.</p>
 */
final class CgBloomChain {

    /** Levels drawn, and each one's share of the glow: the old composite's sum of levels 1 to 5. */
    static final float[] WEIGHTS = {0.30f, 0.25f, 0.20f, 0.15f, 0.10f};
    private static final int LEVELS = WEIGHTS.length;
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("cg_bloom_chain")
            .color(0, CgTextureType.RGBA16F).build();
    private static final String DOWN = "crystalgraphics:shaders/post/bloom/down.shader";
    private static final String UP = "crystalgraphics:shaders/post/bloom/up.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.bloom.chain");

    private CgGraphTexture chain;
    /** Per level: what draws it down from the level above (level 0's from the emission), and up from the one below. */
    private final CgMaterial[] down = new CgMaterial[LEVELS], up = new CgMaterial[LEVELS];
    /** The textures the materials' properties name, so they are set again only when one is made anew. */
    private CgGraphTexture boundEmission, boundChain;
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];

    /** Records the chain from {@code emission} under the frame's {@code frame} constants; answers it, its glow in level 0. */
    CgGraphTexture record(CgRecording recording, CgGraphTexture emission, CgPassConstants frame) {
        int w = Math.max(1, emission.getWidth() / 2), h = Math.max(1, emission.getHeight() / 2);
        int levels = Math.min(LEVELS, CgTexture.fullChain(w, h));
        if (chain == null || chain.getWidth() != w || chain.getHeight() != h || chain.getLevels() != levels) {
            chain = CgGraphTexture.transientTexture("cg_bloom_chain", new CgTextureDesc(w, h, FORMAT, levels));
        }
        ensureMaterials(emission, levels);
        frame.write(block, 0);
        for (int k = 0; k < levels; k++) {
            draw(recording, k, CgLoad.clear(0f, 0f, 0f, 0f), down[k]);
        }
        for (int k = levels - 2; k >= 0; k--) {
            draw(recording, k, CgLoad.load(), up[k]);
        }
        return chain;
    }

    /** Forgets its materials, which the material registry frees with the context. */
    void release() {
        Arrays.fill(down, null);
        Arrays.fill(up, null);
        boundEmission = boundChain = null;
        chain = null;
    }

    private void draw(CgRecording recording, int level, CgLoad load, CgMaterial material) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        constants.read(block, 0).resolution(Math.max(1, chain.getWidth() >> level), Math.max(1, chain.getHeight() >> level));
        CgRasterPass pass = recording.raster(chain, level, load, constants, null, CgOrder.SORTED).timed(GPU);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /**
     * Each level's materials, their sources set: down from the emission or the level above, up from the level below.
     * Made once; their properties set again only when the emission or the chain is a new texture.
     */
    private void ensureMaterials(CgGraphTexture emission, int levels) {
        if (emission == boundEmission && chain == boundChain) return;
        boundEmission = emission;
        boundChain = chain;
        for (int k = 0; k < levels; k++) {
            if (down[k] == null) {
                down[k] = CgMaterial.newInstance(DOWN);
                if (k == 0) down[k].enableKeyword("KARIS");
            }
            CgTexture source = k == 0 ? emission : chain.level(k - 1);
            down[k].applyProperties(b -> b.sampler("_Source", 0, source));
            if (k == levels - 1) continue;
            if (up[k] == null) up[k] = CgMaterial.newInstance(UP);
            CgTexture below = chain.level(k + 1);
            // The deepest level is added as itself, at its own share; every other comes in whole, already weighted.
            float weight = k == levels - 2 ? WEIGHTS[k + 1] : 1f, keep = WEIGHTS[k];
            up[k].applyProperties(b -> b.sampler("_Source", 0, below).set1f("_Weight", weight).set1f("_Keep", keep));
        }
    }
}
