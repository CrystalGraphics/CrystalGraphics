package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.loading.CgLoadingScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.19.2 {
import net.minecraft.network.chat.Component;
//?} elif >=1.14.3 {
/*import net.minecraft.network.chat.TextComponent;
*///?}

/**
 * The screen up while the game's first screen waits behind our loading screen (A13): empty, since the loading screen
 * is drawn over the whole frame at its end ({@link LifecycleModern#frameEnd}). Minecraft with no screen and no level
 * reads keys as a player's that does not exist yet. Client only.
 *
 * <pre>{@code
 * // a node mixin at the head of the method opening the first screen
 * if (HeldFirstScreen.hold(() -> setInitialScreen(realms, reload, quickPlay))) ci.cancel();
 * }</pre>
 */
public final class HeldFirstScreen extends Screen {

    private HeldFirstScreen() {
        //? if >=1.19.2 {
        super(Component.empty());
        //?} elif >=1.14.3 {
        /*super(new TextComponent(""));
        *///?} else {
        /*super();
        *///?}
    }

    /**
     * Holds {@code open}, the game's own opening of its first screen, until our loading screen hides, and shows this
     * meanwhile. False, holding nothing, when the loading screen is not showing: the caller opens it now.
     */
    public static boolean hold(Runnable open) {
        if (!CgLoadingScreen.holdUntilHidden(open)) return false;
        // 26.2 kept only setScreenAndShow
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(new HeldFirstScreen());
        *///?} else {
        Minecraft.getInstance().setScreen(new HeldFirstScreen());
        //?}
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
