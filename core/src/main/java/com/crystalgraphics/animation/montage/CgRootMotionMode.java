package com.crystalgraphics.animation.montage;

/**
 * Where a character's root motion comes from: Unreal's {@code ERootMotionMode} (AnimInstance.cpp:2293-2305). Unreal's
 * {@code RootMotionFromEverything} has no member yet: the graph's players extract nothing.
 */
public enum CgRootMotionMode {
    /** Nothing extracted: the root plays as keyed. */
    NO_EXTRACTION,
    /** Extracted from the root-motion montage, the root held, and the motion thrown away. */
    IGNORE,
    /** From the one root-motion montage playing, unblended: Unreal's default, deterministic over the network. */
    FROM_MONTAGES_ONLY
}
