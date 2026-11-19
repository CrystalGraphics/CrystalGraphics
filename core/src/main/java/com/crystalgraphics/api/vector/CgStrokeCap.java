package com.crystalgraphics.api.vector;

/** How a {@link CgStroke} ends an open contour: SVG's {@code stroke-linecap}. */
public enum CgStrokeCap {
    /** Flat, at the end point. */
    BUTT,
    /** A half disc past the end point. */
    ROUND,
    /** A half square past the end point. */
    SQUARE
}
