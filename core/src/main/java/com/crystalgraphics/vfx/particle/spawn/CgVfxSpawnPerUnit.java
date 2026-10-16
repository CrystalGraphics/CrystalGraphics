package com.crystalgraphics.vfx.particle.spawn;

/**
 * Particles spawned for each block the emitter's source moves while its loop runs: a wake, footsteps. Niagara's Spawn
 * Per Unit module. A step farther than the emitter's {@code maxStep} is a teleport and spawns nothing, as Niagara's Max
 * Frame Distance.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...
 * wake.moveTo(x, y, z);      // each tick: the books count how far it went
 * }</pre>
 *
 * <ul>
 *   <li>How fast its source travels is unbounded, so its emitter's capacity is what holds it: give it one.</li>
 *   <li>Spread along the step's path, as a rate is, so a fast source leaves an even line.</li>
 * </ul>
 */
public record CgVfxSpawnPerUnit(float perBlock) {

    /** Nothing by distance: what an emitter has until it is given one. */
    public static final CgVfxSpawnPerUnit NONE = new CgVfxSpawnPerUnit(0f);

    public CgVfxSpawnPerUnit {
        if (!(perBlock >= 0f)) throw new IllegalArgumentException(perBlock + " a block");
    }

    /** This at {@code scale} times its size: spaced as far apart, relative to it, as {@code CgVfxEmitter.scaled} scales it. */
    public CgVfxSpawnPerUnit scaled(float scale) {
        return new CgVfxSpawnPerUnit(perBlock / scale);
    }
}
