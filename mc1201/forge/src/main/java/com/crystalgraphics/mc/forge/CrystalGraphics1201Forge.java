package com.crystalgraphics.mc.forge;

import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

@Mod(MODID)
public final class CrystalGraphics1201Forge {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    public CrystalGraphics1201Forge() {
        CgPlatform.register(PlatformService1201.getInstance());
        LOGGER.info("[CrystalGraphics] Forge 1.20.1 platform registered");
    }
}
