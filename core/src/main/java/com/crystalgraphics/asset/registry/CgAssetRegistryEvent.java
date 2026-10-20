package com.crystalgraphics.asset.registry;

import javax.annotation.Nullable;

/**
 * Something the registry learned: Unreal's {@code OnAssetAdded}, {@code OnAssetRemoved}, {@code OnAssetRenamed},
 * {@code OnAssetUpdated} and {@code OnFilesLoaded}.
 *
 * <pre>{@code
 * Runnable stop = registry.onEvent(e -> {
 *     if (e.type() == CgAssetRegistryEvent.Type.SCANNED) browser.refresh(e.namespace());
 * });
 * }</pre>
 *
 * @param type      what happened
 * @param namespace the namespace it happened in
 * @param entry     the entry; null for {@link Type#SCANNED}
 * @param previous  the id it had before a {@link Type#RENAMED}; null otherwise
 */
public record CgAssetRegistryEvent(Type type, String namespace, @Nullable CgAssetEntry entry, @Nullable String previous) {

    /** What happened. */
    public enum Type {
        /** An entry registered. */
        ADDED,
        /** An entry gone, its file deleted. */
        REMOVED,
        /** An entry moved: {@link #previous()} is its old id. */
        RENAMED,
        /** An entry's file changed. */
        CHANGED,
        /** A namespace's scan finished: every entry it found has been added. */
        SCANNED
    }
}
