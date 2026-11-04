package com.crystalgraphics.object;

/**
 * What a property is to each system that reads it. Mirrors Unreal's {@code EPropertyFlags} ({@code CPF_*}), cut
 * to the ones the format needs.
 *
 * <pre>{@code
 * .property("capacity", CgValueType.INT).flags(CgPropertyFlag.STRUCTURAL)         // a change restarts what plays it
 * .property("duration", CgValueType.FLOAT).flags(CgPropertyFlag.KEYABLE)          // a track may key it
 * .property("radius", CgValueType.FLOAT).flags(CgPropertyFlag.NETWORKED)          // carried by a cue's fields
 * }</pre>
 *
 * <ul>
 *   <li>A property without {@link #STRUCTURAL} is value-only: editing it patches what plays.</li>
 *   <li>{@link #NETWORKED} takes its replication mode from {@link CgPropertyMeta#replication()}.</li>
 * </ul>
 */
public enum CgPropertyFlag {
    /** An {@code editor} block: kept whole, skipped by resolve, dropped by the cook ({@code CPF_EditorOnly}). */
    EDITOR_ONLY,
    /** Never written ({@code CPF_Transient}). */
    TRANSIENT,
    /** Resolved on a dedicated server ({@code NeedsLoadForServer}): what a server needs of the asset. */
    HEADLESS,
    /** A change rebuilds what plays the asset, where any other edit only updates its values. */
    STRUCTURAL,
    /** A sequence track may key it ({@code CPF_Interp}). */
    KEYABLE,
    /** May differ per playing instance: a binding, a per-instance roll. */
    PER_INSTANCE,
    /** Carried by a cue's networked fields ({@code CPF_Net}). */
    NETWORKED,
    /**
     * Gathered by the asset registry's scan without loading the asset ({@code AssetRegistrySearchable}): a cue's
     * {@code tag}, read as {@code entry.searchable().get("tag")}. A top-level scalar only.
     */
    SEARCHABLE,
    /** A {@link #SEARCHABLE} value no two assets of the kind share: a second fails at registration, naming both. */
    UNIQUE,
    /**
     * Names the asset as its id does: an inline child that leaves it unwritten takes its block's plus {@code .<name>}
     * (a cue {@code rpgcore:cue.ground_break}'s child {@code hard} is {@code rpgcore:cue.ground_break.hard}).
     */
    NAMES_ASSET,
    /**
     * Declares the gameplay tags it holds, a top-level tag or list of tags: the registry's scan registers them, and their
     * ancestors, before anything compiles. A cue's {@code tag}, a rig's {@code tags}. Any other field naming a tag only
     * refers to it.
     */
    DECLARES_TAG,
    /** Named by a code contract: the editor refuses a rename or delete, naming the class. */
    LOCKED,
    /** Read and upgraded, never written ({@code CPF_Deprecated}). */
    DEPRECATED,
    /**
     * No default defines it: an asset gives it wherever its {@code visible_when} gate holds, or fails at load naming
     * it. A module input declared with no {@code default} (a mesh, a field, a speed limit); {@code default: null} is
     * optional instead, left unread when absent.
     */
    NO_DEFAULT
}
