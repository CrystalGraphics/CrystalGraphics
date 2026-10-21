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
