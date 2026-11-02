package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.gl.CgGL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Our loading screen on Forge 1.8-1.12.2 (A13): the main menu {@code startGame} opens once Forge's splash ends waits
 * behind it, an empty screen up meanwhile, and opens once it hides. Client only.
 *
 * <pre>{@code
 * MinecraftForge.EVENT_BUS.register(new LoadingScreenLegacy());   // before startGame opens the menu
 * LoadingScreenLegacy.draw(mc);                                    // the frame's tail, after the GUI, before its tick
 * CgLoadingScreen.runHeld();                                       // after the tick: opens the menu once hidden
 * }</pre>
 */
public final class LoadingScreenLegacy {

    private static boolean firstSeen;

    /**
     * The first screen: held only if the main menu, since a connect screen (a server on the command line) connects as
     * it is made. A screen something opened meanwhile (Forge's mod warnings) stays.
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        //? if <1.9 {
        /*GuiScreen first = event.gui;
        *///?} else {
        GuiScreen first = event.getGui();
        //?}
        if (firstSeen || first == null) return;
        firstSeen = true;
        if (!(first instanceof GuiMainMenu)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!CgLoadingScreen.holdUntilHidden(() -> {
            if (mc.currentScreen instanceof Held) mc.displayGuiScreen(first);
        })) return;
        //? if <1.9 {
        /*event.gui = new Held();
        *///?} else {
        event.setGui(new Held());
        //?}
    }

    /** Draws the screen over the frame, if a held menu waits on it: the frame's tail, before the frame tick. */
    public static void draw(Minecraft mc) {
        if (!CgLoadingScreen.hostDraws() || !CgGraphicsLifecycle.isInitialized()) return;
        CgGL.fromHost();
        try {
            int fbo = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
            CgLoadingScreen.draw(fbo, mc.displayWidth, mc.displayHeight);
        } finally {
            CgGL.toHost();
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
