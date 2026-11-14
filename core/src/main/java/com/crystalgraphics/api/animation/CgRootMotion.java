package com.crystalgraphics.api.animation;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * How a clip moves its character: Unreal's {@code UAnimSequence} root motion settings. With root motion on, the root
 * bone's movement is taken out of the pose and handed to whatever moves the character, so a lunge carries the entity
 * forward instead of stepping out of its own collision.
 *
 * <pre>{@code
 * CgAnimSequence lunge = mesh.sequence("lunge").with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
 * Matrix4f moved = lunge.extractRootMotion(0.2f, 0.016f, false, new Matrix4f());   // this frame's step, mesh space
 * }</pre>
 *
 * <ul>
 *   <li>The root is the skeleton's bone 0, as Unreal's is, unless {@code rootBone} names another.</li>
 *   <li>The root is locked only when something extracts its motion, or with {@code forceRootLock}
 *       ({@code bForceRootLock}).</li>
 * </ul>
 *
 * @param enabled       whether the root's motion is extracted ({@code bEnableRootMotion})
 * @param rootBone      the root bone's name, or null for bone 0
 * @param lock          what the root holds while its motion is extracted ({@code RootMotionRootLock})
 * @param forceRootLock lock the root even with nothing extracting
 */
public record CgRootMotion(boolean enabled, @Nullable String rootBone, Lock lock, boolean forceRootLock) {

    /** No root motion: the root plays as keyed. */
    public static final CgRootMotion NONE = new CgRootMotion(false, null, Lock.REF_POSE, false);

    /** Unreal's {@code ERootMotionRootLock}: where the root bone stays while its motion is extracted. */
    public enum Lock {
        /** The skeleton's rest pose of the root. */
        REF_POSE,
        /** The root as the clip's first frame has it. */
        ANIM_FIRST_FRAME,
        /** Identity: the root at the mesh's origin. */
        ZERO
    }

    public CgRootMotion {
        Objects.requireNonNull(lock, "lock");
    }

    /** Root motion on, the root held at {@code lock}. */
    public static CgRootMotion of(Lock lock) {
        return new CgRootMotion(true, null, lock, false);
    }
}
