package com.crystalgraphics.sequence.spawn;

import javax.annotation.Nullable;

import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * An object a sequence spawned: a target its tracks write, that the sequence ends when its spawnable is despawned or the
 * play stops. Unreal's spawned actor, destroyed by its spawn register.
 *
 * <pre>{@code
 * CgSequenceSpawner lamp = (player, spawnable, at) -> new Lamp(at);   // registered for the kind "light"
 * }</pre>
 *
 * <ul>
 *   <li>{@link #destroy} with {@code graceful}: a system finishes what is alive, a post volume fades out; without, it
 *       goes at once (a kill, a seek past its life).</li>
 *   <li>It is placed by the {@link CgAttachment} its spawner was given: follow it each frame where its spawnable
 *       follows ({@code at}'s {@code follow}), else place it once.</li>
 * </ul>
 */
public interface CgSequenceSpawned extends CgSequenceTarget {

    /** Ends it: gracefully, or at once. */
    void destroy(boolean graceful);

    /** Once a frame, after its tracks are applied: where it follows its place, it takes it now. */
    default void update() {
    }

    /** Its node or bone {@code name} as an attachment, for what is placed {@code at: { spawnable, node }}; null if none. */
    @Nullable
    default CgAttachment attachment(String name) {
        return null;
    }

    @Override
    default int slot(String name) {
        return -1;
    }

    @Override
    default void set(int slot, float[] values, int mask) {
    }

    @Override
    default void get(int slot, float[] out) {
    }
}
