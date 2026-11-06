package com.crystalgraphics.platform.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file whole: the old contents or the new, never half of either. Every file the engine keeps, from a cache
 * entry to a config file, is written through it.
 *
 * <pre>{@code
 * CgAtomicFile.write(cache.resolve(key), bytes);
 * }</pre>
 *
 * Written beside the target under a unique name and moved over it, so a reader never sees a partial file and two
 * writers never share a temp file. The target's folders are made.
 */
public final class CgAtomicFile {

    private CgAtomicFile() {
    }

    /** Replaces {@code file} with {@code bytes}, making its folders. */
    public static void write(Path file, byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        // Same directory as the target: elsewhere the rename may degrade to a non-atomic copy
        Path temp = Files.createTempFile(parent, ".cg-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
