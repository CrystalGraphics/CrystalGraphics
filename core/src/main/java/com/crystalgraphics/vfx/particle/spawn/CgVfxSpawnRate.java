package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;

import javax.annotation.Nullable;
import java.util.Map;

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
 *       its spawn rate's distribution each tick. A draw below zero spawns nothing, as Cascade clamps it.</li>
 *   <li>A rate or scale bound to a user parameter ({@link Bound}) multiplies it by the parameter's value each step,
 *       as Cascade reads its parameter distribution each tick. A binding with no remap has no most: the emitter's
 *       capacity holds it.</li>
 * </ul>
 *
 * @param perSecond particles a second, or the least where it is drawn between two ends: below zero, a share of
 *                  the draws that spawn nothing
 * @param most      the most a second: {@code perSecond} for one rate
 * @param scale     its scale by the loop's seconds
 * @param bound     a user parameter it is multiplied by each step, or null
 * @param scaleTo   where its scale is drawn each step between {@code scale} and this curve (Cascade's
 *                  {@code UniformCurve}), or null
 */
public record CgVfxSpawnRate(float perSecond, float most, CgKeyframes scale, @Nullable Bound bound,
                             @Nullable CgKeyframes scaleTo) {

    /** No rate: what an emitter has until it is given one. */
    public static final CgVfxSpawnRate NONE = new CgVfxSpawnRate(0f, CgKeyframes.constant(1f));

    /**
     * A spawn rate's {@code input} ({@code rate} or {@code scale}) bound to a user parameter: {@code bind}, compiled
     * from {@code source}, the form as its file wrote it.
     */
    public record Bound(String input, CgVfxFormInput.Binding bind, Map<?, ?> source) {
        /** Its value for an instance this step: never below 0. */
        public float value(CgVfxInstanceView view) {
            String path = bind.path();
            float raw = view.user(path.startsWith("user.") ? path.substring("user.".length()) : path, 0, bind.fallback()[0]);
            return Math.max(0f, bind.apply(raw, 0));
        }

        /** The most it gives: its remap's ends; unbounded with no remap. */
        public float most() {
            if (bind.inLo() == null || "direct".equals(bind.modes()[0])) return Float.POSITIVE_INFINITY;
            return Math.max(0f, Math.max(bind.apply(bind.inLo()[0], 0), bind.apply(bind.inHi()[0], 0)));
        }
    }

    public CgVfxSpawnRate {
        if (!Float.isFinite(perSecond) || !(most >= perSecond)) throw new IllegalArgumentException("a rate of " + perSecond + " to " + most);
        Objects.requireNonNull(scale, "scale");
    }

    public CgVfxSpawnRate(float perSecond, float most, CgKeyframes scale) {
        this(perSecond, most, scale, null, null);
    }

    /** One rate, {@code perSecond}, scaled by {@code scale}. */
    public CgVfxSpawnRate(float perSecond, CgKeyframes scale) {
        this(perSecond, perSecond, scale, null, null);
    }

    /** This, multiplied each step by {@code bound}. */
    public CgVfxSpawnRate bound(@Nullable Bound bound) {
        return new CgVfxSpawnRate(perSecond, most, scale, bound, scaleTo);
    }

    /** This, its scale drawn each step between its curve and {@code to}. */
    public CgVfxSpawnRate scaleTo(CgKeyframes to) {
        return new CgVfxSpawnRate(perSecond, most, scale, bound, to);
    }

    /** {@code perSecond} particles a second, all through each loop. */
    public static CgVfxSpawnRate of(float perSecond) {
        return new CgVfxSpawnRate(perSecond, CgKeyframes.constant(1f));
    }

    /** Particles a second {@code seconds} into the loop, {@code u} of the way between its ends (0 to 1). */
    public float at(float seconds, float u) {
        return at(seconds, u, u);
    }

    /**
     * Particles a second {@code seconds} into the loop: its rate {@code u} of the way between its ends, its scale
     * {@code v} of the way between its curves, each drawn apart as Cascade draws each distribution.
     */
    public float at(float seconds, float u, float v) {
        float by = scaleTo == null ? scale.at(seconds) : scale.at(seconds) + (scaleTo.at(seconds) - scale.at(seconds)) * v;
        // Cascade's drawn rate is clamped at zero (ParticleEmitterInstances.cpp:2014-2016).
        return Math.max(0f, (perSecond + (most - perSecond) * u) * by);
    }

    /** Whether it is drawn between two ends each step. */
    public boolean drawn() {
        return most > perSecond || scaleTo != null;
    }

    /**
     * The most it spawns a second in a loop of {@code duration} seconds: its curve's peak there, times its rate. What a
     * pool's slot is sized by.
     */
    public float peak(float duration) {
        if (most <= 0f) return 0f;
        float end = Math.min(duration, Math.max(Math.max(scale.end(), scaleTo == null ? 0f : scaleTo.end()), 0f));
        float peak = highest(0f);
        // Sampled: an eased segment may overshoot its keys.
        for (int i = 1; i <= 256; i++) peak = Math.max(peak, highest(end * i / 256f));
        if (duration > end) peak = Math.max(peak, highest(duration));
        return most * Math.max(peak, 0f) * (bound == null ? 1f : bound.most());
    }

    private float highest(float seconds) {
        return scaleTo == null ? scale.at(seconds) : Math.max(scale.at(seconds), scaleTo.at(seconds));
    }
}
