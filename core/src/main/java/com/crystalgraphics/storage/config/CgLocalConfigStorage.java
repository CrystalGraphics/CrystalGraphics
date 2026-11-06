package com.crystalgraphics.storage.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A {@link CgConfigStorage} over one directory, made if missing.
 *
 * <pre>{@code
 * CgConfigStorage config = new CgLocalConfigStorage(dir);
 * config.write("settings.json", "{}");
 * }</pre>
 *
 * <ul>
 *   <li>Writes go to a temp file in the same directory, then an atomic rename, so a crash leaves the previous file.</li>
 *   <li>Failures are logged, never thrown, so unreadable preferences mean defaults rather than a crash. Writing to a
 *       store already known read-only throws.</li>
 *   <li>{@link #scoped} is a real subdirectory.</li>
 * </ul>
 */
public final class CgLocalConfigStorage implements CgConfigStorage {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final Path directory;
    private final boolean writable;

    public CgLocalConfigStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        boolean usable = true;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOGGER.warn("Could not create the config directory {}; nothing will be saved this session", directory, e);
            usable = false;
        }
        this.writable = usable;
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public CgConfigStorage scoped(String scope) {
        if (scope == null || scope.isEmpty()) return this;
        return new CgLocalConfigStorage(directory.resolve(scope.replace(':', '.').replace('/', '.')));
    }

    @Nullable
    @Override
    public String read(String name) {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}; continuing without it", file, e);
            return null;
        }
    }

    @Override
    public void write(String name, String contents) {
        if (!writable) throw new IllegalStateException("Config storage at " + directory + " is read-only");
        Path target = directory.resolve(name);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            // Same directory as the target: elsewhere the rename may degrade to a non-atomic copy
            temp = Files.createTempFile(target.getParent(), ".cg-", ".tmp");
            Files.write(temp, contents.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", target, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless, and a second warning would bury the first
                }
            }
        }
    }

    @Override
    public List<String> list() {
        if (!Files.isDirectory(directory)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) names.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list {}", directory, e);
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(directory.resolve(name));
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}", directory.resolve(name), e);
        }
    }

    @Override
    public boolean isWritable() {
        return writable;
    }
}
