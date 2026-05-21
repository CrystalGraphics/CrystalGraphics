package com.crystalgraphics.mc.integration;

import com.crystalgraphics.demo.CgFontDemo;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;

/**
 * MC 1.7.10 Forge adapter for {@link CgFontDemo}.
 *
 * <p>Subscribes to FML and Forge events and delegates entirely to the platform-agnostic
 * {@code CgFontDemo.INSTANCE}.  No rendering state lives here.</p>
 */
public class CrystalGraphicsFontDemo {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsFontDemo");

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CrystalGraphicsFontDemo: Registered");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        CgFontDemo.INSTANCE.onMouseWheel(Mouse.getDWheel());
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        CgFontDemo.INSTANCE.render(mc.displayWidth, mc.displayHeight);
    }
}
