package com.crystalgraphics.storage.config;

import com.crystalgraphics.serialization.format.CgFiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>Writes are {@code CgFiles.writeAtomic}, so a crash leaves the previous file.</li>
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
    public byte[] readBytes(String name) {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}; continuing without it", file, e);
            return null;
        }
    }

    @Override
    public void writeBytes(String name, byte[] contents) {
        if (!writable) throw new IllegalStateException("Config storage at " + directory + " is read-only");
        Path target = directory.resolve(name);
        try {
            CgFiles.writeAtomic(target, contents);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", target, e);
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
