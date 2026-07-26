package com.crystalgraphics.api.text;

/**
 * Overall paragraph direction for BiDi analysis.
 */
public enum CgTextDirection {
    /** Auto-detect via {@link java.text.Bidi#DIRECTION_DEFAULT_LEFT_TO_RIGHT} — today's default behavior. */
    AUTO,
    LTR,
    RTL
}
