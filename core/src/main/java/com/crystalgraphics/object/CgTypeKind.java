package com.crystalgraphics.object;

/**
 * What a {@link CgType} declares.
 *
 * <pre>{@code
 * CgTypes.kind("crystalgraphics:shake")             // KIND: an asset kind, one file each
 * CgTypes.struct("crystalgraphics:shake.wave")      // STRUCT: a value with named fields
 * CgTypes.element("crystalgraphics:system.emitter") // ELEMENT: an id list's entry, carrying an id
 * CgTypes.dispatch("crystalgraphics:shake_pattern", "kind")   // DISPATCH: a member chosen by a key
 * }</pre>
 */
public enum CgTypeKind {
    /** An asset kind: one per file, in its folder, with a version ({@code spec/README.md} § Kinds). */
    KIND,
    /** A value with named fields, replaced or merged by key. */
    STRUCT,
    /** An entry of an id list: it carries an {@code id} and merges by it ({@code spec/core.md} § Inheritance). */
    ELEMENT,
    /** A family of structs, one chosen by a key's value; mods add members (P15). */
    DISPATCH
}
