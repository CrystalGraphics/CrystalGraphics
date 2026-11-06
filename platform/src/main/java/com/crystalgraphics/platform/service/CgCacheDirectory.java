package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * A pruned cache's bounds: past {@code MOST} bytes, down to {@code KEEP}. A player's set (a few hundred to about
     * 2,000 programs, 10 to 75 MB of GL binaries) stays under it; a cap below the set would recompile it every launch.
     */
    public static final long MOST = 128L << 20, KEEP = 96L << 20;

    private CgCacheDirectory() {}

    /** {@code name}'s folder under the cache root, made if missing; null where caching is off or it cannot be made. */
    public static Path of(String name) {
        if (!ENABLED.get()) return null;
        Path root = DIR.isSet() ? Paths.get(DIR.get())
                : CgGameDirectory.game().resolve("crystalgraphics").resolve("cache");
        Path dir = root.resolve(name);
        try {
            return Files.createDirectories(dir);
        } catch (IOException | SecurityException e) {
            LOG.warn("No {} cache: {} cannot be made ({})", name, dir, e.toString());
            return null;
        }
    }

    /**
     * The current GL driver as a folder or file name: its renderer, vendor and version hashed, so what one driver made
     * or answered is never read under another. Render thread, its context current.
     *
     * <pre>{@code
     * Path mine = CgCacheDirectory.of("glprogram").resolve(CgCacheDirectory.driver());
     * }</pre>
     */
    public static String driver() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (int name : new int[]{CgGL.GL_RENDERER, CgGL.GL_VENDOR, CgGL.GL_VERSION}) {
                sha.update(String.valueOf(CgGL.glGetString(name)).getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The kinds keyed by what made their entries, which {@link #prune} bounds: every source change is a new key, and the
     * old entry is never read again. A kind of one file a driver ({@code vulkan}, {@code pipelines}) needs none.
     */
    public static final String[] PRUNED = {"glprogram", "spirv"};

    private static final Set<String> PRUNED_ALREADY = ConcurrentHashMap.newKeySet();

    /**
     * Bounds kind {@code name}'s folder, subfolders included: past {@link #MOST} bytes, the least recently used go until
     * it holds {@link #KEEP}. Synchronous, so run it on a worker; once a process for each kind, later calls doing
     * nothing. The engine prunes {@link #PRUNED} once the graphics start has built its programs, when this session's
     * entries have been used.
     *
     * <pre>{@code
     * CgJob.run(() -> { for (String kind : CgCacheDirectory.PRUNED) CgCacheDirectory.prune(kind); });
     * CgCacheDirectory.used(file);   // a cache, at every hit
     * }</pre>
     *
     * <ul>
     *   <li>"Used" is the file's modified time: a cache calls {@link #used} at every hit, or an entry read every launch
     *       ages out as if never read.</li>
     *   <li>A file deleted while a load races it reads as missing: the cache compiles, as on any miss.</li>
     * </ul>
     */
    public static void prune(String name) {
        if (!PRUNED_ALREADY.add(name)) return;
        Path dir = of(name);
        if (dir != null) pruneNow(dir, MOST, KEEP);
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
