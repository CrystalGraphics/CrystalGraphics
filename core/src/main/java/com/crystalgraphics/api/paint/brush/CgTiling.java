package com.crystalgraphics.api.paint.brush;

/**
 * How a {@link CgBrush}'s image covers a shape larger than it: CSS's {@code background-repeat} and
 * {@code border-image-repeat}, Slate's brush tiling.
 */
public enum CgTiling {
    /** Stretched over the shape once. */
    STRETCH,
    /** Repeated at its own size on both axes, cut at the far edges. */
    REPEAT,
    /** Repeated across, stretched down. */
    REPEAT_X,
    /** Repeated down, stretched across. */
    REPEAT_Y,
    /** Repeated a whole number of times, each copy scaled to fit exactly (CSS's {@code round}). */
    ROUND,
    /** Repeated a whole number of times at its own size, the leftover spread between copies (CSS's {@code space}). */
    SPACE
}
