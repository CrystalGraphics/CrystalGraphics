package com.crystalgraphics.render.draw;

/**
 * What a draw's instances are: the record each one writes and the geometry every instance expands. A batch never
 * mixes kinds, and the executor uploads one kind's instances for a pass in a single write, drawing each batch's
 * range of it through {@code cg_InstanceBase}.
 *
 * <pre>{@code
 * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);   // a UI box, a glyph, a blit
 * CgPipeline w = material.pipeline(CgInstanceKind.OBJECT); // a world mesh
 * }</pre>
 *
 * <p>A shader reads the record through its engine buffer: {@code #pragma cg_use quad} for {@link #QUAD},
 * {@code cg_use curve} for {@link #CURVE}; {@link #OBJECT} through {@code cg_env.glsl}'s {@code CG_OBJECT_DATA}.</p>
 */
public enum CgInstanceKind {

    /** The quad record ({@code CgQuadRenderer}'s format) over the unit quad: UI boxes, glyphs, blits. */
    QUAD(128),

    /** The curve record ({@code CgVectorRenderer}'s format) over the curve strip: strokes, triangles, cells. */
    CURVE(128),

    /** The object record ({@code cg_env.glsl}'s {@code CgObjectData}) over the mesh a draw names: world geometry. */
    OBJECT(192);

    private final int stride;

    CgInstanceKind(int stride) {
        this.stride = stride;
    }

    /** Bytes per instance record. */
    public int stride() {
        return stride;
    }
}
