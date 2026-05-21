package com.crystalgraphics.mc.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CrystalGraphics1201Fabric implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CrystalGraphics] Fabric mod loaded (stub — services not yet initialised)");
        CgPlatform.register(PlatformService1201.getInstance());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    public ResourceLocation getFabricId() {return new ResourceLocation(MODID, "asset_reload");}
                    public void onResourceManagerReload(ResourceManager manager) {CgPlatform.reload().onReload();}
                });


        LOGGER.info("[CrystalGraphics] Fabric 1.20.1 platform registered");
    }
}
