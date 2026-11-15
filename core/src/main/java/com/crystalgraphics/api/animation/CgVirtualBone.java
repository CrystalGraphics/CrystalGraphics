package com.crystalgraphics.api.animation;

import java.util.Objects;

/**
 * A bone a skeleton gains under {@code source} that follows {@code target} in every clip: a virtual bone. It gives a skeleton exported without them the bones an anim graph reads, an IK foot
 * that tracks its foot, without touching the mesh or any clip.
 *
 * <pre>{@code
 * CgSkeletalMesh goku = mesh.withVirtualBones(List.of(
 *         new CgVirtualBone("ik_foot_root", "root", "root"),
 *         new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"),
 *         new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
 * }</pre>
 *
 * <ul>
 *   <li>Each frame a clip is sampled, the bone is set to {@code target}'s place relative to {@code source}, as a track
 *       baked into each clip would hold it.</li>
 *   <li>A clip that carries a track for it (an export from a skeleton that had it) plays that track instead.</li>
 *   <li>{@code source} may be another virtual bone declared before it; {@code target} is a real bone.</li>
 * </ul>
 *
 * @param name   its name, unique in the skeleton
 * @param source the bone it hangs under
 * @param target the bone it follows
 */
public record CgVirtualBone(String name, String source, String target) {

    public CgVirtualBone {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
