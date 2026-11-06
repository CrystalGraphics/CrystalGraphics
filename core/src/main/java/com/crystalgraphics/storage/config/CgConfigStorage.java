package com.crystalgraphics.storage.config;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.format.CgFileFormat;
import com.crystalgraphics.serialization.format.CgFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A product's own files as named blobs: preferences, one session record per project. A store sits on a directory
 * ({@link CgLocalConfigStorage}), a key prefix of another ({@link CgScopedConfigStorage}) or a map
 * ({@link CgInMemoryConfigStorage}, for tests). Values go through {@code CgFiles}, so the name's extension picks the
 * format: {@code .toml}, {@code .json}, {@code .jsonc}, {@code .yml}, anything else binary.
 *
 * <pre>{@code
 * CgConfigStorage config = new CgLocalConfigStorage(CgStorageLayout.of("mymod").configIn(CgGameDirectory.game()));
 * CgConfigStorage mine = config.scoped("editor");                 // <config>/editor/
 *
 * mine.write("shelf.toml", Shelf.CODEC, shelf);                    // comments in an edited file survive
 * Shelf back = mine.read("shelf.toml", Shelf.CODEC);               // null when never written
 *
 * mine.write("notes.txt", "text");                                 // raw text, no codec
 * }</pre>
 *
 * <ul>
 *   <li>Client-local: a dedicated server's store is not a player's, so preferences never live there.</li>
 *   <li>Absence is null. An unreadable or unwritable file is logged and the store carries on.</li>
 *   <li>A typed read of a file that is not its format, or holds what the codec cannot read, throws
 *       {@link CgCodecException} naming it: a hand edit to report, never to overwrite with defaults.</li>
 *   <li>{@link #write} throws on a store whose {@link #isWritable()} is false.</li>
 * </ul>
 */
public interface CgConfigStorage {

    /** The bytes of {@code name}, or null when there is none. */
    @Nullable
    byte[] readBytes(String name);

    /**
     * Replaces {@code name}.
     *
     * @throws IllegalStateException when {@link #isWritable()} is false
     */
    void writeBytes(String name, byte[] contents);

    /** Every name stored. */
    List<String> list();

    void delete(String name);

    /** The text of {@code name}, or null when there is none. */
    @Nullable
    default String read(String name) {
        byte[] bytes = readBytes(name);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Replaces {@code name} with {@code contents}.
     *
     * @throws IllegalStateException when {@link #isWritable()} is false
     */
    default void write(String name, String contents) {
        writeBytes(name, contents.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The value in {@code name}, or null when there is none.
     *
     * @throws CgCodecException when the file is not its format or the codec cannot read it
     */
    @Nullable
    default <V> V read(String name, CgCodec<V> codec) {
        byte[] bytes = readBytes(name);
        if (bytes == null) return null;
        try {
            return formatOf(name).fromBytes(codec, bytes);
        } catch (IOException e) {
            throw new CgCodecException("Could not read " + name + ": " + e.getMessage(), e);
        } catch (CgCodecException e) {
            throw new CgCodecException(name + ": " + e.getMessage(), e);
        }
    }

    /** The value in {@code name} decoded into {@code target}; no file leaves {@code target} as it is. */
    default <V> V read(String name, CgCodec<V> codec, V target) {
        byte[] bytes = readBytes(name);
        if (bytes == null) return target;
        try {
            return formatOf(name).fromBytes(codec, bytes, target);
        } catch (IOException e) {
            throw new CgCodecException("Could not read " + name + ": " + e.getMessage(), e);
        } catch (CgCodecException e) {
            throw new CgCodecException(name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Replaces {@code name} with {@code value}; a format that keeps comments carries the old file's across.
     *
     * @throws IllegalStateException when {@link #isWritable()} is false
     */
    default <V> void write(String name, CgCodec<V> codec, V value) {
        try {
            writeBytes(name, formatOf(name).toBytes(codec, value, readBytes(name)));
        } catch (IOException e) {
            throw new CgCodecException("Could not encode " + name + ": " + e.getMessage(), e);
        }
    }

    /** The format the extension of {@code name} names. */
    static CgFileFormat formatOf(String name) {
        return CgFiles.of(Paths.get(name));
    }

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
