package com.crystalgraphics.asset.load;

/**
 * How urgently a load runs, most urgent first. Prepare work at the frame boundary goes to the most urgent load
 * waiting; an asset two handles want runs at the higher of the two.
 *
 * <pre>{@code
 * CgVfxAssets.instance().load(List.of(SHURIKEN), CgLoadPriority.MOVE);    // a move a character could cast
 * SHURIKEN.request(CgLoadPriority.PLAYING);                               // its cue arrived: raised
 * }</pre>
 */
public enum CgLoadPriority {
    /** An editor's open asset or preview. */
    EDITOR,
    /** A cue already arrived for it. */
    PLAYING,
    /** Could be cast: a character's bound moves and palette. */
    MOVE,
    /** Wanted later, if there is time. */
    PREFETCH;

    /** Whether this runs before {@code other}. */
    public boolean above(CgLoadPriority other) {
        return ordinal() < other.ordinal();
    }

    /** The more urgent of the two. */
    public CgLoadPriority max(CgLoadPriority other) {
        return other.above(this) ? other : this;
    }
}
