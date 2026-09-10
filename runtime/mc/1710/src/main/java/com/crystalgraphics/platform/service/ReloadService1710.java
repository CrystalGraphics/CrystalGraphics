package com.crystalgraphics.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgReloadService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MC 1.7.10 implementation of {@link CgReloadService}. This is the sole implementor
 * of that interface in the mc1710 platform layer.
 *
 * <p>{@link #onReload()} delegates to {@link CgAssetReloader#reload()}, which triggers
 * texture, shader, and material reloads in order with per-type failure isolation.</p>
 *
 * <p>Call {@link #attachToResourceManager()} once in FML init to wire Minecraft's
 * {@link IReloadableResourceManager} reload events to this service.</p>
 */
public final class ReloadService1710 implements CgReloadService {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    @Override
    public void onReload() { CgAssetReloader.reload(); }

    /**
     * Attach a reload listener to the current Minecraft resource manager so that
     * F3+T / resource pack changes trigger {@link CgPlatform#reload()}.
     * Must be called on the client thread after Minecraft has initialised its resource manager.
     */
    public static void attachToResourceManager() {
        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        if (rm instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) rm).registerReloadListener(
                resourceManager -> CgPlatform.reload().onReload());
        } else {
            LOGGER.warn("[CrystalGraphics] Resource manager is not reloadable — hot-reload listener not registered");
        }
    }
}
