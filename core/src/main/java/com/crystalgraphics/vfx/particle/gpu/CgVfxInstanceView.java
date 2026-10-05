package com.crystalgraphics.vfx.particle.gpu;

/**
 * One playing emitter instance, as a module kind reads it to work out its per-instance values for a step
 * ({@link CgVfxGpuModule#writeInstance}). {@code CgVfxEmitterInstance} has every method.
 *
 * <ul>
 *   <li>{@link #time()} is at the step's start, before the step adds its length: what the CPU's modules read in
 *       {@code tick}.</li>
 * </ul>
 */
public interface CgVfxInstanceView {

    /** Where the effect is in the world, in blocks; its particles' positions are relative to it. */
    double originX();

    double originY();

    double originZ();

    /** Seconds since the instance started, at the step's start. */
    float time();

    /** Where it spawns from, relative to the origin. */
    float sourceX();

    float sourceY();

    float sourceZ();
}
