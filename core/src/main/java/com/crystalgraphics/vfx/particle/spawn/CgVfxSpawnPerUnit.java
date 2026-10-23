package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.easing.CgKeyframes;

import java.util.Objects;

/**
 * Particles spawned by the distance the emitter's source moves while its loop runs: a wake, footsteps. Cascade's
 * {@code UParticleModuleSpawnPerUnit} ({@code ParticleModules_Spawn.cpp}, {@code GetSpawnAmount}): {@code perUnit}
 * particles every {@code unit} blocks, the distance left over carried to the next step.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...           // six a block
 * CgVfxEmitter.builder("steps").capacity(64).ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.5f)
 *         .ignoringRateWhenMoving().ignoring(false, true, false))...                      // one each half block, flat
 * wake.moveTo(x, y, z);      // each tick: the books count how far it went
 * }</pre>
 *
 * <ul>
 *   <li>How fast its source travels is unbounded, so its emitter's capacity is what holds it: give it one.</li>
 *   <li>Spread along the step's path, as a rate is, so a fast source leaves an even line.</li>
 *   <li>The distance carried over is Cascade's: the step's travel less {@code unit} for each particle spawned, never
 *       below 0. With {@code perUnit} above 1 that drops what is left, as Cascade does.</li>
 *   <li>A step farther than the emitter's {@code maxStep} (above 0) is a teleport: nothing spawns and the carry is lost.</li>
 * </ul>
 *
 * @param perUnit       particles a unit, Cascade's {@code SpawnPerUnit}, times {@code scale} at the loop's seconds
 * @param scale         a curve over the loop's seconds
 * @param unit          blocks a unit, Cascade's {@code UnitScalar}
 * @param tolerance     units a step must move to count as moving, Cascade's {@code MovementTolerance}
 * @param ignoreRateWhenMoving whether a moving step spawns nothing by rate, Cascade's {@code bIgnoreSpawnRateWhenMoving}
 * @param ignored       axes whose movement does not count: bit 0 x, 1 y, 2 z, host axes
 */
public record CgVfxSpawnPerUnit(float perUnit, CgKeyframes scale, float unit, float tolerance,
                                boolean ignoreRateWhenMoving, int ignored) {

    /** Nothing by distance: what an emitter has until it is given one. */
    public static final CgVfxSpawnPerUnit NONE = new CgVfxSpawnPerUnit(0f);

    public CgVfxSpawnPerUnit {
        if (!(perUnit >= 0f)) throw new IllegalArgumentException(perUnit + " a unit");
        if (!(unit > 0f)) throw new IllegalArgumentException("a unit of " + unit + " blocks");
        Objects.requireNonNull(scale, "scale");
    }

    /** {@code perBlock} a block all through each loop: one particle every {@code 1 / perBlock} blocks. */
    public CgVfxSpawnPerUnit(float perBlock) {
        this(perBlock > 0f ? 1f : 0f, CgKeyframes.constant(1f), perBlock > 0f ? 1f / perBlock : 1f, 0.1f, false, 0);
    }

    /** {@code perUnit} every {@code unit} blocks. */
    public static CgVfxSpawnPerUnit every(float perUnit, float unit) {
        return new CgVfxSpawnPerUnit(perUnit, CgKeyframes.constant(1f), unit, 0.1f, false, 0);
    }

    /** This with its count scaled by {@code scale} over the loop's seconds. */
    public CgVfxSpawnPerUnit over(CgKeyframes scale) {
        return new CgVfxSpawnPerUnit(perUnit, scale, unit, tolerance, ignoreRateWhenMoving, ignored);
    }

    /** This counting a step as moving past {@code units} units. */
    public CgVfxSpawnPerUnit tolerance(float units) {
        return new CgVfxSpawnPerUnit(perUnit, scale, unit, units, ignoreRateWhenMoving, ignored);
    }

    /** This spawning nothing by rate in a step that moves. */
    public CgVfxSpawnPerUnit ignoringRateWhenMoving() {
        return new CgVfxSpawnPerUnit(perUnit, scale, unit, tolerance, true, ignored);
    }

    /** This not counting movement along the axes given true. */
    public CgVfxSpawnPerUnit ignoring(boolean x, boolean y, boolean z) {
        return new CgVfxSpawnPerUnit(perUnit, scale, unit, tolerance, ignoreRateWhenMoving, (x ? 1 : 0) | (y ? 2 : 0) | (z ? 4 : 0));
    }

    /** Particles a unit {@code seconds} into the loop: Cascade's {@code SpawnPerUnit} read at the emitter's time. */
    public float at(float seconds) {
        return perUnit * scale.at(seconds);
    }

    /** Particles a block {@code seconds} into the loop. */
    public float perBlockAt(float seconds) {
        return at(seconds) / unit;
    }

    /** Particles a block at its curve's start: above 0 when it spawns by distance at all. */
    public float perBlock() {
        return perUnit / unit;
    }

    /** This at {@code scale} times its size: spaced as far apart, relative to it, as {@code CgVfxEmitter.scaled} scales it. */
    public CgVfxSpawnPerUnit scaled(float scale) {
        return new CgVfxSpawnPerUnit(perUnit, this.scale, unit * scale, tolerance, ignoreRateWhenMoving, ignored);
    }
}
