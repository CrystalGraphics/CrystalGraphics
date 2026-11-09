package com.crystalgraphics.vfx.particle.spawn;

import java.util.Locale;

/**
 * How a tick's new particles are timed and placed along their emitter's move: interpolated unless an emitter imported
 * from Cascade keeps its own. An emitter's {@code properties.spawning}.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("sparks").spawning(CgVfxSpawning.CASCADE_LEGACY).rate(350f).burst(0f, 90)...
 * }</pre>
 *
 * <pre>{@code
 * properties: { capacity: 161, spawning: cascade_gpu }
 * }</pre>
 *
 * <ul>
 *   <li>It changes where and when spawns are born, never how many: the books are the same in every mode.</li>
 *   <li>A Cascade GPU sprite emitter ignores its legacy flag in its game, so an importer writes {@link #CASCADE_GPU}
 *       for it whatever the flag says.</li>
 * </ul>
 */
public enum CgVfxSpawning {

    /**
     * The default: a tick's bursts born at its start where the source was, its rate spawns spread across it and along
     * the source's move, each stepped with its forces for the rest of the tick.
     */
    INTERPOLATED,

    /**
     * Cascade's CPU spawning: as {@link #INTERPOLATED}, except that a tick's bursts are born at the end of the frame's
     * step, so they draw at age 0 on the frame they spawn and age from the next. Cascade updates its live particles
     * before it spawns, and a burst's spawn time is 0 ({@code ParticleEmitterInstances.cpp} 4.21, 834-837, 2064).
     */
    CASCADE,

    /**
     * Cascade's {@code bUseLegacySpawningBehavior}: every rate spawn of a tick born at the tick's shared start time,
     * spawn {@code i} of {@code n} placed {@code (i + 1) / n} along the source's move, bursts likewise over their share
     * of the tick, and each moved by its velocity once more in the tick it spawns.
     */
    CASCADE_LEGACY,

    /**
     * Cascade's GPU sprites ({@code BuildNewParticles}): as {@link #CASCADE}, except that burst spawn {@code j} of
     * {@code n} is placed {@code j / n} along the source's move, the first where the source was. Their bursts are
     * injected after the simulation with a start time of 0, so they too draw at age 0.
     */
    CASCADE_GPU;

    /** Whether a tick's bursts are born at the end of the frame's step, drawn at age 0: Cascade's, legacy aside. */
    public boolean burstsAtStepEnd() {
        return this == CASCADE || this == CASCADE_GPU;
    }

    /** Its name in a file; null for {@link #INTERPOLATED}, which a file leaves out. */
    public String key() {
        return this == INTERPOLATED ? null : name().toLowerCase(Locale.ROOT);
    }

    /** The mode a file's {@code spawning} names; absent, {@link #INTERPOLATED}. */
    public static CgVfxSpawning of(Object key) {
        if (key == null) return INTERPOLATED;
        for (CgVfxSpawning s : values()) if (s != INTERPOLATED && s.key().equals(key)) return s;
        throw new IllegalArgumentException("spawning: " + key + ", not cascade, cascade_legacy or cascade_gpu");
    }
}
