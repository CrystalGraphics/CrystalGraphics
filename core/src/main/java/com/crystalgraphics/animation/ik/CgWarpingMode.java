package com.crystalgraphics.animation.ik;

/**
 * How a warping node gets what it warps by: from the graph's root motion this frame, or from values the game hands it.
 *
 * <pre>{@code
 * CgStrideWarpingNode.of(run, "pelvis", "ik_foot_root").locomotionSpeed(SPEED)   // GRAPH
 * CgStrideWarpingNode.of(run, "pelvis", "ik_foot_root").manual(STRIDE)           // MANUAL
 * }</pre>
 */
public enum CgWarpingMode {
    /** Values the game sets: a stride scale, an angle. */
    MANUAL,
    /** The players' root motion ({@code CgRootMotionMode.FROM_EVERYTHING}), read and bent. */
    GRAPH
}
