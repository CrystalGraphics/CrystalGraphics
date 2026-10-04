package com.crystalgraphics.render.post.volume;

/** The look of an impact frame: what the picture turns into for the instant a hit lands. */
public enum CgImpact {
    /** The picture's negative. */
    INVERT,
    /** Stark black and white, split at mid-grey. */
    CONTRAST,
    /** Dark speed lines radiating from the focus, redrawn every frame. */
    LINES
}
