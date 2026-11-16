package com.crystalgraphics.animation.graph;

/**
 * Where a character's root motion comes from: Unreal's {@code ERootMotionMode} (AnimInstance.cpp:2293-2305).
 *
 * <pre>{@code
 * anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);   // a run's own motion carries the character
 * }</pre>
 *
 * <ul>
 *   <li>With {@link #IGNORE} and {@link #FROM_EVERYTHING} every player of the graph holds its clip's root; with the
 *       others only a root-motion montage does.</li>
 * </ul>
 */
public enum CgRootMotionMode {
    /** Nothing extracted: the root plays as keyed. */
    NO_EXTRACTION,
    /** Extracted from the root-motion montage, the root held, and the motion thrown away. */
    IGNORE,
    /** From the one root-motion montage playing, unblended: Unreal's default, deterministic over the network. */
    FROM_MONTAGES_ONLY,
    /**
     * From every clip playing, each at its weight in the graph, and the montages: what locomotion warping reads and
     * bends ({@code CgStrideWarpingNode}, {@code CgOrientationWarpingNode}). Blended, so not deterministic over the
     * network: a networked move uses {@link #FROM_MONTAGES_ONLY}.
     */
    FROM_EVERYTHING
}
