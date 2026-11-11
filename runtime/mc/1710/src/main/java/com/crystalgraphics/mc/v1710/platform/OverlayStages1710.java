package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.render.stage.CgRenderStage;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

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
        try {
            stage.fireOverlay(mc.displayWidth, mc.displayHeight, scale,
                    OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0);
        } finally {
            // What Minecraft's fixed-function present assumes: it binds its screen texture to the active unit and draws
            // with no program, so a unit left elsewhere presents a white window. The section's close restores what it
            // read, not this.
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
    }
}
