package com.crystalgraphics.mc.modern.platform;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The client state Minecraft moved between versions, spelled once so a call site carries no directive. 26.2 moved
 * the screen, the loading overlay and the HUD's hidden flag onto {@code Minecraft.gui}, and the main target onto the
 * game renderer.
 *
 * <pre>{@code
 * Minecraft mc = Minecraft.getInstance();
 * if (CgMinecraftClient.screen(mc) == null) CgMinecraftClient.setScreen(mc, new MyScreen());
 * }</pre>
 */
public final class CgMinecraftClient {

    private CgMinecraftClient() {}

    /** The screen in front, or null when the game has none up. */
    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    /** Opens {@code screen}, or closes the current one when it is null. */
    public static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        /*mc.gui.setScreen(screen);
        *///?} else {
        mc.setScreen(screen);
        //?}
    }

    /** Whether a loading overlay (resource reload, first load) covers the game. 1.13 has none. */
    public static boolean overlayUp(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.overlay() != null;
        *///?} elif >=1.14 {
        return mc.getOverlay() != null;
        //?} else {
        /*return false;
        *///?}
    }

    /** Minecraft's own main target, which a composite over the frame lands in. */
    public static RenderTarget mainTarget(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gameRenderer.mainRenderTarget();
        *///?} else {
        return mc.getMainRenderTarget();
        //?}
    }

    /** Whether the player has hidden the HUD (F1). */
    public static boolean hudHidden(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.hud.isHidden();
        *///?} else {
        return mc.options.hideGui;
        //?}
    }

    /** Hides or shows the HUD, as F1 does. */
    public static void setHudHidden(Minecraft mc, boolean hidden) {
        //? if >=26.2 {
        /*if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
        *///?} else {
        mc.options.hideGui = hidden;
        //?}
    }
}
