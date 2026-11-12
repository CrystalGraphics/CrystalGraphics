package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.draw.CgInstanceKind;

/**
 * A quad's instance record, in floats: what {@link CgCanvas#quads} takes from a caller baking its own. Read from
 * {@link CgInstanceKind#QUAD}'s format, so naming it touches no GPU buffer.
 *
 * <pre>{@code
 * float[] records = new float[n * CgQuadRecord.FLOATS];
 * int at = i * CgQuadRecord.FLOATS;
 * records[at + CgQuadRecord.ORIGIN] = x;   // ... every field, padding left 0
 * canvas.quads(material, null, records, 0, n, 0);
 * }</pre>
 *
 * <ul>
 *   <li>{@code COLOR} is ARGB unpacked to 0..1 by division; {@code NODE} is {@code CgPalette.pack(spatial, effect)},
 *       its spatial half equal to the call's {@code spatial}.</li>
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

    private CgQuadRecord() {
    }
}
