package com.crystalgraphics.render.scene.component;

/**
 * How a skeletal mesh component hides a bone ({@link CgSkeletalMeshComponent#hideBone}): its skinning collapses to a
 * point, never its pose, so its sockets and what rides them still move.
 *
 * <pre>{@code
 * role.hideBone("head", CgBoneHide.BONE);        // the head's own vertices go, a hat on a child bone stays
 * role.hideBone("left_arm", CgBoneHide.BRANCH);  // the arm and everything below it
 * role.hideBone("head", CgBoneHide.NONE);        // drawn again
 * }</pre>
 */
public enum CgBoneHide {
    /** Drawn. */
    NONE,
    /** Only the bone's own vertices: what is below it still draws (GeckoLib's {@code setHidden}). */
    BONE,
    /** The bone and every bone below it (Unreal's {@code HideBoneByName}). */
    BRANCH
}
