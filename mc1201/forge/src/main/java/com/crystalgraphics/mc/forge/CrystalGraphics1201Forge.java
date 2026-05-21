package com.crystalgraphics.mc.forge;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Forge 1.20.1 mod entry point for CrystalGraphics.
 * Stub — SPI service registration will be added in a subsequent task.
 */
@Mod("crystalgraphics")
public final class CrystalGraphics1201Forge {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    public CrystalGraphics1201Forge() {
        LOGGER.info("[CrystalGraphics] Forge 1.20.1 mod loaded (stub)");
    }
}
