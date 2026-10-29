package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.render.stage.CgRenderStage;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/**
 * The GUI stages on 1.7.10: {@link CgRenderStage#HUD} once the HUD has drawn, {@link CgRenderStage#SCREEN} once a
 * screen has. On Forge's bus, client only: {@code MinecraftForge.EVENT_BUS.register(new OverlayStages1710())}.
 */
public final class OverlayStages1710 {

    /** Posted per HUD element; ALL is the once-a-frame one. */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) fire(CgRenderStage.HUD);
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        fire(CgRenderStage.SCREEN);
    }

    private static void fire(CgRenderStage stage) {
        Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        stage.fireOverlay(mc.displayWidth, mc.displayHeight, scale, mc.getFramebuffer().framebufferObject);
    }
}
