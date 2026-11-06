package com.crystalgraphics.storage.config;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A product's own files as named text blobs: preferences, one session record per project. A store sits on a directory
 * ({@link CgLocalConfigStorage}), a key prefix of another ({@link CgScopedConfigStorage}) or a map
 * ({@link CgInMemoryConfigStorage}, for tests).
 *
 * <pre>{@code
 * CgConfigStorage config = new CgLocalConfigStorage(CgStorageLayout.of("mymod").configIn(CgGameDirectory.game()));
 * CgConfigStorage mine = config.scoped("apps").scoped("editor");   // <config>/apps/editor/
 * mine.write("settings.json", json);
 * String back = mine.read("settings.json");                                       // null when never written
 * }</pre>
 *
 * <ul>
 *   <li>Client-local: a dedicated server's store is not a player's, so preferences never live there.</li>
 *   <li>Absence is null, never an exception; failures are logged and the store carries on.</li>
 *   <li>{@link #write} throws on a store whose {@link #isWritable()} is false.</li>
 * </ul>
 */
public interface CgConfigStorage {

    /** The contents of {@code name}, or null when there is none. */
    @Nullable
    String read(String name);

    /**
     * Replaces {@code name}.
     *
     * @throws IllegalStateException when {@link #isWritable()} is false
     */
    void write(String name, String contents);

    /** Every name stored. */
    List<String> list();

    void delete(String name);

    /** False for a store that cannot be written, such as a directory that could not be created. */
    default boolean isWritable() {
        return true;
    }

    /**
     * The directory this store keeps its files in, or null when it is not on disk (an in-memory store). For derived
     * output that must be a real folder; settings go through {@link #read} and {@link #write}.
     */
    @Nullable
    default Path directory() {
        return null;
    }

    /**
     * This store's named corner of itself. Two applications sharing one directory each get their own, so one's
     * {@code settings.json} never replaces the other's. A directory-backed store answers with a real subdirectory.
     *
     * <pre>{@code
     * CgConfigStorage editor = config.scoped("mymod:editor");   // ':' and '/' become '.'
     * }</pre>
     */
    default CgConfigStorage scoped(String scope) {
        if (scope == null || scope.isEmpty()) return this;
        // A colon is not a legal path character on Windows, so a namespaced id is sanitised before it can reach disk.
        return new CgScopedConfigStorage(this, scope.replace(':', '.').replace('/', '.'));
    }
}
