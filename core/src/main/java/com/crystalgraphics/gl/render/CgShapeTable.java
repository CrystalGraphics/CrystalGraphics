package com.crystalgraphics.gl.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;

import java.util.Arrays;

/**
 * The boxes a recording's quads are drawn as, by index: a rounded, bordered, flat, textured or nine-slice rectangle, so
 * every one of them is a quad of one pipeline and they batch, where a uniform per box broke the batch at every box.
 * Equal shapes share an entry. A shader reads an entry through {@code #pragma cg_use shape}.
 *
 * <pre>{@code
 * CgShapeTable shapes = recording.shapes();   // one per recording: its quads name its entries
 *
 * int button = shapes.begin(CgShapeTable.FLAT, 120, 24)
 *         .radii(6, 6, 6, 6, 6, 6, 6, 6)
 *         .border(1, 1, 1, 1, 0xFF3C3F41)
 *         .fill(0xFF2B2D30)
 *         .end();
 * quads.quad().at(x, y).size(120, 24).custom2(button).color(tint).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Entries {@link #PLAIN} and {@link #PREMULTIPLIED} are in every table: a quad that is its texture times its
 *       colour, straight or already premultiplied.</li>
 *   <li>An index means nothing in another recording, and nothing once the recording is reset.</li>
 *   <li>The texture a shape samples is the draw's, bound with it: shapes that differ only in texture share an entry.</li>
 *   <li>A shape's quad takes uv 0..1: the shape reads it as where in the box a fragment is.</li>
 *   <li>Filled on the recording's thread with no GL; uploaded and bound by the executor per raster pass.</li>
 * </ul>
 */
public final class CgShapeTable {

    /** The macro a shader reads an entry through. */
    public static final String MACRO_NAME = "SHAPE_DATA";

    /** The texture times the quad's colour, straight alpha. */
    public static final int PLAIN = 0;
    /** The texture times the quad's colour, its texture already premultiplied: a composited layer. */
    public static final int PREMULTIPLIED = 1;

    /** A shape's kind, the entry's first value. Kinds {@link #PLAIN} and {@link #PREMULTIPLIED} are entries 0 and 1. */
    public static final int FLAT = 2, TEXTURE = 3, NINE_SLICE = 4;

    /** Flags, the entry's second value. */
    public static final int BORDER = 1, SPLIT_BORDER = 2;

    private static final CgBufferFormat FORMAT = CgBufferFormat.builder("ShapeEntry", CgBufferFormat.MemoryLayout.STD430)
            .vec4("meta").vec4("radiiX").vec4("radiiY").vec4("borderWidths")
            .vec4("fillColor").vec4("borderColor").vec4("borderTop").vec4("borderBottom")
            .vec4("sliceBorder").vec4("sliceOuterUv").vec4("sliceInnerUv").vec4("sliceTiles").vec4("sliceMode")
            .build();
    private static final String[] FIELDS = {"meta", "radiiX", "radiiY", "borderWidths", "fillColor", "borderColor",
            "borderTop", "borderBottom", "sliceBorder", "sliceOuterUv", "sliceInnerUv", "sliceTiles", "sliceMode"};

    static final int FLOATS = 52;
    private static final int RESERVED = 2;

    private static CgShaderBuffer buffer;
    private static CgShapeTable uploaded;
    private static int uploadedVersion = -1;
    private static long uploadedFrame = -1;

    private float[] entries = new float[FLOATS * 16];
    private int count;
    private int version;
    /** Open addressing over entry indices + 1, keyed by content: what makes equal shapes one entry. */
    private int[] slots = new int[64];

    private final Shape scratch = new Shape();

    public CgShapeTable() {
        reset();
    }

    /** The table's buffer, for {@code #pragma cg_use shape}. */
    public static CgShaderBuffer buffer() {
        if (buffer == null) {
            buffer = CgShaderBufferRegistry.get()
                    .getOrCreateInternal("CgShapeTable", FORMAT, CgBindingPoints.SHAPE_TABLE, CgBufferLifetime.FRAME);
        }
        return buffer;
    }

    /**
     * Starts a shape of {@code kind} ({@link #FLAT}, {@link #TEXTURE} or {@link #NINE_SLICE}) for a box {@code width}
     * by {@code height} in its own units; {@link Shape#end()} answers its index. The builder is this table's scratch:
     * build and end in one expression.
     */
    public Shape begin(int kind, float width, float height) {
        if (kind < FLAT || kind > NINE_SLICE) throw new IllegalArgumentException("not a shape kind: " + kind);
        return scratch.reset(kind, width, height);
    }

    /** How many entries it holds, the two built in included. */
    public int count() {
        return count;
    }

    /** Makes this a copy of {@code other}: what a built frame keeps, so its recording can be reset at once. */
    public void copyFrom(CgShapeTable other) {
        if (entries.length < other.count * FLOATS) entries = new float[other.entries.length];
        System.arraycopy(other.entries, 0, entries, 0, other.count * FLOATS);
        count = other.count;
        if (slots.length != other.slots.length) slots = new int[other.slots.length];
        System.arraycopy(other.slots, 0, slots, 0, slots.length);
        version++;
    }

    /** Empties it for a new recording: the two built-in entries alone. */
    public void reset() {
        count = 0;
        Arrays.fill(slots, 0);
        Arrays.fill(entries, 0, RESERVED * FLOATS, 0f);
        entries[0] = PLAIN;
        entries[FLOATS] = PREMULTIPLIED;
        count = RESERVED;
        version++;
    }

