package com.crystalgraphics.vfx.particle.gpu;

/**
 * One playing emitter instance, as a module kind reads it to work out its per-instance values for a step
 * ({@link CgVfxGpuModule#writeInstance}), and as a pool writes its row. {@code CgVfxEmitterInstance} has every method.
 *
 * <ul>
 *   <li>{@link #time()} is at the step's start, before the step adds its length.</li>
 *   <li>The source is where it is at the step's end; {@link #fromX()} where it was at its start, the same for a source
 *       that stays put.</li>
 * </ul>
 */
public interface CgVfxInstanceView {

    /** Where the effect is in the world, in blocks; its particles' positions are relative to it. */
    double originX();

    double originY();

    double originZ();

    /** Seconds since the instance started, at the step's start. */
    float time();

    /** Where it spawns from, relative to the origin, at the step's end. */
    float sourceX();

    float sourceY();

    float sourceZ();

    /** Where its source was at the step's start: a rate's spawns are spread from here to the source. */
    default float fromX() {
        return sourceX();
    }

    default float fromY() {
        return sourceY();
    }

    default float fromZ() {
        return sourceZ();
    }

    /** The box of the entity its effect follows: its centre relative to the source; 0 when it follows none. */
    default float boxX() {
        return 0f;
    }

    default float boxY() {
        return 0f;
    }

    default float boxZ() {
        return 0f;
    }

    /** That box's half extents; 0 when it follows none. */
    default float boxHalfX() {
        return 0f;
    }

    default float boxHalfY() {
        return 0f;
    }

    default float boxHalfZ() {
        return 0f;
    }
}
