package com.crystalgraphics.mc;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.shader.CgShaderManager;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import com.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reloads the engine's assets: all of them at once ({@link #reload()}, on F3+T or a resource pack change), or only
 * what one edited file reaches ({@link #changed}, hot reload).
 *
 * <pre>{@code
 * CgAssetReloader.reload();                                         // everything
 * CgAssetReloader.changed("crystalgraphics:shaders/lib/noise.glsl");  // any thread: applied at the next frame
 * }</pre>
 *
 * <p>Every loaded asset tracks the files it read ({@link #track}): a material shader, kernel or raw shader its own file
 * and every {@code #include}, a cached texture its images. A change refreshes the assets that read that file and no
 * others, then calls each listener's {@link CgReloadListener#onChanged}. What fails to compile or load keeps its last
 * good version, and the log names the file and the error. {@link CgHotReload} calls it for each file edited in an
 * override root.</p>
 *
 * <pre>{@code
 * // a mod's own asset, found again by what it read
 * try (CgIO.Reads reads = CgIO.recordReads()) {
 *     palette = parse(CgIO.loadSource("mymod:palettes/fire.json"));
 *     CgAssetReloader.track(this, reads.paths());                     // this: a CgReloadable
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A host's {@code CgReloadService.onReload()} calls {@link #reload()}: textures, shaders, materials and kernels
 *       in that order, each isolated, then the listeners.</li>
 *   <li>An asset that never loaded is not tracked, so a change to its file does nothing until it loads.</li>
 *   <li>Only cached textures ({@link CgTextureManager}) are tracked; one made with {@code createDirect} is not.</li>
 * </ul>
 */
public final class CgAssetReloader {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final Set<CgShaderManager> shaderManagers = Collections.newSetFromMap(new IdentityHashMap<>());

    private CgAssetReloader() {}

    /**
     * Consumers outside CrystalGraphics that cache assets of their own. @see CgReloadListener
     *
     * <p>An identity set, so registering the same listener twice is a no-op — a consumer's
     * registration usually rides a lazily-initialised class and may be reached more than once.</p>
     */
    private static final Set<CgReloadListener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Registers a consumer's cache with the reload. Idempotent. */
    public static void addListener(CgReloadListener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public static boolean removeListener(CgReloadListener listener) {
        synchronized (listeners) {
            return listeners.remove(listener);
        }
    }

    public static void reload() {
        reloadTextures();
        reloadShaders();
        reloadMaterials();
        invalidateTextCaches();
        // LAST, so a listener that re-reads a texture or a material gets the reloaded one.
        notifyListeners();
    }

    /**
     * Drops every registered consumer's cache, each isolated like the steps above.
     *
     * <p>Snapshotted under the lock and then called outside it: a listener may register or remove
     * one while it runs, and doing that inside the lock is a deadlock waiting for a consumer to
     * write it.</p>
     */
    private static void notifyListeners() {
        Set<CgReloadListener> snapshot;
        synchronized (listeners) {
            if (listeners.isEmpty()) return;
            snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            snapshot.addAll(listeners);
        }
        for (CgReloadListener listener : snapshot) {
            try {
                listener.onReload();
            } catch (Exception e) {
                LOGGER.error("Reload listener {} failed", listener.getClass().getName(), e);
            }
        }
    }

    /**
     * Drops cached text layouts, which are built from font data and go stale when fonts reload.
     *
     * <p>Isolated in its own try/catch like every other step here: a failure to clear a cache must
     * not prevent textures or shaders from reloading, and vice versa.</p>
     *
     * <p>Only the layout cache needs clearing. {@code CgGlyphPlacementCache} is keyed by
     * {@code CgTextLayout} identity, so once the layouts are gone its entries are unreachable and
     * are reclaimed by its own eviction — clearing it too would be harmless but redundant.</p>
     */
    private static void invalidateTextCaches() {
        try {
            CgTextLayoutCache.clear();
        } catch (Exception e) {
            LOGGER.error("Failed to invalidate text layout cache", e);
        }
    }

    private static void reloadTextures() {
        try {
            CgTextureManager.get().reloadAll();
        } catch (Exception e) {
            LOGGER.error("Failed to reload textures", e);
        }
    }

    private static void reloadShaders() {
        // First: shaders and then materials recompile against it, and an edited include must be read again.
        CgShaderPreprocessor.clearCache();
        Set<CgShaderManager> snapshot;
        synchronized (shaderManagers) {
            if (shaderManagers.isEmpty()) return;
            snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            snapshot.addAll(shaderManagers);
        }

        LOGGER.info("Reloading {} shader manager(s)", snapshot.size());

        for (CgShaderManager manager : snapshot) {
            try {
                manager.reloadAll();
            } catch (Exception e) {
                LOGGER.error("Failed to reload shader manager: {}", manager, e);
            }
        }
    }

    private static void reloadMaterials() {
        try {
            CgMaterialRegistry.get().reloadAll();
            CgMaterialShaderRegistry.get().reloadAll();
        } catch (Exception e) {
            LOGGER.error("Failed to reload materials", e);
        }
        CgCompute.reloadAll();
    }

    /** Paths {@link #changed} queued, normalized, for the next {@link #applyChanges}. */
    private static final Queue<String> CHANGED = new ConcurrentLinkedQueue<>();

    /** Normalized path to the assets that read it, and each asset to its paths; both weak, both under {@code INDEX}. */
    private static final Map<String, Set<CgReloadable>> READERS = new HashMap<>();
    private static final Map<CgReloadable, Set<String>> READS = new WeakHashMap<>();
    private static final Object INDEX = new Object();

    /**
     * Records that {@code asset} was loaded from {@code paths} (any form {@link CgIO#normalizePath} takes), replacing
     * what it was tracked with before: an edit to any of them refreshes it. Any thread. @see CgReloadable
     */
    public static void track(CgReloadable asset, Collection<String> paths) {
        Set<String> normalized = new HashSet<>();
        for (String path : paths) normalized.add(CgIO.normalizePath(path));
        synchronized (INDEX) {
            unlink(asset);
            READS.put(asset, normalized);
            for (String path : normalized) {
                READERS.computeIfAbsent(path, k -> Collections.newSetFromMap(new WeakHashMap<>())).add(asset);
            }
        }
    }

    /** Forgets {@code asset}: it was deleted. Any thread. */
    public static void untrack(CgReloadable asset) {
        synchronized (INDEX) {
            unlink(asset);
        }
    }

    private static void unlink(CgReloadable asset) {
        Set<String> previous = READS.remove(asset);
        if (previous == null) return;
        for (String path : previous) {
            Set<CgReloadable> readers = READERS.get(path);
            if (readers == null) continue;
            readers.remove(asset);
            if (readers.isEmpty()) READERS.remove(path);
        }
    }

    /**
     * Queues the asset at {@code path} as edited, in any form {@link CgIO#normalizePath} takes; the next frame
     * refreshes what read it. Any thread. A path nothing read does nothing.
     */
    public static void changed(String path) {
        CHANGED.add(CgIO.normalizePath(path));
    }

    /** Refreshes what each queued change reaches. Render thread, once a frame: {@code CgGraphicsLifecycle.tickFrame}. */
    public static void applyChanges() {
        if (CHANGED.isEmpty()) return;
        Set<String> paths = new LinkedHashSet<>();
        for (String path; (path = CHANGED.poll()) != null; ) paths.add(path);
        for (String path : paths) CgShaderPreprocessor.forget(path);
        for (String path : paths) apply(path);
    }

    private static void apply(String path) {
        List<CgReloadable> readers;
        synchronized (INDEX) {
            Set<CgReloadable> held = READERS.get(path);
            readers = held != null ? new ArrayList<>(held) : Collections.emptyList();
        }
        int refreshed = 0;
        for (CgReloadable asset : readers) {
            try {
                asset.refresh();
                refreshed++;
            } catch (RuntimeException e) {
                LOGGER.error("[hot reload] kept the last good {}: {}", asset, e.getMessage());
            }
        }
        String id = toId(path);
        Set<CgReloadListener> snapshot;
        synchronized (listeners) {
            snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            snapshot.addAll(listeners);
        }
        for (CgReloadListener listener : snapshot) {
            try {
                listener.onChanged(id);
            } catch (Exception e) {
                LOGGER.error("Reload listener {} failed on {}", listener.getClass().getName(), id, e);
            }
        }
        LOGGER.info("[hot reload] {}: {} of {} asset(s) refreshed", id, refreshed, readers.size());
    }

    /** {@code /assets/ns/rest} as {@code ns:rest}. */
    private static String toId(String normalized) {
        String rest = normalized.startsWith("/assets/") ? normalized.substring("/assets/".length()) : normalized;
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) + ":" + rest.substring(slash + 1) : rest;
    }

    public static void trackShaderManager(CgShaderManager manager) {
        if (manager == null) return;
        synchronized (shaderManagers) {
            shaderManagers.add(manager);
        }
    }
}
