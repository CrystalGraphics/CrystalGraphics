package com.crystalgraphics.api.buffer;

import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.gpu.staging.CgStagingBuffer;

/**
 * The records a buffer write fills, one after another: what {@code rec.upload} and {@link CgBuffer#write} hand their
 * lambda. Each {@link #next()} is a record of the layout, zeroed, written by field name.
 *
 * <pre>{@code
 * CgGraphBuffer sparks = rec.upload("mymod.sparks", SPARK, live.size(), records -> {
 *     for (Spark s : live) {
 *         records.next().vec4("positionLife", s.x, s.y, s.z, s.life)
 *                       .vec4("velocitySeed", s.vx, s.vy, s.vz, s.seed);
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A field the layout does not have, or of another type, throws naming it.</li>
 *   <li>Records left unwritten are zero; one past the count throws.</li>
 *   <li>Valid only inside the lambda it was handed to.</li>
 * </ul>
 */
public final class CgBufferRecords {

    private final CgBufferLayout layout;
    private final CgStagingBuffer staging;
    private final CgBufferWriter writer;
    private int count, written;

    /** The engine's: one per buffer or upload slot, reused write to write. */
    public CgBufferRecords(CgBufferLayout layout) {
        this.layout = layout;
        this.staging = new CgStagingBuffer(layout.getFloatCount());
        this.writer = new CgBufferWriter(staging, layout);
    }

    /** The next record, zeroed. */
    public CgBufferWriter next() {
        if (written == count) {
            throw new IllegalStateException("record " + written + " of " + count + " " + layout.getGlslName() + " records");
        }
        written++;
        return writer.beginRecord();
    }

    /** The layout every record has. */
    public CgBufferLayout layout() {
        return layout;
    }

    /** Engine: opens a write of {@code count} records. */
    public void begin(int count) {
        if (count < 0) throw new IllegalArgumentException(count + " records");
        staging.reset();
        this.count = count;
        this.written = 0;
    }

    /** Engine: closes the write, the records left zeroed; answers the floats written, every record's. */
    public int end() {
        int rest = count - written;
        if (rest > 0) staging.reserveAndZero(rest * layout.getFloatCount());
        written = count;
        return staging.rawCursor();
    }

    /** Engine: the floats {@link #end} counted. */
    public float[] floats() {
        return staging.rawData();
    }
}
