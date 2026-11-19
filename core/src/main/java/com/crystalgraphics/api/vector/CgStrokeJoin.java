package com.crystalgraphics.api.vector;

/** How a {@link CgStroke} turns a corner: SVG's {@code stroke-linejoin}. */
public enum CgStrokeJoin {
    /** A sharp point, cut to a bevel past the stroke's miter limit. */
    MITER,
    /** An arc. */
    ROUND,
    /** A straight cut across the corner. */
    BEVEL
}
