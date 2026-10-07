package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.CgVfxParticleSet;

import java.nio.ByteBuffer;

/**
 * A particle as a pool stores it: 80 bytes, five 16-byte lanes, the layout the Step kernel reads and appends and Range
 * reads (vfx-gpu §13.3). The Java side packs and reads one, so a test can put the CPU path's particles into a pool and
 * compare what the GPU made of them.
 *
 * <pre>{@code
 * ByteBuffer records = ByteBuffer.allocateDirect(n * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
 * for (int i = 0; i < n; i++) CgVfxRecord.pack(records, i, set, i, slot, paramRow);
 *
 * float x = CgVfxRecord.f(read, i, CgVfxRecord.POSITION);         // what a readback holds
 * int id = CgVfxRecord.i(read, i, CgVfxRecord.ID);
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the instance's origin, as on the CPU; {@link #SIZE} is the size at birth.</li>
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

    /**
     * Writes particle {@code i} of {@code p} as record {@code record} of {@code out}: a particle of the instance in
     * {@code slot}, whose definition is parameter row {@code paramRow}.
     */
    public static void pack(ByteBuffer out, int record, CgVfxParticleSet p, int i, int slot, int paramRow) {
        int at = record * BYTES;
        out.putFloat(at, p.x[i]).putFloat(at + 4, p.y[i]).putFloat(at + 8, p.z[i]).putFloat(at + 12, p.age[i]);
        out.putFloat(at + 16, p.px[i]).putFloat(at + 20, p.py[i]).putFloat(at + 24, p.pz[i]).putFloat(at + 28, p.life[i]);
        out.putFloat(at + 32, p.vx[i]).putFloat(at + 36, p.vy[i]).putFloat(at + 40, p.vz[i]).putFloat(at + 44, p.size[i]);
        out.putFloat(at + 48, p.seed[i]).putFloat(at + 52, p.spin[i]).putFloat(at + 56, p.spinRate[i]).putFloat(at + 60, p.heat[i]);
        out.putInt(at + 64, p.id[i]).putInt(at + 68, slot).putInt(at + 72, p.resting[i] != 0f ? RESTING : 0).putInt(at + 76, paramRow);
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
