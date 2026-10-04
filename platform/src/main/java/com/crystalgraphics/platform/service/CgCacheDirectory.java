package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where CrystalGraphics keeps what it can rebuild — compiled shaders, pipeline caches — under the game directory's
 * {@code crystalgraphics/cache/}. Deleting it costs a slower first launch and nothing else.
 *
 * <pre>{@code
 * // a host, wiring a device: each cache asks for its own folder
 * Path spirv = CgCacheDirectory.of("spirv");          // null: keep nothing
 * new ShadercGlslCompiler(spirv);
 * }</pre>
 *
 * <ul>
 *   <li>{@code -Dcrystalgraphics.cache.dir=<path>} moves the root; {@code -Dcrystalgraphics.cache=false} keeps
 *       nothing, every {@code of} answering null.</li>
 *   <li>Null too where the folder cannot be made, logged: a cache is an optimisation, never a failure.</li>
 *   <li>The root is {@link CgGameDirectory}'s: a host provides that first, or it is the working directory.</li>
 * </ul>
 */
public final class CgCacheDirectory {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private CgCacheDirectory() {}

    /** {@code name}'s folder under the cache root, made if missing; null where caching is off or it cannot be made. */
    public static Path of(String name) {
        if (!Boolean.parseBoolean(System.getProperty("crystalgraphics.cache", "true"))) return null;
        String configured = System.getProperty("crystalgraphics.cache.dir");
        Path root = configured != null ? Paths.get(configured)
                : CgPlatform.get(CgGameDirectory.SERVICE).get().resolve("crystalgraphics").resolve("cache");
        Path dir = root.resolve(name);
        try {
            return Files.createDirectories(dir);
        } catch (IOException | SecurityException e) {
            LOG.warn("No {} cache: {} cannot be made ({})", name, dir, e.toString());
            return null;
        }
    }
}
