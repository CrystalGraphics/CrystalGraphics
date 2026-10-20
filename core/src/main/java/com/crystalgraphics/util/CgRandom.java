package com.crystalgraphics.util;

import com.crystalgraphics.variable.CgVar;

/**
 * The engine's one random stream, for every unseeded draw: the seed of an effect, of a cue. Mirrors Unreal's
 * {@code FMath::Rand}, seeded once at startup by {@code FEngineLoop::PreInit}: from the clock, or a constant when the
 * process runs with {@code -FixedSeed} ({@code FApp::bUseFixedSeed}).
 *
 * <pre>{@code
 * // a capture that must repeat run to run: -Dcrystalgraphics.fixedSeed=true, or in code at startup
 * CgRandom.fixedSeed(true);
 *
 * int seed = CgRandom.nextInt();      // a cue's network seed
 * float unit = CgRandom.unit();       // [0, 1), an effect's seed
 * }</pre>
 *
 * <ul>
 *   <li>The stream is per thread, as {@code rand()} is on Windows: a thread starts it from the clock, or from the
 *       same constant on every thread when fixed. Draws repeat run to run only if each thread draws in the same
 *       order, so an effect that must repeat is created from one thread.</li>
 *   <li>The switch is not derived from a fixed time step ({@code CgFrameClock}): Unreal sets them separately, and
 *       benchmarks set both.</li>
 *   <li>Something that carries its own seed (a look, a cue's {@code seed}, {@code seeded(...)}) keeps it. This stream
 *       is only for what has none.</li>
 *   <li>Changing the switch re-seeds every thread's stream at its next draw.</li>
 * </ul>
 */
public final class CgRandom {

    /** Unreal seeds with 0 when fixed. */
    private static final long FIXED_SEED = 0L;
    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private static volatile boolean fixed = CgVar.bool("crystalgraphics.fixedSeed", false, CgVar.DEV | CgVar.RESTART,
            "Seed the engine's random stream from a constant, not the clock (Unreal's -FixedSeed): a capture repeats run to run.").get();
    private static volatile int generation;

    private static final ThreadLocal<Stream> STREAMS = new ThreadLocal<Stream>() {
        @Override protected Stream initialValue() {
            return new Stream();
        }
    };

    private CgRandom() {}

    /** Whether the stream starts from a constant rather than the clock: Unreal's {@code FApp::bUseFixedSeed}. */
    public static boolean fixedSeed() {
        return fixed;
    }

    /** Sets {@link #fixedSeed()}; every thread's stream starts over at its next draw. Call it at startup. */
    public static void fixedSeed(boolean fixedSeed) {
        fixed = fixedSeed;
        generation++;
    }

    /** The next 32 random bits of this thread's stream. */
    public static int nextInt() {
        Stream s = STREAMS.get();
        int g = generation;
        if (s.generation != g) {
            s.generation = g;
            s.state = fixed ? FIXED_SEED : System.nanoTime() ^ (System.identityHashCode(Thread.currentThread()) * GAMMA);
        }
        long z = (s.state += GAMMA);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (int) ((z ^ (z >>> 31)) >>> 32);
    }

    /** The next float of this thread's stream in [0, 1). */
    public static float unit() {
        return (nextInt() >>> 8) * (1f / 16777216f);
    }

    private static final class Stream {
        long state;
        int generation = -1;
    }
}
