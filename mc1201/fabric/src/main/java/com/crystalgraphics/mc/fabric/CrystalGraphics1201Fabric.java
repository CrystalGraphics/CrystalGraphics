package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.NAME;

public final class CrystalGraphics1201Fabric implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CgPlatform.register(PlatformService1201.getInstance());
        CgEngineFabricEvents.register();
        CgDemoFabricEvents.register();
        LOGGER.info("[CrystalGraphics] Fabric 1.20.1 platform registered");
    }
}
