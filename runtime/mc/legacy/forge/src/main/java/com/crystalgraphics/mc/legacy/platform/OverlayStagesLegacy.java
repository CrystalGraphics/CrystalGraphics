package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.render.stage.CgRenderStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The GUI stages on Forge 1.8-1.12.2: {@link CgRenderStage#HUD} once the HUD has drawn, {@link CgRenderStage#SCREEN}
 * once a screen has. On Forge's bus, client only: {@code MinecraftForge.EVENT_BUS.register(new OverlayStagesLegacy())}.
 */
public final class OverlayStagesLegacy {

    /** Posted per HUD element; ALL is the once-a-frame one. */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        //? if <1.9 {
        /*RenderGameOverlayEvent.ElementType type = event.type;
        *///?} else {
        RenderGameOverlayEvent.ElementType type = event.getType();
        //?}
        if (type == RenderGameOverlayEvent.ElementType.ALL) fire(CgRenderStage.HUD);
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        fire(CgRenderStage.SCREEN);
    }

    private static void fire(CgRenderStage stage) {
        Minecraft mc = Minecraft.getMinecraft();
        stage.fireOverlay(mc.displayWidth, mc.displayHeight, new ScaledResolution(mc).getScaleFactor(),
                mc.getFramebuffer().framebufferObject);
    }
}
