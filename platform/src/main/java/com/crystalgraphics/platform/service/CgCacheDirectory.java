package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 *   <li>A cache keyed by what made its entries grows by a set with every source change: it {@link #prune}s itself.</li>
 * </ul>
 */
public final class CgCacheDirectory {

    public static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.cache", true, CgVar.DEV,
            "Keep what can be rebuilt across launches; false rebuilds SPIR-V and the Vulkan pipeline cache each launch.");
    public static final CgVar.Text DIR = CgVar.text("crystalgraphics.cache.dir", "", CgVar.DEV,
            "The cache root, a folder per kind (spirv, vulkan); empty for <game directory>/crystalgraphics/cache.");

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private CgCacheDirectory() {}

    /** {@code name}'s folder under the cache root, made if missing; null where caching is off or it cannot be made. */
    public static Path of(String name) {
        if (!ENABLED.get()) return null;
        Path root = DIR.isSet() ? Paths.get(DIR.get())
                : CgPlatform.get(CgGameDirectory.SERVICE).get().resolve("crystalgraphics").resolve("cache");
        Path dir = root.resolve(name);
        try {
            return Files.createDirectories(dir);
        } catch (IOException | SecurityException e) {
            LOG.warn("No {} cache: {} cannot be made ({})", name, dir, e.toString());
            return null;
        }
    }

    /**
     * Bounds a folder of entries named by what made them: once its files pass {@code most} bytes, the least recently
     * used go until it holds {@code keep}. A content-addressed cache needs it: every source change is a new key and the
     * old entry is never read again. Runs on a daemon thread; the folder's subfolders are included.
     *
     * <pre>{@code
     * Path dir = CgCacheDirectory.of("glprogram");
     * if (dir != null) CgCacheDirectory.prune(dir, 512L << 20, 384L << 20);   // once, as the cache opens
     * ...
     * CgCacheDirectory.used(file);                                         // at every hit
     * }</pre>
     *
     * <ul>
     *   <li>"Used" is the file's modified time: call {@link #used} at every hit, or an entry read every launch ages
     *       out as if never read.</li>
     *   <li>A file deleted while a load races it reads as missing: the caller compiles, as on any miss.</li>
     * </ul>
     */
    public static void prune(Path dir, long most, long keep) {
        Thread pruning = new Thread(() -> pruneNow(dir, most, keep), "crystalgraphics-cache-prune");
        pruning.setDaemon(true);
        pruning.setPriority(Thread.MIN_PRIORITY);
        pruning.start();
    }

    /** Marks {@code file} used now, for {@link #prune}. A failure is ignored: the entry only ages sooner. */
    public static void used(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | SecurityException ignored) {
            // Read-only or gone: nothing to keep fresh.
        }
    }

    private static void pruneNow(Path dir, long most, long keep) {
        long start = System.nanoTime();
        List<Path> files = new ArrayList<>();
        List<long[]> found = new ArrayList<>();   // size, modified: read with the listing, one stat a file
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file);
                        found.add(new long[]{attrs.size(), attrs.lastModifiedTime().toMillis()});
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Cache {} not pruned: {}", dir, e.toString());
            return;
        }
        long total = 0;
        long[] sizes = new long[files.size()], times = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            sizes[i] = found.get(i)[0];
            times[i] = found.get(i)[1];
            total += sizes[i];
        }
        if (total <= most) return;
        Integer[] order = new Integer[files.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
        int deleted = 0;
        long before = total;
        for (int i = 0; i < order.length && total > keep; i++) {
            try {
                Files.deleteIfExists(files.get(order[i]));
                total -= sizes[order[i]];
                deleted++;
            } catch (IOException ignored) {
                // In use or read-only: the next launch's prune tries again.
            }
        }
        LOG.info("Cache {} pruned: {} files, {} MB to {} MB in {} ms", dir, deleted, before >> 20, total >> 20,
                (System.nanoTime() - start) / 1_000_000);
    }
}
