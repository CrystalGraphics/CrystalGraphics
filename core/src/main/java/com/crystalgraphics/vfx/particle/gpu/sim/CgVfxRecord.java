package com.crystalgraphics.vfx.particle.gpu.sim;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A particle as its pool stores it: the layout its shape simulates, generated from the attributes its code reads and
 * writes ({@link CgVfxShape#record()}), as Niagara's {@code BuildLayout} lays a data set out from its compiled variables.
 * Sixteen core words at fixed places, then the shape's {@link Tail} in the order its code first touches them, padded to
 * a {@code uvec4}. What a draw reads is not here: the Step's last stage writes it into the pool's
 * {@link CgVfxParticlePool#drawn() drawn} buffer, {@link #DRAWN_BYTES} a particle at the same index.
 *
 * <pre>{@code
 * CgVfxRecord layout = pool.record();
 * float x = layout.f(read, i, CgVfxRecord.POSITION);              // a core word: the same in every layout
 * int flags = layout.i(read, i, layout.word(CgVfxRecord.Tail.FLAGS));   // a tail word: ask the layout
 * recording.readback(pool.records(), 0, (long) pool.storage() * layout.bytes(), data -> read = data);
 * }</pre>
 *
 * <ul>
 *   <li>A tail attribute a layout lacks has {@link #word} {@code -1}: its value is its default (PREVIOUS the position,
 *       FLAGS 0).</li>
 *   <li>Positions are relative to the instance's origin; {@link #SIZE} is the size at birth.</li>
 *   <li>Buffers are read and written at absolute positions in their own byte order: give them native order, the GPU's.</li>
 * </ul>
 */
public final class CgVfxRecord {

    /** Core word offsets, the same in every layout ({@code lib/vfx/fx_record.glsl}): xyz from the first. */
    public static final int POSITION = 0, AGE = 3, VELOCITY = 4, SIZE = 7, LIFE = 8, SEED = 9, SPIN = 10, SPIN_RATE = 11,
            HEAT = 12, ID = 13, SLOT = 14, PARAM_ROW = 15;
    public static final int CORE_WORDS = 16;

    /** {@link Tail#FLAGS}' bit for a particle at rest on the ground. */
    public static final int RESTING = 1;

    /** Where {@link Tail#FLAGS} holds the particle's collisions so far, 16 bits, saturating: {@code fx_hit}'s count. */
    public static final int COLLISIONS_SHIFT = 16;

    /** A particle's drawn part, {@code CgParticleBuffer}'s record: what the Look stage writes and draws read. */
    public static final String DRAWN_GLSL =
            "struct FxDrawn { vec4 place; vec4 motion; vec4 state; vec4 light; vec4 color; vec4 scale; };";
    public static final int DRAWN_BYTES = 96;

    /** The attributes a shape stores only when its code reads them, each {@code components} words. */
    public enum Tail {
        /** The position before the last step: a collision's sweep, a floor's stair test. */
        PREVIOUS(3),
        /** {@link #RESTING}, a collision's completion state, and the collisions so far from {@link #COLLISIONS_SHIFT}. */
        FLAGS(1),
        /** Its colour at birth from a spawn form; an alpha below 0 takes the row's rolled alpha. */
        COLOR(4),
        /** Its mesh scale at birth from a spawn form. */
        SCALE(3),
        /** Its mesh rotator at birth from a spawn form. */
        TURN(3),
        /** Its mesh rotation rate at birth from a spawn form. */
        TURN_RATE(3),
        /**
         * A beam's state ({@code fx_beam.glsl}): its source and the source's tangent and strength, its target and the
         * target's, its noise clock and its effect's orientation: six vec4s, what Cascade's beam payload keeps between
         * updates.
         */
        BEAM(24),
        /**
         * Its light's radius scale and exponent, read once at birth by the emitter's time: Cascade's light payload
         * ({@code ParticleModules.cpp:3110-3112}).
         */
        LIGHT(2),
        /**
         * Its mesh turn's age map, {@code turnBase} then {@code turnScale}: Look turns it by its rate times
         * {@code turnBase + turnScale * age}, which a collision damps from the age it strikes at
         * ({@code ParticleModules_Collision.cpp:421-426}).
         */
        TURN_STATE(6),
        /**
         * Its orbit at birth, offset, rotation then rotation rate: Cascade's orbit payload
         * ({@code ParticleModules_Orbit.cpp:111-165}), which Look draws it away from its place by.
         */
        ORBIT(9);

        private final int components;

        Tail(int components) {
            this.components = components;
        }

        public int components() {
            return components;
        }
    }

    /** The core words alone: a shape whose code reads no tail attribute. */
    public static final CgVfxRecord CORE = new CgVfxRecord(List.of());

    private static final Map<List<Tail>, CgVfxRecord> LAYOUTS = new ConcurrentHashMap<>();

    private final List<Tail> tail;
    private final int[] at = new int[Tail.values().length];
    private final int words;

    private CgVfxRecord(List<Tail> tail) {
        this.tail = tail;
        Arrays.fill(at, -1);
        int w = CORE_WORDS;
        for (Tail t : tail) {
            at[t.ordinal()] = w;
            w += t.components();
        }
        words = (w + 3) & ~3;
    }

    /** The layout of the core then {@code declared}, in its order, each once. */
    public static CgVfxRecord of(Collection<Tail> declared) {
        List<Tail> tail = List.copyOf(new LinkedHashSet<>(declared));
        return tail.isEmpty() ? CORE : LAYOUTS.computeIfAbsent(tail, CgVfxRecord::new);
    }

    /** Its tail, in layout order. */
    public List<Tail> tail() {
        return tail;
    }

    /** Where {@code t} starts, in words; -1 when this layout lacks it. */
    public int word(Tail t) {
        return at[t.ordinal()];
    }

    public boolean has(Tail t) {
        return at[t.ordinal()] >= 0;
    }

    /** Its words, a whole number of {@code uvec4}s. */
    public int words() {
        return words;
    }

    public int vectors() {
        return words / 4;
    }

    public int bytes() {
        return words * 4;
    }

    /** Its struct, {@code FxRecord}: {@link #vectors()} {@code uvec4}s, {@code w0} first. */
    public String glsl() {
        StringBuilder s = new StringBuilder("struct FxRecord {");
        for (int v = 0; v < vectors(); v++) s.append(" uvec4 w").append(v).append(';');
        return s.append(" };").toString();
    }

    /** GLSL naming word {@code word} of a record {@code r} of this struct, as a {@code uint}. */
    public static String lane(String r, int word) {
        return r + ".w" + (word / 4) + "." + "xyzw".charAt(word % 4);
    }

    /** Float word {@code word} of record {@code record}. */
    public float f(ByteBuffer in, int record, int word) {
        return in.getFloat(record * bytes() + word * 4);
    }

    /** Integer word {@code word} of record {@code record}. */
    public int i(ByteBuffer in, int record, int word) {
        return in.getInt(record * bytes() + word * 4);
    }

    /** Writes float word {@code word} of record {@code record}: a check packing particles of its own. */
    public void putF(ByteBuffer out, int record, int word, float v) {
        out.putFloat(record * bytes() + word * 4, v);
    }

    /** Writes integer word {@code word} of record {@code record}. */
    public void putI(ByteBuffer out, int record, int word, int v) {
        out.putInt(record * bytes() + word * 4, v);
    }

    /**
     * Writes a particle into record {@code record}, as a check seeding its own does ({@link CgVfxParticlePool#seed}):
     * its previous position its position, where the layout keeps one. Its spin, spin rate, heat, flags and parameter
     * row stay as {@code out} holds them: zero in a new buffer.
     */
    public void put(ByteBuffer out, int record, float x, float y, float z, float vx, float vy, float vz, float age,
                    float life, float size, float seed, int id, int slot) {
        putF(out, record, POSITION, x);
        putF(out, record, POSITION + 1, y);
        putF(out, record, POSITION + 2, z);
        putF(out, record, VELOCITY, vx);
        putF(out, record, VELOCITY + 1, vy);
        putF(out, record, VELOCITY + 2, vz);
        putF(out, record, AGE, age);
        putF(out, record, LIFE, life);
        putF(out, record, SIZE, size);
        putF(out, record, SEED, seed);
        putI(out, record, ID, id);
        putI(out, record, SLOT, slot);
        int previous = word(Tail.PREVIOUS);
        if (previous >= 0) {
            putF(out, record, previous, x);
            putF(out, record, previous + 1, y);
            putF(out, record, previous + 2, z);
        }
        int turnState = word(Tail.TURN_STATE);
        if (turnState >= 0) for (int a = 3; a < 6; a++) putF(out, record, turnState + a, 1f);
    }

    @Override
    public String toString() {
        List<String> names = new ArrayList<>();
        for (Tail t : tail) names.add(t.name());
        return "CgVfxRecord" + names + " (" + bytes() + " bytes)";
    }
}
