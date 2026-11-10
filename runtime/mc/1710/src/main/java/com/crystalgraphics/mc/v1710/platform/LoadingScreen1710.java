package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.loading.CgLoadingScreen;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.event.GuiOpenEvent;

/**
 * Our loading screen on 1.7.10 (A13): the main menu {@code startGame} opens once Forge's splash ends waits behind it,
 * an empty screen up meanwhile, and opens once it hides. Client only.
 *
 * <pre>{@code
 * MinecraftForge.EVENT_BUS.register(new LoadingScreen1710());   // before startGame opens the menu
 * LoadingScreen1710.draw(mc);                                    // the frame's tail, after the GUI, before its tick
 * CgLoadingScreen.runHeld();                                     // after the tick: opens the menu once hidden
 * }</pre>
 */
public final class LoadingScreen1710 {

    private static boolean firstSeen;

    /**
     * The first screen: held only if the main menu, since a connect screen (a server on the command line) connects as
     * it is made. A screen something opened meanwhile (FML's mod warnings) stays.
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        GuiScreen first = event.gui;
        if (firstSeen || first == null) return;
        firstSeen = true;
        if (!(first instanceof GuiMainMenu)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (CgLoadingScreen.holdUntilHidden(() -> {
            if (mc.currentScreen instanceof Held) mc.displayGuiScreen(first);
        })) event.gui = new Held();
    }

    /** Draws the screen over the frame, if a held menu waits on it: the frame's tail, before the frame tick. */
    public static void draw(Minecraft mc) {
        if (!CgLoadingScreen.hostDraws() || !CgGraphicsLifecycle.isInitialized()) return;
        CgDeviceSection.begin();
        try {
            int fbo = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
            CgLoadingScreen.draw(fbo, mc.displayWidth, mc.displayHeight);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** Up while the menu waits: Minecraft with no screen and no world reads keys for a player not made yet. */
    private static final class Held extends GuiScreen {
        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            // Esc would open the menu early
        }
    }
}
