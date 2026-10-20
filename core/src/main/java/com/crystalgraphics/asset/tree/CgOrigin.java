package com.crystalgraphics.asset.tree;

/**
 * Where a resolved value comes from: Unreal's overridden-property operations ({@code EOverriddenPropertyOperation}:
 * {@code None}, {@code Modified}, {@code Replace}, {@code Add}, {@code Remove}), made the rule.
 *
 * <pre>{@code
 * data.origin("emitters", "h1", "enabled");   // OVERRIDDEN: this asset set it, even to its parent's value
 * data.origin("emitters", "h1");              // MODIFIED: inherited, with something under it overridden
 * data.origin("emitters", "r7");              // ADDED: an id the parent lacks
 * data.from("emitters", "h1", "name");        // dragonforge:kamehameha/charge, for INHERITED
 * }</pre>
 *
 * <ul>
 *   <li>The file is the {@link #OVERRIDDEN} and {@link #ADDED} values alone: a parent's change never rewrites it.</li>
 *   <li>{@link #DISABLED} and {@link #REMOVED} elements stay in the tree, so the parent's later edits still reach them
 *       and {@code reset} restores them.</li>
 * </ul>
 */
public enum CgOrigin {
    /** The type's default: nothing in the chain sets it. */
    DEFAULT,
    /** A parent's value, untouched here. */
    INHERITED,
    /** A parent's map, list or element with something under it overridden here: Unreal's {@code Modified}. */
    MODIFIED,
    /** Set by this asset whole, even where it equals the parent's: Unreal's {@code Replace}. */
    OVERRIDDEN,
    /** An element this asset added: an id its parent lacks. */
    ADDED,
    /** An inherited element this asset turned off with {@code enabled: false}. */
    DISABLED,
    /** An inherited element this asset dropped with {@code remove:}. */
    REMOVED
}
