package com.crystalgraphics.vfx.particle.spawn;

/**
 * How many times an emitter's loop runs: Niagara's Emitter State Loop Behavior. Set through {@link CgVfxEmitterState}.
 *
 * <pre>{@code
 * CgVfxEmitterState.once(1.5f)          // ONCE
 * CgVfxEmitterState.loops(3, 0.5f)      // MULTIPLE: three loops of half a second
 * CgVfxEmitterState.infinite(2.9f)      // INFINITE: a loop every 2.9 s until stopped
 * }</pre>
 */
public enum CgVfxLoopBehavior {
    /** One loop, then it spawns nothing more. */
    ONCE,
    /** {@link CgVfxEmitterState#loopCount()} loops. */
    MULTIPLE,
    /** Loops until its instance is stopped: the default. */
    INFINITE
}
