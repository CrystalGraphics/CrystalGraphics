package com.crystalgraphics.asset.tree;

/**
 * What an edit costs whatever plays the asset (blueprint 01 §3.6): computed from the edited properties' flags, so a
 * listener knows whether to take a value, recompute, or rebuild.
 *
 * <pre>{@code
 * data.onChanged(e -> {
 *     if (e.change() == CgChange.VALUE) definition.patch(e.edits());   // a slider: written into the parameter block
 *     else definition.recompile();                                       // a module added, a renderer's material
 * });
 * }</pre>
 */
public enum CgChange {
    /** Every edited property is value-only: a playing instance takes the new value. */
    VALUE,
    /** A property declares values derived from it, which are recomputed (§3.10). */
    DERIVED,
    /** The asset's structure changed: the definition is rebuilt. */
    STRUCTURAL;

    /** The costlier of the two. */
    public CgChange max(CgChange other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
