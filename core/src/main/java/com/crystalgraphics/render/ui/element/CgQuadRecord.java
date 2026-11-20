package com.crystalgraphics.render.ui.element;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * The {@link CgInstanceKind#QUAD} record, in floats: a glyph quad of a text item, or a glass pane's, read by the
 * {@code quad} engine buffer. Its offsets come from the kind's format, so naming one touches no GPU buffer.
 *
 * <pre>{@code
 * float[] records = new float[n * CgQuadRecord.FLOATS];
 * int at = i * CgQuadRecord.FLOATS;
 * records[at + CgQuadRecord.ORIGIN] = x;   // ... every field, padding left 0
 * }</pre>
 *
 * <ul>
 *   <li>{@code COLOR} is ARGB unpacked to 0..1 by division; {@code NODE} is {@code CgPalette.pack(spatial, effect)}.</li>
 * </ul>
 */
public final class CgQuadRecord {

    private static final CgBufferLayout FORMAT = CgInstanceKind.QUAD.format();
    public static final int FLOATS = FORMAT.getFloatCount();
    public static final int ORIGIN = FORMAT.getField("origin").getFloatOffset();
    public static final int RIGHT = FORMAT.getField("right").getFloatOffset();
    public static final int UP = FORMAT.getField("up").getFloatOffset();
    public static final int UV0 = FORMAT.getField("uv0").getFloatOffset();
    public static final int UV1 = FORMAT.getField("uv1").getFloatOffset();
    public static final int COLOR = FORMAT.getField("color").getFloatOffset();
    public static final int ATLAS_LAYER = FORMAT.getField("atlasLayer").getFloatOffset();
    public static final int CUSTOM0 = FORMAT.getField("custom0").getFloatOffset();
    public static final int CUSTOM1 = FORMAT.getField("custom1").getFloatOffset();
    public static final int CUSTOM2 = FORMAT.getField("custom2").getFloatOffset();
    public static final int CLIP = FORMAT.getField("clip").getFloatOffset();
    public static final int NODE = FORMAT.getField("node").getFloatOffset();

    /** The macro the {@code quad} token binds the records under; {@code quad.glsl}'s macros name it. */
    public static final String MACRO_NAME = "QUAD_DATA";
    /** Where its shaders read the records, and where a draw names its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("quad");

    /** Made at its first ask, after the graphics start: naming the class allocates nothing. */
    private static final class Buffer {
        static final CgShaderBuffer INSTANCES = CgShaderBufferRegistry.get()
                .getOrCreateInternal("CgQuadInstances", FORMAT, SLOT, CgBufferLifetime.FRAME);
    }

    private CgQuadRecord() {
    }

    /** The buffer the {@code quad} token names. Engine. */
    public static CgShaderBuffer instanceBuffer() {
        return Buffer.INSTANCES;
    }
}
