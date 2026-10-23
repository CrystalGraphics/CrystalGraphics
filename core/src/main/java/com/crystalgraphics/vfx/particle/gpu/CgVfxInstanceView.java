package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.look.CgVfxParam;

import javax.annotation.Nullable;

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

    /** Its seed's bits: what a roll once an instance keys on. */
    default int seed() {
        return 0;
    }

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

    /**
     * Skeleton point {@code i} of its emitter's {@code CgVfxSockets}, relative to the origin, into {@code out}; false where
     * nothing answers it, and there it spawns nothing. Render thread.
     */
    default boolean socket(int i, float[] out) {
        return false;
    }

    /**
     * How the instance is turned in the world at the step's end, a unit quaternion: its effect's orientation. Its spawns'
     * shape, offset and launch turn by it; the identity unless its effect is turned or follows a turned point.
     */
    default float rotationX() {
        return 0f;
    }

    default float rotationY() {
        return 0f;
    }

    default float rotationZ() {
        return 0f;
    }

    default float rotationW() {
        return 1f;
    }

    /**
     * Its rotation's change over the step, a unit quaternion, by which a {@code local()} instance's live particles turn
     * about its origin; the identity for one that is not local, or did not turn.
     */
    default float turnX() {
        return 0f;
    }

    default float turnY() {
        return 0f;
    }

    default float turnZ() {
        return 0f;
    }

    default float turnW() {
        return 1f;
    }

    /**
     * Point {@code name} of the entity its effect follows ({@code CgEntityAttachments}), relative to the origin, into
     * {@code out}; false where nothing answers it. What a beam end naming an actor reads. Render thread or a books worker.
     */
    default boolean point(String name, float[] out) {
        return false;
    }

    /** Its loop's seconds as this step ends: what a module input's curve or array by the loop reads. */
    default float loopSeconds() {
        return 0f;
    }

    /** User parameter {@code param}'s value this step, for {@link CgVfxUserBinding}s: its default unless its effect sets it. */
    default float user(CgVfxParam.Scalar param) {
        return param.defaultValue();
    }

    /**
     * Component {@code component} of user parameter {@code name} (without {@code user.}) this step, for
     * {@code CgVfxLifeBind}s: a number's value, a vector's or a colour's channel; {@code fallback} where its effect's
     * look has none.
     */
    default float user(String name, int component, float fallback) {
        return fallback;
    }

    /** Its effect's distance from the camera to its bounds, 0 inside: what a collider's reach reads. */
    default float cameraDistance() {
        return 0f;
    }

    /** The fluid its particles are carried by, for kinds taking {@code CgVfxDataInterfaces.GAS}; null for none. */
    @Nullable
    default CgVfxFluid gas() {
        return null;
    }

    /** The grid its particles are in, for kinds taking {@code CgVfxDataInterfaces.GRID}; null for none. */
    @Nullable
    default CgVfxGrid3D grid() {
        return null;
    }
}
