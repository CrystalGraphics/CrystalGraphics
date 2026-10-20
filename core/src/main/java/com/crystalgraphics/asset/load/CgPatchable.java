package com.crystalgraphics.asset.load;

/**
 * A definition a value-only edit can patch in place: what a kind's compiler returns when its kind has value-only
 * properties.
 *
 * <pre>{@code
 * if (definition instanceof CgPatchable p) {
 *     CgPatchTable.Entry e = p.patches().entry(edit.path());
 *     if (e != null) p.patch(e, ((Number) edit.value()).doubleValue());   // else recompile
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Patches the live object, never a copy: readers keyed by its identity see the change.</li>
 *   <li>Call on the thread that owns the definition's readers (the frame thread), between steps.</li>
 * </ul>
 */
public interface CgPatchable {

    /** What it compiled each patchable property to. */
    CgPatchTable patches();

    /** Writes {@code value} at {@code entry}'s slot, recomputing what its effect names. */
    void patch(CgPatchTable.Entry entry, double value);
}
