package com.crystalgraphics.mc.forge;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

@Mod(MODID)
public final class CrystalGraphics1201Forge {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    public CrystalGraphics1201Forge() {
        LOGGER.info("[CrystalGraphics] Forge 1.20.1 mod loaded (stub)");
        CgPlatform.register(PlatformService1201.getInstance());
        LOGGER.info("[CrystalGraphics] Forge 1.20.1 platform registered");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((stage, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) -> 
                    stage.wait(null).thenRunAsync(() -> CgPlatform.reload().onReload(), gameExecutor));
        }
    }

    }
}
