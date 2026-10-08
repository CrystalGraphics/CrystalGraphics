package com.crystalgraphics.render.post.volume;

/** The look of an impact frame: what the picture turns into for the instant a hit lands. */
public enum CgImpact {
    /** The picture's negative. */
    INVERT,
    /** Stark black and white, split at mid-grey. */
    CONTRAST,
    /** Dark speed lines radiating from the focus, redrawn every frame. */
    LINES,
    /**
     * What glows white, the world black: anime's impact frame. What glows is the HDR scene's light past white, or the
     * picture's near-white with the scene off.
     */
    SUBJECT,
    /** {@link #SUBJECT} the other way: what glows black on a white world. */
    SUBJECT_INVERTED,
    /** {@link #SUBJECT} with white speed lines radiating from the focus, a new set each 24th of a second. */
    SUBJECT_LINES
}
