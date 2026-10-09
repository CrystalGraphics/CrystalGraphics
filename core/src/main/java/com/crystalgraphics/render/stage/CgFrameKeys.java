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
     * That something will read {@link #EMISSION} this firing: put before the world renderer records (the post stack puts
     * it while its bloom will draw). Only then does the world renderer write glows in their own draws, which costs a
     * target-sized emission; without it {@code EMISSION} is still published, drawn the old way, and culled unread.
     *
     * <pre>{@code
     * CgRenderStage.WORLD_TRANSPARENT.register(0, frame -> frame.resources().put(CgFrameKeys.EMISSION_READ, Boolean.TRUE));
     * }</pre>
     */
    public static final CgFrameKey<Boolean> EMISSION_READ = CgFrameKey.of("crystalgraphics:emission_read", Boolean.class);

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

    /**
     * The linear HDR scene this firing draws into in place of the host's colour, RGBA16F beside the host's depth: put at
     * the top of {@code WORLD_TRANSPARENT} while {@code CgWorldRenderer.hdrScene()} is on, and the stage's target until
     * the post stack's composite encodes it back into the host's.
     */
    public static final CgFrameKey<CgGraphTexture> SCENE = CgFrameKey.of("crystalgraphics:scene", CgGraphTexture.class);

    /**
     * What the hitting effect glows with this firing, for an impact frame to draw: every visible Emissive pass, hidden by
     * the scene's depth and by the transparent surfaces in front, linear HDR, at a quarter of the target's size or the
     * emission target itself. Published at the end of {@code WORLD_TRANSPARENT} only while {@link #SUBJECT_READ} was put.
     */
    public static final CgFrameKey<CgGraphTexture> SUBJECT = CgFrameKey.of("crystalgraphics:subject", CgGraphTexture.class);

    /** That something will read {@link #SUBJECT} this firing: put before the world renderer records (the post stack's, while an impact frame shows). */
    public static final CgFrameKey<Boolean> SUBJECT_READ = CgFrameKey.of("crystalgraphics:subject_read", Boolean.class);

    private CgFrameKeys() {
    }
}
