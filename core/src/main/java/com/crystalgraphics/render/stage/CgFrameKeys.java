package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.graph.CgGraphTexture;

/** The engine's own blackboard keys ({@link CgFrameKey}): what its renderers hand each other within a firing. */
public final class CgFrameKeys {

    /**
     * What the scene emits this firing: the world renderer's emission target, every visible Emissive pass drawn into it
     * and hidden by the scene's depth, linear HDR. Published at the end of {@code WORLD_TRANSPARENT}, absent when nothing
     * emitted; the post stack's bloom reads it, bent by {@link #DISTORTION}.
     */
    public static final CgFrameKey<CgGraphTexture> EMISSION = CgFrameKey.of("crystalgraphics:emission", CgGraphTexture.class);

    /**
     * Where the scene was bent from this firing ({@link CgDistortionField}): published by the world renderer after the
     * transparent pass when any Distortion pass drew, already applied to the target by then. A post effect bends a side
     * input by it with {@code CgPostContext.distorted}.
     */
    public static final CgFrameKey<CgDistortionField> DISTORTION =
            CgFrameKey.of("crystalgraphics:distortion", CgDistortionField.class);

    /**
     * How many transparent fragments each pixel shaded this firing, R16F: the world renderer's overdraw view, published
     * only while {@code CgWorldRenderer.overdraw(true)}; {@code -Dcrystalgraphics.post.debug=overdraw} shows it.
     */
    public static final CgFrameKey<CgGraphTexture> OVERDRAW = CgFrameKey.of("crystalgraphics:overdraw", CgGraphTexture.class);

    private CgFrameKeys() {
    }
}
