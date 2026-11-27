package com.crystalgraphics.net.object;

/**
 * Why a player stopped watching an object, as its server behaviour hears it ({@link CgNetBehaviour#unwatched}).
 *
 * <pre>{@code
 * @Override public void unwatched(CgNetObject obj, UUID player, CgNetUnwatched why) {
 *     if (why != CgNetUnwatched.ASKED) closeTheirScreen(player);   // the game took them away from it
 * }
 * }</pre>
 */
public enum CgNetUnwatched {
    /** {@link CgNetObject#unwatch} was called. */
    ASKED,
    /** The player disconnected. */
    LEFT,
    /** The player is in another dimension than the object. */
    DIMENSION,
    /** The player is out of the object's range, or no longer tracks its entity or chunk. */
    RANGE
}
