package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.easing.CgKeyframes;

import java.util.Objects;

/**
 * Particles spawned at a steady rate while the emitter's loop runs, scaled by a curve over the loop's seconds: Niagara's
 * Spawn Rate module, its rate an input bound to {@code Emitter.LoopedAge}. An emitter takes one, through its builder.
 *
 * <pre>{@code
 * .rate(60f)                                                         // 60 a second for as long as each loop runs
 * .rate(400f, CgKeyframes.start(0f, 1f).to(1.6f, 0f, CgEasings.OUT_QUAD).build())   // thinning out over 1.6 s
 * .rate(200f, CgKeyframes.start(0.5f, 0f).to(0.5f, 1f, CgEasings.LINEAR).build())    // from half a second in
 * }</pre>
 *
 * <ul>
 *   <li>The curve's times are seconds into the loop; before its first key it holds the first value, after the last the
 *       last. Keys at one time make a step.</li>
 *   <li>Across loops with no curve it is one continuous rate: loop boundaries do not show.</li>
 *   <li>A rate between two ends ({@code most} above {@code perSecond}) is drawn again each step, as Cascade evaluates
 *       its spawn rate's distribution each tick.</li>
 * </ul>
 *
 * @param perSecond particles a second, or the least where it is drawn between two ends
 * @param most      the most a second: {@code perSecond} for one rate
 * @param scale     its scale by the loop's seconds
 */
public record CgVfxSpawnRate(float perSecond, float most, CgKeyframes scale) {

    /** No rate: what an emitter has until it is given one. */
    public static final CgVfxSpawnRate NONE = new CgVfxSpawnRate(0f, CgKeyframes.constant(1f));

    public CgVfxSpawnRate {
        if (!(perSecond >= 0f) || !(most >= perSecond)) throw new IllegalArgumentException("a rate of " + perSecond + " to " + most);
        Objects.requireNonNull(scale, "scale");
    }

    /** One rate, {@code perSecond}, scaled by {@code scale}. */
    public CgVfxSpawnRate(float perSecond, CgKeyframes scale) {
        this(perSecond, perSecond, scale);
    }

    /** {@code perSecond} particles a second, all through each loop. */
    public static CgVfxSpawnRate of(float perSecond) {
        return new CgVfxSpawnRate(perSecond, CgKeyframes.constant(1f));
    }

    /** Particles a second {@code seconds} into the loop, {@code u} of the way between its ends (0 to 1). */
    public float at(float seconds, float u) {
        return (perSecond + (most - perSecond) * u) * scale.at(seconds);
    }

    /** Whether it is drawn between two ends each step. */
    public boolean drawn() {
        return most > perSecond;
    }

    /**
     * The most it spawns a second in a loop of {@code duration} seconds: its curve's peak there, times its rate. What a
     * pool's slot is sized by.
     */
    public float peak(float duration) {
        if (most == 0f) return 0f;
        float end = Math.min(duration, Math.max(scale.end(), 0f)), peak = scale.at(0f);
        // Sampled: an eased segment may overshoot its keys.
        for (int i = 1; i <= 256; i++) peak = Math.max(peak, scale.at(end * i / 256f));
        if (duration > end) peak = Math.max(peak, scale.at(duration));
        return most * Math.max(peak, 0f);
    }
}
