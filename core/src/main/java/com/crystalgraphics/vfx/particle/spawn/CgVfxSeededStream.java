package com.crystalgraphics.vfx.particle.spawn;

import java.util.List;

import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;

/**
 * One seeded module's stream: its seed, and the draws each spawn takes from it in its module's Cascade order
 * ({@code SpawnEx}). The books step each instance's stream spawn by spawn ({@link #advance}) and hand the Step its state
 * at each spawn row's first spawn; the Step reaches a later spawn's from there.
 *
 * <pre>{@code
 * // Cascade's seeded sphere: its location's three draws, its direction's three, its radius's one.
 * new CgVfxSeededStream("shape", seed, 7, List.of(new Input(CgVfxSpawnInput.OFFSET, 0, new int[]{0, 2, 1}, false)), 3,
 *         new int[]{0, 2, 1}, null);
 * }</pre>
 *
 * @param element   its module's element id: what keys its pick among its seed's values
 * @param seed      its seed
 * @param draws     the draws a spawn takes; with a {@link #rejection}, those outside its tries
 * @param inputs    each spawn input it draws for, where its draws start in a spawn's
 * @param shape     where the spawn shape's own draws start (a Cascade sphere's direction, then its radius); -1 for none
 * @param axis      which of the shape's three direction draws each host axis takes: Cascade draws its source's x, y, z
 * @param rejection a Cascade cylinder's tries, which make a spawn's draws vary; null for none
 */
public record CgVfxSeededStream(String element, CgVfxSeed seed, int draws, List<Input> inputs, int shape, int[] axis,
                                Rejection rejection) {

    /** Streams one emitter may hold: a spawn row's spare words. */
    public static final int MAX = 8;
    /** Cascade's tries at a cylinder's direction before it takes the last ({@code ParticleModules_Location.cpp:1051}). */
    public static final int TRIES = 51;

    /**
     * A spawn input's draws: roll {@code r} of its form ({@code CgVfxFormInput.Range.rollOf}) is the spawn's draw
     * {@code first + draw[r]}, counted from the end of its shape's tries where {@code afterTries}.
     */
    public record Input(CgVfxSpawnInput target, int first, int[] draw, boolean afterTries) {
    }

    /**
     * A Cascade cylinder's tries ({@code LocationPrimitiveCylinder::SpawnEx}, 4.21:1040-1060): three draws each, from
     * draw {@code first}, until the two radial ones fall in the unit circle, at most {@link #TRIES}. {@code directions}
     * are the source's (+x at bit 0, -x at 1, ...), {@code height} its height's source axis; a radius of 0 takes its first.
     */
    public record Rejection(int first, int directions, int height, boolean zeroRadius) {

        /** The draws before {@code state}'s spawn's last try ends, its tries walked. */
        public int drawsThroughTries(int state) {
            if (zeroRadius) return first + 3;
            int s = CgVfxRandomStream.skip(state, first);
            for (int t = 1; ; t++) {
                float x = axis(0, CgVfxRandomStream.fraction(s = CgVfxRandomStream.next(s)));
                float y = axis(1, CgVfxRandomStream.fraction(s = CgVfxRandomStream.next(s)));
                float z = axis(2, CgVfxRandomStream.fraction(s = CgVfxRandomStream.next(s)));
                float r0 = height == 0 ? y : x, r1 = height == 2 ? y : z;
                if (r0 * r0 + r1 * r1 <= 1f || t >= TRIES) return first + 3 * t;
            }
        }

        /** {@code DetermineUnitDirection}'s axis {@code a} of draw {@code r}: 2r - 1, r or -r by its signs, else 0. */
        public float axis(int a, float r) {
            boolean plus = (directions >> (2 * a) & 1) != 0, minus = (directions >> (2 * a + 1) & 1) != 0;
            return plus && minus ? r * 2f - 1f : plus ? r : minus ? -r : 0f;
        }
    }

    /** The input drawing for {@code target}, or null. */
    public Input input(CgVfxSpawnInput target) {
        for (Input i : inputs) if (i.target() == target) return i;
        return null;
    }

    /** The state after one spawn's draws from {@code state}: its fixed draws, a cylinder's tries walked. */
    public int advance(int state) {
        if (rejection == null) return CgVfxRandomStream.skip(state, draws);
        return CgVfxRandomStream.skip(state, rejection.drawsThroughTries(state) + draws - rejection.first());
    }

    /**
     * The draws {@code form} takes from a stream, Cascade's cooked distributions' ({@code Distributions.cpp} 4.21:680-800):
     * a constant, curve or binding none, a pick one, a range or a random_range of curves one, or three for a vector.
     * {@code who} names it where a form has no Cascade count.
     */
    public static int drawsOf(CgVfxFormInput form, String who) {
        if (form instanceof CgVfxFormInput.Constant || form instanceof CgVfxFormInput.Binding) return 0;
        if (form instanceof CgVfxFormInput.Curve c && c.by() != CgVfxFormInput.Lookup.RANDOM && c.by() != CgVfxFormInput.Lookup.ID) {
            return 0;
        }
        if (form instanceof CgVfxFormInput.Pick) return 1;
        if (form instanceof CgVfxFormInput.Range r) {
            if (r.rolls() != null && r.rolls() != CgPropertyMeta.Rolls.PARTICLE) {
                throw new IllegalArgumentException(who + ": a seeded range rolls per particle, not per " + r.rolls());
            }
            // A vector draws its three either way: one without per_channel is Cascade's XYZ lock (GetValue3Random).
            return form.components() == 1 ? 1 : 3;
        }
        if (form instanceof CgVfxFormInput.Function f && f.fn().endsWith(":random_range")
                && drawsOf(f.first(), who) == 0 && drawsOf(f.second(), who) == 0) {
            // Cascade's UniformCurve, cooked: a table whose entry pairs one roll mixes.
            return form.components() == 1 ? 1 : 3;
        }
        throw new IllegalArgumentException(who + ": a seeded " + form.form().specName() + " has no Cascade draw count");
    }
}
