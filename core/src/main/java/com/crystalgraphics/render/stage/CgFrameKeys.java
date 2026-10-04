package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.graph.CgGraphTexture;

/** The engine's own blackboard keys ({@link CgFrameKey}): what its renderers hand each other within a firing. */
public final class CgFrameKeys {

    /**
     * What the scene emits this firing: the world renderer's emission target, every visible Emissive pass drawn into it
     * and hidden by the scene's depth, linear HDR. Published at the end of {@code WORLD_TRANSPARENT}, absent when nothing
     * emitted; the post stack's bloom reads it.
     */
    public static final CgFrameKey<CgGraphTexture> EMISSION = CgFrameKey.of("crystalgraphics:emission", CgGraphTexture.class);

    /**
     * How many transparent fragments each pixel shaded this firing, R16F: the world renderer's overdraw view, published
     * only while {@code CgWorldRenderer.overdraw(true)}; {@code -Dcrystalgraphics.post.debug=overdraw} shows it.
     */
    /**
     * Where each pixel of the scene was bent from this firing, RGBA16F: xy the offset in UV units, z the chromatic split,
     * every Distortion pass added into it. Published by the world renderer after the transparent pass when any drew,
     * already applied to the target by then.
     */
    public static final CgFrameKey<CgGraphTexture> DISTORTION = CgFrameKey.of("crystalgraphics:distortion", CgGraphTexture.class);

    public static final CgFrameKey<CgGraphTexture> OVERDRAW = CgFrameKey.of("crystalgraphics:overdraw", CgGraphTexture.class);

    private CgFrameKeys() {
    }
}
