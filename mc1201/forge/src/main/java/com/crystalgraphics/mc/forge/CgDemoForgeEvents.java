package com.crystalgraphics.mc.forge;

import com.crystalgraphics.demo.CgFontDemo;
import com.crystalgraphics.demo.CgRenderDemo;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Temporary demo event subscriptions for the Forge loader.
 * All hooks here drive {@link CgFontDemo} and will be removed when the demo is replaced.
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CgDemoForgeEvents {
    private CgDemoForgeEvents() {}

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        CgFontDemo.INSTANCE.render(mc.getWindow().getWidth(), mc.getWindow().getHeight());
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        CgFontDemo.INSTANCE.onMouseWheel((int) (event.getScrollDelta() * 120));
        CgRenderDemo.INSTANCE.onMouseWheel((int) (event.getScrollDelta() * 120));
    }
}
