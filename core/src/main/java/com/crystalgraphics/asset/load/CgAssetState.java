package com.crystalgraphics.asset.load;

/**
 * How far an asset has loaded. Each state holds everything the one before it does; {@link #FAILED} can follow any.
 *
 * <pre>{@code
 * REGISTERED ─read─► TREE ─merge(parent)─► RESOLVED ─compile─► COMPILED ─prepare(GPU)─► READY
 * }</pre>
 */
public enum CgAssetState {
    /** Known to the registry; nothing read. */
    REGISTERED,
    /** Its file read and upgraded to its kind's current {@code _v}. */
    TREE,
    /** Merged over its parent and validated with no error. */
    RESOLVED,
    /** Its definition built, its hard dependencies compiled before it. A dedicated server stops here. */
    COMPILED,
    /** Its GPU side resident: textures, meshes, pipelines, kernels. */
    READY,
    /** A step failed; its issues and message say where. It stays failed until its files change. */
    FAILED;

    /** Whether it has reached {@code state}, never true once failed. */
    public boolean atLeast(CgAssetState state) {
        return this != FAILED && ordinal() >= state.ordinal();
    }
}
