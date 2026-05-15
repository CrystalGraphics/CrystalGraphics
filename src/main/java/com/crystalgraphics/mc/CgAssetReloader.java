package com.crystalgraphics.mc;

import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.shader.CgShaderManager;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.texture.CgTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.IReloadableResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Unified asset reload hook that triggers texture and shader recompilation
 * whenever Minecraft's resource manager reloads (e.g. F3+T, resource pack change).
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>Shaders — via {@link CgShaderManager}</li>
 *   <li>Textures — via {@link CgTextureManager}</li>
 * </ul>
 *
 * <p>Registers with Minecraft's resource manager. On reload, both textures
 * and shaders are reloaded. Failures are isolated per-type.</p>
 */
public final class CgAssetReloader {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static volatile boolean registered = false;

    private static final Set<CgShaderManager> shaderManagers = Collections.newSetFromMap(new IdentityHashMap<>());

    public static final IResourceManagerReloadListener LISTENER = resourceManager -> reload();

    private CgAssetReloader() {}

    public static void reload() {
        reloadTextures();
        reloadShaders();
        reloadMaterials();
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

    public static void register() {
        if (registered) return;

        IResourceManager resourceManager = Minecraft.getMinecraft().getResourceManager();
        if (resourceManager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) resourceManager).registerReloadListener(LISTENER);
            registered = true;
            LOGGER.info("CgAssetReloader registered");
        } else {
            LOGGER.warn("Resource manager not reloadable, CgAssetReloader not registered");
        }
    }
}