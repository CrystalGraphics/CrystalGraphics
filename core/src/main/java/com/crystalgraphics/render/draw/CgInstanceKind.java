package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.buffer.CgBufferFormat;

/**
 * What a draw's instances are: the record each one writes and the geometry every instance expands. A batch never
 * mixes kinds, and the executor uploads one kind's instances for a frame in a single write, drawing each batch's
 * range of it through {@code cg_InstanceBase}.
 *
 * <pre>{@code
 * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);   // a UI box, a glyph, a blit
 * CgPipeline w = material.pipeline(CgInstanceKind.OBJECT); // a world mesh
 * int floats = CgInstanceKind.QUAD.floats();               // one record's size, for a writer
 * }</pre>
 *
 * <p>A shader reads the record through its engine buffer: {@code #pragma cg_use quad} for {@link #QUAD},
 * {@code cg_use curve} for {@link #CURVE}; {@link #OBJECT} through {@code cg_env.glsl}'s {@code CG_OBJECT_DATA}.
 * The formats live here, not in the renderers, so a frame builder off the render thread can read them without
 * touching a class that allocates GL buffers.</p>
 */
public enum CgInstanceKind {

    /** The quad record over the unit quad: UI boxes, glyphs, blits. */
    QUAD(quadFormat()),

    /** The curve record over the curve strip: strokes, triangles, cells. */
    CURVE(curveFormat()),

    /** The object record ({@code cg_env.glsl}'s {@code CgObjectData}) over the mesh a draw names: world geometry. */
    OBJECT(objectFormat());

    /** The GLSL buffer {@link #OBJECT}'s records are read from, declared by {@code cg_env.glsl}. */
    public static final String OBJECT_BLOCK_NAME = "CgObjectDataBuffer";

    private static final CgInstanceKind[] BY_ORDINAL = values();

    private final CgBufferFormat format;

    CgInstanceKind(CgBufferFormat format) {
        this.format = format;
    }

    /** The kind with this ordinal, without the copy {@code values()} makes. */
    public static CgInstanceKind of(int ordinal) {
        return BY_ORDINAL[ordinal];
    }

    /** The std430 record one instance writes. */
    public CgBufferFormat format() {
        return format;
    }

    /** Floats per instance record. */
    public int floats() {
        return format.getFloatCount();
    }

    /** Bytes per instance record. */
    public int stride() {
        return format.getFloatCount() * Float.BYTES;
    }

    private static CgBufferFormat quadFormat() {
        return CgBufferFormat
                .builder("QuadInstance", CgBufferFormat.MemoryLayout.STD430)
                .vec3("origin").vec3("right").vec3("up")
                .vec2("uv0").vec2("uv1")
                .vec4("color")
                .float_("atlasLayer")
                // A free scalar in the twelve bytes std430 pads after atlasLayer, so it costs no size.
                .float_("custom2")
                // The clip-table entry this quad is drawn under, 0 for none -- in the same padding. @see CgClipTable
                .float_("clip")
                // -- per-instance CUSTOM slots, whatever a consumer needs them to mean --------------
                // The same shape CgObjectData gives the render pipeline (custom0..custom3, read through
                // CG_OBJECT_CUSTOM*), for the same reason: a material that needs per-instance parameters
                // should not have to widen a shared record with fields only it understands. Text packs a
                // stroke into these; anything else may pack anything else.
                //
                // What they buy is batching. A parameter carried as a MATERIAL property is shared by
                // every quad in a batch, so changing it per draw forces a flush and a re-apply between
                // draws that are otherwise identical. Carried per instance, quads that disagree about it
                // still go out in one call.
                //
                // Two rather than four: a slot nothing writes is still uploaded for every quad in the
                // engine. These take the record from 96 bytes to 128 in std430 and each further one is
                // another 16 -- add a third when a feature needs it, not before.
                .vec4("custom0")
                .vec4("custom1")
                .build();
    }

    /** {@code cg_env.glsl}'s {@code CgObjectData}: model, normal matrix (its 3x3 read as a mat3), custom0..3. */
    private static CgBufferFormat objectFormat() {
        return CgBufferFormat
                .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
                .mat4("modelMatrix")
                .mat4("normalMatrix")
                .vec4("custom0")
                .vec4("custom1")
                .vec4("custom2")
                .vec4("custom3")
                .build();
    }

    private static CgBufferFormat curveFormat() {
        return CgBufferFormat
                .builder("CurveInstance", CgBufferFormat.MemoryLayout.STD430)
                .vec3("p0").vec3("p1").vec3("p2")
                .vec4("color0").vec4("color1")
                .vec2("widths")
                .float_("feather")
                .float_("flags")
                // Linear-gradient axis for a FILL reading: (originX, originY, dirX, dirY), in the same space
                // as p0/p1/p2 and scaled so t = dot(p - origin, dir) runs 0..1 across color0 -> color1.
                // Zero for every stroke and for any flat fill; FLAG_GRADIENT is what says to read it.
                .vec4("gradient")
                // The CgClipTable entry the primitive is drawn under, 0 for none.
                .float_("clip")
                .build();
    }
}
