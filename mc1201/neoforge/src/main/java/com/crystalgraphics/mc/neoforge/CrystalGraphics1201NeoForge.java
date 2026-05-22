package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

@Mod(MODID)
public final class CrystalGraphics1201NeoForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public CrystalGraphics1201NeoForge() {
        CgPlatform.register(PlatformService1201.getInstance());
        CgEngineNeoForgeEvents.register();
        CgDemoNeoForgeEvents.register();
        LOGGER.info("[CrystalGraphics] NeoForge 1.20.4 platform registered");
    }
}
