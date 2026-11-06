package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.buffer.CgBufferFormat;

import java.util.Arrays;

/**
 * A firing's object records ({@code cg_env.glsl}'s {@code CgObjectData}), each written once and named by its slot, as
 * Unreal's GPUScene collects a view's dynamic primitives ({@code FGPUScenePrimitiveCollector}). An
 * {@link CgInstanceKind#OBJECT} draw's instances carry slots, so a pass drawing a record again copies an id of four
 * bytes, not the record. A recording holds one; the executor uploads it once a frame.
 *
 * <pre>{@code
 * // The first pass to draw it writes the record, through the chunk builder as any OBJECT record
 * chunks.draw(pipeline, bindingId, mesh);
 * int at = chunks.instance();                      // a zeroed record in the table; its first float in data()
 * model.get(chunks.data(), at);
 * int slot = CgPrimitiveTable.slot(at);
 *
 * // Every later pass of the firing names it
 * chunks.draw(depthPipeline, bindingId, mesh).primitive(slot);
 * }</pre>
 *
 * <p>A slot is good only in the table that answered it, until that table's {@link #reset}: its recording's.</p>
 */
public final class CgPrimitiveTable {

    /** One record: model, normal matrix (its 3x3 read as a mat3), custom0..3. */
    public static final CgBufferFormat FORMAT = CgBufferFormat
            .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
            .mat4("modelMatrix")
            .mat4("normalMatrix")
            .vec4("custom0")
            .vec4("custom1")
            .vec4("custom2")
            .vec4("custom3")
            .build();

    /** Floats per record. */
    public static final int FLOATS = FORMAT.getFloatCount();

    private float[] data;
    private int count;

    public CgPrimitiveTable() {
        data = new float[FLOATS * 64];
    }

    private CgPrimitiveTable(float[] data, int count) {
        this.data = data;
        this.count = count;
    }

    /** The slot of the record whose first float is at {@code at} in {@link #data}. */
    public static int slot(int at) {
        return at / FLOATS;
    }

    /** Reserves one zeroed record and answers its first float in {@link #data}. */
    public int add() {
        int at = count * FLOATS;
        if (at + FLOATS > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, at + FLOATS));
        else Arrays.fill(data, at, at + FLOATS, 0f);
        count++;
        return at;
    }

    /** Appends {@code n} whole records read from {@code records} at float {@code from}; answers the first's slot. */
    public int add(float[] records, int from, int n) {
        int first = count, at = first * FLOATS, floats = n * FLOATS;
        if (at + floats > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, at + floats));
        System.arraycopy(records, from, data, at, floats);
        count += n;
        return first;
    }

    /** The records, {@link #floats} of them valid. Replaced when it grows: read it after {@link #add}. */
    public float[] data() {
        return data;
    }

    public int count() {
        return count;
    }

    public int floats() {
        return count * FLOATS;
    }

    /** Forgets every record: their slots are void. */
    public void reset() {
        count = 0;
    }

    /** A table of its own holding this one's records: what a chunk outliving its builder's table keeps. */
    public CgPrimitiveTable copy() {
        return new CgPrimitiveTable(Arrays.copyOf(data, count * FLOATS), count);
    }
}
