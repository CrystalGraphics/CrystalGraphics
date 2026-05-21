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
    }
}
