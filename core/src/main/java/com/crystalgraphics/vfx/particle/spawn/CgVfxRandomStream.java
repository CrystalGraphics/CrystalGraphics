package com.crystalgraphics.vfx.particle.spawn;

/**
 * Cascade's seeded random stream: what a {@code _Seeded} module's inputs draw from, so the same seed gives the same
 * particles every play. Mirrors Unreal 4.21's {@code FRandomStream}: a 32-bit linear congruential state, mutated before
 * each draw, a draw being the state's low 23 bits over 2<sup>23</sup>.
 *
 * <pre>{@code
 * int state = 14;                                  // Initialize(14)
 * float a = CgVfxRandomStream.fraction(state = CgVfxRandomStream.next(state));
 * float b = CgVfxRandomStream.fraction(state = CgVfxRandomStream.next(state));
 * }</pre>
 *
 * <p>Spawn {@code k} of a module drawing {@code d} fractions a spawn starts where {@code k * d} draws left it, which
 * {@link #skip} reaches without stepping through them:</p>
 *
 * <pre>{@code
 * int at = CgVfxRandomStream.skip(seed, (long) k * d);    // the state before spawn k's first draw
 * float x = CgVfxRandomStream.fraction(CgVfxRandomStream.next(at));
 * }</pre>
 */
public final class CgVfxRandomStream {

    /** The multiplier and increment of {@code MutateSeed}. */
    public static final int A = 196314165, C = 907633515;

    private CgVfxRandomStream() {
    }

    /** The state after one more draw. */
    public static int next(int state) {
        return state * A + C;
    }

    /** The draw a state mutated to {@code state} gives: in [0, 1), a multiple of 2<sup>-23</sup>. */
    public static float fraction(int state) {
        return (state & 0x7FFFFF) * 0x1p-23f;
    }

    /** The state {@code n} draws after {@code state}, in log time: the affine step raised to {@code n}. */
    public static int skip(int state, long n) {
        int a = A, c = C, accA = 1, accC = 0;
        for (long m = n; m > 0; m >>>= 1) {
            if ((m & 1) != 0) {
                accA *= a;
                accC = accC * a + c;
            }
            c = c * a + c;
            a *= a;
        }
        return state * accA + accC;
    }
}
