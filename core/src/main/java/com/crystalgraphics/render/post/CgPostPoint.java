package com.crystalgraphics.render.post;

/**
 * Where a {@link CgPostEffect} runs in its view's frame, named for the phase it joins as the render stages are. Within a
 * point, effects run by their order, ties in the order they were added.
 *
 * <pre>{@code
 * CgPost.client().add(CgPostPoint.HDR, (ctx, input) -> ...);          // after bloom is taken, before the tonemap
 * CgPost.client().material("mymod:shaders/post/thermal.shader");      // at the Location its file names
 * }</pre>
 *
 * <ul>
 *   <li>At {@link #SURFACES} and {@link #TRANSLUCENT} an effect draws on the scene the renderer goes on drawing into: it
 *       answers its input, drawn over.</li>
 *   <li>A shader's {@code Material { Location ... }} takes the same names.</li>
 * </ul>
 */
public enum CgPostPoint {

    // Declared in the order they run: the chain walks each point's effects in turn.

    /**
     * On the lit opaque scene, before anything translucent and before the {@code SURFACES} stage's registrations (Unreal
     * 4's {@code BL_BeforeTranslucency}): custom depth, normals and custom data are ready. The height fog draws here
     * (order 0, after the outlines). Without the HDR scene it runs with {@link #TRANSLUCENT}.
     */
    SURFACES,
    /**
     * On the scene after our translucent draws and the {@code TRANSLUCENT} stage, before separate translucency is laid
     * over it (Color Correct Regions' pre-post pass): what draws here never touches an effect whose material says
     * {@code TranslucencyPass Separate}.
     */
    TRANSLUCENT,
    /** The linear scene with every translucency composed, before bloom: what bloom sees, it blooms. */
    SCENE,
    /** After bloom is taken, before the tonemap: still linear, never bloomed. */
    HDR,
    /** The tonemapped, display-encoded picture, in the host's target. */
    DISPLAY;

    /** Whether an effect here draws on the scene the renderer goes on drawing into, and so answers its input. */
    boolean onScene() {
        return this == SURFACES || this == TRANSLUCENT;
    }
}
