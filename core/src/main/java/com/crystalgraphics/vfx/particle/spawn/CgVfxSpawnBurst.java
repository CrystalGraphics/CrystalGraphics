package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

/**
 * Particles spawned all at once, {@code time} seconds into each loop of the emitter: Niagara's Spawn Burst
 * Instantaneous module. Its count is rolled each loop between two, and it fires at all only with its probability.
 *
 * <pre>{@code
 * .burst(0f, 120)                                                    // 120 at each loop's start
 * .burst(CgVfxSpawnBurst.at(0.25f, 8, 16))                           // 8 to 16, a quarter of a second in
 * .burst(CgVfxSpawnBurst.at(0f, 1).probability(0.3f))                // in three loops of ten, one particle
 * }</pre>
 *
 * <ul>
 *   <li>It re-fires every loop: an emitter that bursts once says {@code state(CgVfxEmitterState.ONCE)}.</li>
 *   <li>A burst after its loop's duration never fires.</li>
 *   <li>Each loop's roll comes from the instance's seed, the loop and the burst: every client spawns the same.</li>
 * </ul>
 */
public record CgVfxSpawnBurst(float time, int countMin, int countMax, float probability) {

    public CgVfxSpawnBurst {
        if (!(time >= 0f)) throw new IllegalArgumentException("a burst at " + time + " s");
        if (countMin < 0 || countMax < countMin) throw new IllegalArgumentException("a burst of " + countMin + " to " + countMax);
        if (!(probability >= 0f && probability <= 1f)) throw new IllegalArgumentException("a burst's probability of " + probability);
    }

    /** {@code count} particles {@code time} seconds into each loop. */
    public static CgVfxSpawnBurst at(float time, int count) {
        return new CgVfxSpawnBurst(time, count, count, 1f);
    }

    /** Between {@code min} and {@code max} particles, both included, {@code time} seconds into each loop. */
    public static CgVfxSpawnBurst at(float time, int min, int max) {
        return new CgVfxSpawnBurst(time, min, max, 1f);
    }

    /** Fires in a share {@code p} of its loops, 0 to 1. */
    public CgVfxSpawnBurst probability(float p) {
        return new CgVfxSpawnBurst(time, countMin, countMax, p);
    }

    /**
     * How many it spawns in loop {@code loop} (0 first) of an instance whose seed's bits are {@code seed}: 0 in a loop
     * its probability skips. Its rolls are keyed by its element's id ({@code CgVfxFormCompiler.key(id, "count")} and
     * {@code "probability"}), never its place among the bursts. The same on every side.
     */
    public int count(int seed, int countKey, int probabilityKey, int loop) {
        if (probability < 1f && CgVfxEmitterInstance.rand(seed ^ probabilityKey, loop, 0) >= probability) return 0;
        if (countMax == countMin) return countMin;
        int span = countMax - countMin + 1;
        return countMin + Math.min(span - 1, (int) (CgVfxEmitterInstance.rand(seed ^ countKey, loop, 0) * span));
    }
}
