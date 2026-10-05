package com.crystalgraphics.render.post.debug;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.stage.CgFrameKey;
import com.crystalgraphics.render.stage.CgDistortionField;
import com.crystalgraphics.render.stage.CgFrameKeys;

/**
 * Draws what bloom works from over the whole frame, last: the emission target, or one level of the chain. Set by
 * {@code -Dcrystalgraphics.post.debug=emission} or {@code =level<N>} (0 is the glow the composite reads), so a material's
 * glow can be seen without the picture round it; {@code =overdraw} shows the world renderer's overdraw count through a
 * heat ramp (blue 1, green 4, red 16, white 32), {@code =distortion} the distortion field's offsets summed (|offset| x
 * 50 in red and green, the split in blue). The post stack adds it when the flag is set.
 */
public final class CgPostDebug implements CgPostEffect {

    private static final String SHADER = "crystalgraphics:shaders/post/debug.shader";
    private static final String DISTORTION_SHADER = "crystalgraphics:shaders/post/debug_distortion.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private static final int EMISSION = -1, OVERDRAW = -2, DISTORTION = -3;

    /** {@link #EMISSION}, {@link #OVERDRAW}, else the chain's level. */
    private final int level;
    private CgMaterial material;
    private CgTexture bound;
    private int boundCount;

    private CgPostDebug(int level) {
        this.level = level;
    }

    /** The view {@code -Dcrystalgraphics.post.debug} asks for; null when it is unset. Throws on a value it cannot read. */
    public static CgPostDebug fromProperty() {
        String view = System.getProperty("crystalgraphics.post.debug");
        if (view == null || view.isEmpty()) return null;
        if (view.equals("emission")) return new CgPostDebug(EMISSION);
        if (view.equals("overdraw")) return new CgPostDebug(OVERDRAW);
        if (view.equals("distortion")) return new CgPostDebug(DISTORTION);
        if (view.startsWith("level")) {
            try {
                return new CgPostDebug(Math.max(0, Integer.parseInt(view.substring(5))));
            } catch (NumberFormatException ignored) {
                // falls through to the refusal
            }
        }
        throw new IllegalArgumentException("-Dcrystalgraphics.post.debug=" + view + ": emission, overdraw, distortion or level<N>");
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.AFTER_COMPOSITE;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean active(CgPostContext post) {
        return level == DISTORTION ? post.resources().has(CgFrameKeys.DISTORTION) : post.resources().has(key());
    }

    @Override
    public void record(CgPostContext post) {
        CgTexture source;
        int count = 0;
        if (level == DISTORTION) {
            CgDistortionField field = post.resources().get(CgFrameKeys.DISTORTION);
            source = field.offsets();
            count = field.count();
        } else if (level < 0) {
            source = post.resources().get(key());
        } else {
            CgGraphTexture chain = post.resources().get(CgBloom.CHAIN);
            source = chain.level(Math.min(level, chain.getLevels() - 1));
        }
        if (material == null) {
            material = CgMaterial.newInstance(level == DISTORTION ? DISTORTION_SHADER : SHADER);
            if (level == OVERDRAW) material.enableKeyword("HEAT");
        }
        if (source != bound || count != boundCount) {
            int layers = count;
            material.applyProperties(level == DISTORTION ? b -> b.sampler("_Fields", 0, source).set1i("_FieldCount", layers)
                    : b -> b.sampler("_Source", 0, source));
            bound = source;
            boundCount = count;
        }
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = post.recording().raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED);
        CgChunkBuilder chunks = post.recording().chunks().begin();
        chunks.draw(pipeline, material.captureBindings(post.recording().bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    private CgFrameKey<CgGraphTexture> key() {
        return level == EMISSION ? CgFrameKeys.EMISSION : level == OVERDRAW ? CgFrameKeys.OVERDRAW : CgBloom.CHAIN;
    }

    /** Forgets its material, which the material registry frees with the context. */
    public void release() {
        material = null;
        bound = null;
    }
}
