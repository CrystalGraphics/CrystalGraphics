package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.buffer.CgBufferLayout;

/**
 * What a draw's instances are: the record each one writes and the geometry every instance expands. A batch never
 * mixes kinds, and the executor uploads one kind's instances for a frame in a single write, drawing each batch's
 * range of it from the draw's first instance.
 *
 * <pre>{@code
 * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);   // a UI box, a glyph, a blit
 * CgPipeline w = material.pipeline(CgInstanceKind.OBJECT); // a world mesh
 * int floats = CgInstanceKind.QUAD.floats();               // one record's size, for a writer
 * }</pre>
 *
 * <p>A shader reads the record through its engine buffer: {@code #pragma cg_use quad} for {@link #QUAD},
 * {@code cg_use curve} for {@link #CURVE}; {@link #OBJECT} through {@code cg_env.glsl}'s {@code CG_OBJECT_DATA}, which
 * reads its id and then the table's record.
 * The formats live here, not in the renderers, so a frame builder off the render thread can read them without
 * touching a class that allocates GL buffers.</p>
 */
public enum CgInstanceKind {

    /** The quad record over the unit quad: UI boxes, glyphs, blits. */
    QUAD(quadFormat()),

    /** The curve record over the curve strip: strokes, triangles, cells. */
    CURVE(curveFormat()),

    /**
     * A slot of the firing's {@link CgPrimitiveTable} over the mesh a draw names: world geometry. The record
     * ({@code cg_env.glsl}'s {@code CgObjectData}) is the table's; an instance carries its id, and beside it its
     * material's arena slot ({@link CgMaterialArena}), which the frame builder writes.
     */
    OBJECT(objectIdFormat(), 2);

    /** The GLSL buffer the object records are read from, declared by {@code cg_env.glsl}: the table, or a draw's own. */
    public static final String OBJECT_BLOCK_NAME = "CgObjectDataBuffer";
    /** The GLSL buffer {@link #OBJECT}'s ids are read from, declared by {@code cg_env.glsl}. */
    public static final String OBJECT_ID_BLOCK_NAME = "CgObjectIdBuffer";

    private static final CgInstanceKind[] BY_ORDINAL = values();

    private final CgBufferLayout format;
    /** Floats a record takes in the array: the format's, which rounds to 16 bytes, but for OBJECT's id pair, 8. */
    private final int floats;

    CgInstanceKind(CgBufferLayout format) {
        this(format, format.getFloatCount());
    }

    CgInstanceKind(CgBufferLayout format, int floats) {
        this.format = format;
        this.floats = floats;
    }

    /** The kind with this ordinal, without the copy {@code values()} makes. */
    public static CgInstanceKind of(int ordinal) {
        return BY_ORDINAL[ordinal];
    }

    /** The std430 record one instance writes. */
    public CgBufferLayout format() {
        return format;
    }

    /** Floats per instance record. */
    public int floats() {
        return floats;
    }

    /** Bytes per instance record. */
    public int stride() {
        return floats * Float.BYTES;
    }

    private static CgBufferLayout quadFormat() {
        return CgBufferLayout
                .builder("QuadInstance", CgBufferLayout.MemoryLayout.STD430)
                // fade: 1 - the item's opacity, in origin's tail. Stored inverted so a record written without it is
                // opaque. CG_QUAD_OPACITY reads it.
                .vec3("origin").float_("fade").vec3("right").vec3("up")
                .vec2("uv0").vec2("uv1")
                .vec4("color")
                .float_("atlasLayer")
                // A free scalar in the twelve bytes std430 pads after atlasLayer, so it costs no size.
                .float_("custom2")
                // The clip-table entry this quad is drawn under, 0 for none -- in the same padding. @see CgClipTable
                .float_("clip")
                // Its spatial and effect nodes, CgPalette.pack'd -- the last of that padding. @see CgPalette
                .float_("node")
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

    /** {@code cg_env.glsl}'s {@code cg_ObjectIds} entry: a slot of the table and a material slot, as bits in floats. */
    private static CgBufferLayout objectIdFormat() {
        return CgBufferLayout.builder("cg_object_id", CgBufferLayout.MemoryLayout.STD430).uint("id").uint("material").build();
    }

    private static CgBufferLayout curveFormat() {
        return CgBufferLayout
                .builder("CurveInstance", CgBufferLayout.MemoryLayout.STD430)
                // fade: 1 - the item's opacity, in p0's tail, as the quad record's. CG_CURVE_OPACITY reads it.
                .vec3("p0").float_("fade").vec3("p1").vec3("p2")
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
                // Its spatial and effect nodes, CgPalette.pack'd. @see CgPalette
                .float_("node")
                .build();
    }
}