    /** Uploads this table unless the buffer already holds it as it is, this frame, and binds it. Render thread. */
    public void bindForDraw() {
        CgShaderBuffer target = buffer();
        long frame = CgFrameRing.frame();
        if (uploaded != this || uploadedVersion != version || uploadedFrame != frame) {
            CgBufferWriter w = target.beginWrite(count);
            for (int i = 0; i < count; i++) {
                w.beginRecord();
                int o = i * FLOATS;
                for (int f = 0; f < FIELDS.length; f++, o += 4) {
                    w.vec4(FIELDS[f], entries[o], entries[o + 1], entries[o + 2], entries[o + 3]);
                }
                target.endRecord();
            }
            target.endWrite();
            uploaded = this;
            uploadedVersion = version;
            uploadedFrame = frame;
        }
        target.bind();
    }

    /** The entry {@code values} describes, added unless an equal one is there. */
    private int intern(float[] values) {
        int hash = 1;
        for (int i = 0; i < FLOATS; i++) hash = 31 * hash + Float.floatToIntBits(values[i]);
        hash ^= hash >>> 16;
        int mask = slots.length - 1;
        for (int at = hash & mask; ; at = (at + 1) & mask) {
            int held = slots[at];
            if (held == 0) {
                int index = add(values);
                slots[at] = index + 1;
                if (count * 2 > slots.length) rehash();
                return index;
            }
            if (holds(held - 1, values)) return held - 1;
        }
    }

    private boolean holds(int index, float[] values) {
        int o = index * FLOATS;
        for (int i = 0; i < FLOATS; i++) {
            if (Float.floatToIntBits(entries[o + i]) != Float.floatToIntBits(values[i])) return false;
        }
        return true;
    }

    private int add(float[] values) {
        if ((count + 1) * FLOATS > entries.length) entries = Arrays.copyOf(entries, entries.length * 2);
        System.arraycopy(values, 0, entries, count * FLOATS, FLOATS);
        version++;
        return count++;
    }

    private void rehash() {
        int[] old = slots;
        slots = new int[old.length * 2];
        int mask = slots.length - 1;
        for (int held : old) {
            if (held == 0) continue;
            int hash = 1, o = (held - 1) * FLOATS;
            for (int i = 0; i < FLOATS; i++) hash = 31 * hash + Float.floatToIntBits(entries[o + i]);
            hash ^= hash >>> 16;
            int at = hash & mask;
            while (slots[at] != 0) at = (at + 1) & mask;
            slots[at] = held;
        }
    }

    /** One shape being described: the table's scratch, ended into an index by {@link #end()}. */
    public final class Shape {

        private final float[] values = new float[FLOATS];

        private Shape reset(int kind, float width, float height) {
            Arrays.fill(values, 0f);
            values[0] = kind;
            values[2] = width;
            values[3] = height;
            values[44] = 1f;   // sliceTiles: one tile each way
            values[45] = 1f;
            values[50] = 1f;   // sliceMode: the centre is filled
            return this;
        }

        /** Each corner's elliptical radii, top-left, top-right, bottom-right, bottom-left. */
        public Shape radii(float rxTL, float ryTL, float rxTR, float ryTR, float rxBR, float ryBR, float rxBL, float ryBL) {
            set(4, rxTL, rxTR, rxBR, rxBL);
            set(8, ryTL, ryTR, ryBR, ryBL);
            return this;
        }

        /** A border band of these widths, left, top, right, bottom, in one colour. */
        public Shape border(float left, float top, float right, float bottom, int argb) {
            return border(left, top, right, bottom, argb, argb, argb);
        }

        /**
         * A border band whose top and bottom edges take their own colours where they differ from {@code argb}: an
         * inset field's bevel.
         */
        public Shape border(float left, float top, float right, float bottom, int argb, int topArgb, int bottomArgb) {
            int flags = BORDER | (topArgb != argb || bottomArgb != argb ? SPLIT_BORDER : 0);
            values[1] = flags;
            set(12, left, top, right, bottom);
            color(20, argb);
            color(24, topArgb);
            color(28, bottomArgb);
            return this;
        }

        /** A {@link #FLAT} shape's fill. */
        public Shape fill(int argb) {
            color(16, argb);
            return this;
        }

        /**
         * A {@link #NINE_SLICE} fill: the slices' insets in the box's units, the sprite's outer and inner uv rects
         * ({@code u0 v0 u3 v3}, {@code u1 v1 u2 v2}), the centre's tile counts and source size, how each axis repeats
         * (an ordinal: 0 stretch, 1 repeat, 2 round, 3 space) and whether the centre is drawn.
         */
        public Shape slices(float left, float top, float right, float bottom,
                            float u0, float v0, float u3, float v3, float u1, float v1, float u2, float v2,
                            float tilesX, float tilesY, float sourceWidth, float sourceHeight,
                            int repeatX, int repeatY, boolean fillCentre) {
            set(32, left, top, right, bottom);
            set(36, u0, v0, u3, v3);
            set(40, u1, v1, u2, v2);
            set(44, tilesX, tilesY, sourceWidth, sourceHeight);
            set(48, repeatX, repeatY, fillCentre ? 1f : 0f, 0f);
            return this;
        }

        /** The shape's index in the table: an equal one's, when there is one. */
        public int end() {
            return intern(values);
        }

        private void set(int at, float x, float y, float z, float w) {
            values[at] = x;
            values[at + 1] = y;
            values[at + 2] = z;
            values[at + 3] = w;
        }

        /** As {@code CgShaderBindings.colorARGB}: the same floats, so a shape draws as the uniforms it replaced. */
        private void color(int at, int argb) {
            set(at, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                    ((argb >> 24) & 0xFF) / 255f);
        }
    }
}
