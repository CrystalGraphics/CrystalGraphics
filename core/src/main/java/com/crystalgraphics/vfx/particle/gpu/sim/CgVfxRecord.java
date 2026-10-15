package com.crystalgraphics.vfx.particle.gpu.sim;

import java.nio.ByteBuffer;

/**
 * A particle as a pool stores it: 80 bytes, five 16-byte lanes, the layout the Step kernel reads and appends and Range
 * reads (vfx-gpu §13.3). The Java side reads one, so a check can see what the GPU made of a step.
 *
 * <pre>{@code
 * float x = CgVfxRecord.f(read, i, CgVfxRecord.POSITION);         // what a readback holds
 * int id = CgVfxRecord.i(read, i, CgVfxRecord.ID);
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the instance's origin; {@link #SIZE} is the size at birth.</li>
 *   <li>The buffer is read and written at absolute positions in its own byte order: give it native order, the GPU's.</li>
 * </ul>
 */
public final class CgVfxRecord {

    public static final int BYTES = 80, WORDS = 20;

    /**
     * The record's GLSL. A kernel declares it itself, before its {@code Buffers { }}: the compiler lays out only a
     * struct its own file declares, so {@code range.compute} carries this line too.
     */
    public static final String GLSL =
            "struct FxRecord { vec4 positionAge; vec4 previousLife; vec4 velocitySize; vec4 seedSpin; uvec4 idSlot; };";

    /** Word offsets in a record: xyz from the first. */
    public static final int POSITION = 0, AGE = 3, PREVIOUS = 4, LIFE = 7, VELOCITY = 8, SIZE = 11, SEED = 12, SPIN = 13,
            SPIN_RATE = 14, HEAT = 15, ID = 16, SLOT = 17, FLAGS = 18, PARAM_ROW = 19;

    /** {@link #FLAGS}' bit for a particle at rest on the ground. */
    public static final int RESTING = 1;

    /** Where {@link #FLAGS} holds the particle's collisions so far, 16 bits, saturating: {@code fx_hit}'s count. */
    public static final int COLLISIONS_SHIFT = 16;

    private CgVfxRecord() {
    }

    /** Float word {@code word} of record {@code record}. */
    public static float f(ByteBuffer in, int record, int word) {
        return in.getFloat(record * BYTES + word * 4);
    }

    /** Integer word {@code word} of record {@code record}. */
    public static int i(ByteBuffer in, int record, int word) {
        return in.getInt(record * BYTES + word * 4);
    }
}
