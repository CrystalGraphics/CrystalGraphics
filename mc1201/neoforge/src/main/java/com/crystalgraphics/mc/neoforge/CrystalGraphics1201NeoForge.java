package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

@Mod(MODID)
public final class CrystalGraphics1201NeoForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public CrystalGraphics1201NeoForge() {
        CgPlatform.register(PlatformService1201.getInstance());
        NeoForge.EVENT_BUS.addListener(CrystalGraphics1201NeoForge::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CrystalGraphics1201NeoForge::onMouseScroll);
        LOGGER.info("[CrystalGraphics] NeoForge 1.20.4 platform registered");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (stage, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
                            stage.wait(null).thenRunAsync(() -> CgPlatform.reload().onReload(), gameExecutor));
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        CgFontDemo.INSTANCE.render(mc.getWindow().getWidth(), mc.getWindow().getHeight());
    }

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        CgFontDemo.INSTANCE.onMouseWheel((int) (event.getScrollDeltaY() * 120));
    }
}
