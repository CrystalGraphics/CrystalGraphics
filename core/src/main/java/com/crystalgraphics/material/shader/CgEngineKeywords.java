package com.crystalgraphics.material.shader;

/**
 * The keywords the engine compiles a pass under, beside a material's own: each a variant {@link CgGlslWriter} writes
 * differently, asked for by the renderer that draws it (the world renderer's targets, the particle renderers'
 * factories, the overdraw view).
 *
 * <pre>{@code
 * Set<String> keywords = Set.of(CgEngineKeywords.NORMAL_TARGET);           // the opaque pass with its normal target
 * CgGlslProgram program = CgGlslWriter.write(spec, forward, buffers, block, keywords);
 * }</pre>
 *
 * <ul>
 *   <li>A target keyword ({@code *_TARGET}, {@link #SOLID_COVER}, {@link #EMISSION_COVER}) changes a single-output
 *       Forward pass only; any other pass compiles as without it.</li>
 *   <li>{@link #STRIP} and {@link #STRIP_BEAM}/{@link #STRIP_ANIM} are a particle program's implicit features, not
 *       written in any file.</li>
 * </ul>
 */
public final class CgEngineKeywords {

    /**
     * The strip variant every particle program takes undeclared: {@code particle_factory.glsl}'s strip factory places
     * each particle's strip from {@code CG_VERTEX_ID}, as Unreal compiles a material for its beam-and-trail factory.
     */
    public static final String STRIP = "FX_ON_RIBBONS";

    /** Beside {@link #STRIP}, which strip it compiles: a beam renderer's or an anim trail's; a ribbon's takes neither. */
    public static final String STRIP_BEAM = "FX_STRIP_BEAM", STRIP_ANIM = "FX_STRIP_ANIM";

    /** The particle sprite factory's variant: quads placed from {@code CG_VERTEX_ID}, no vertex data read. */
    public static final String SPRITES = "FX_ON_SPRITES";

    /**
     * The skinned factory's variant of a {@code spatial} program: the vertex skinned by its draw's bone palette
     * ({@code factories/bones.glsl}) before the material's code reads {@code cg_Position} and {@code cg_Normal}.
     */
    public static final String SKINNED = "CG_SKINNED";

    /** The overdraw view: the pass's own vertex stage, depth test (as a discard) and {@code discard}, its colour a count. */
    public static final String DEBUG_OVERDRAW = "CG_DEBUG_OVERDRAW";

    /** A Forward pass drawn into a glow target alone (an impact frame's subject): black at its alpha, hidden by depth. */
    public static final String EMISSION_COVER = "CG_EMISSION_COVER";

    /** A Forward pass drawn into the HDR scene with its Emissive pass folded in: only where the spec {@code sceneFolds}. */
    public static final String SCENE_FOLD = "CG_SCENE_FOLD";

    /** A Forward pass writing alpha 1 whatever its fragment wrote: an opaque draw redrawn into a graded layer. */
    public static final String SOLID_COVER = "CG_SOLID_COVER";

    /** A DepthPrepass material's Forward pass drawn into depth alone: its fragment returns at {@code cg_Clip}. */
    public static final String DEPTH_PREPASS = "CG_DEPTH_PREPASS";

    /** A Forward pass drawn into the volume target: what it names with {@code CG_VOLUME_DEPTH(eye)} at location 1. */
    public static final String VOLUME_TARGET = "CG_VOLUME_TARGET";

    /**
     * A Forward pass with the normal target beside it, Unreal's G-buffer normal: {@code n * 0.5 + 0.5} at location 1,
     * the eye depth as alpha, flipped on a back face; {@code (0, 0, 0, -eye)} for a mesh with no normals.
     */
    public static final String NORMAL_TARGET = "CG_NORMAL_TARGET";

    /** A Forward pass writing, in place of its colour, its custom data and shading model packed as two exact floats. */
    public static final String CUSTOM_DATA_TARGET = "CG_CUSTOM_DATA_TARGET";

    /** A Forward pass writing its colour at location 1 too, into the graded layer beside the scene. */
    public static final String LAYER_TARGET = "CG_LAYER_TARGET";

    private CgEngineKeywords() {}
}
