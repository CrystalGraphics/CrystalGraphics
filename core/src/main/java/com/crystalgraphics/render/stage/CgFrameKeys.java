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

    private CgFrameKeys() {
    }
}
