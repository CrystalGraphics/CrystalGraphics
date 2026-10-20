package com.crystalgraphics.asset.registry;

import javax.annotation.Nullable;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Something the registry learned: Unreal's {@code OnAssetAdded}, {@code OnAssetRemoved}, {@code OnAssetRenamed},
 * {@code OnAssetUpdated} and {@code OnFilesLoaded}.
 *
 * <pre>{@code
 * Runnable stop = registry.onEvent(e -> {
 *     if (e.type() == CgAssetRegistryEvent.Type.SCANNED) browser.refresh(e.namespace());
 * });
 * }</pre>
 */
@Value
@Accessors(fluent = true)
public class CgAssetRegistryEvent {

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

    Type type;
    /** The namespace it happened in. */
    String namespace;
    /** The entry; null for {@link Type#SCANNED}. */
    @Nullable CgAssetEntry entry;
    /** The id it had before a {@link Type#RENAMED}; null otherwise. */
    @Nullable String previous;
}
