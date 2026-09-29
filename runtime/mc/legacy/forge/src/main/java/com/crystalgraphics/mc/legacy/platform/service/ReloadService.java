package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgReloadService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import org.apache.logging.log4j.LogManager;

/**
 * Forge 1.8–1.12.2's {@link CgReloadService}: F3+T and resource-pack changes reload textures, shaders and
 * materials through {@link CgAssetReloader}.
 *
 * <pre>
 * ReloadService.attachToResourceManager();   // once, on the client, once Minecraft has its resource manager
 * </pre>
 */
public final class ReloadService implements CgReloadService {

    @Override
    public void onReload() {
        CgAssetReloader.reload();
    }

    /** Registers the reload listener with Minecraft's resource manager. Client thread, from init. */
    public static void attachToResourceManager() {
        IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).registerReloadListener(reloaded -> CgPlatform.reload().onReload());
        } else {
            LogManager.getLogger("CrystalGraphics").warn("Resource manager is not reloadable; F3+T will not reload CrystalGraphics assets");
        }
    }
}
