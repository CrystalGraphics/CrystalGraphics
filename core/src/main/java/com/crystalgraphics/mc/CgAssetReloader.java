package com.crystalgraphics.mc;

import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.shader.CgShaderManager;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.text.layout.CgTextLayoutCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Unified asset reload hook that triggers texture and shader recompilation
 * whenever a resource reload is requested by the platform (e.g. F3+T, resource pack change).
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>Shaders — via {@link CgShaderManager}</li>
 *   <li>Textures — via {@link CgTextureManager}</li>
 *   <li>Materials — via {@link CgMaterialRegistry} and {@link CgMaterialShaderRegistry}</li>
 * </ul>
 *
 * <p>This is a plain static utility. The platform side ({@code ReloadService1710}) implements
 * {@code CgReloadService} and delegates its {@code onReload()} to {@link #reload()} here.
 * On reload, textures, shaders, and materials are all reloaded in order.
 * Failures are isolated per-type.</p>
 *
 * <p>Previously this class had a {@code register()} method and a Minecraft
 * {@code IResourceManagerReloadListener LISTENER} field. Those were MC-specific
 * registration artifacts and have been replaced by the platform SPI.</p>
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
    }

    public static void trackShaderManager(CgShaderManager manager) {
        if (manager == null) return;
        synchronized (shaderManagers) {
            shaderManagers.add(manager);
        }
    }
}
