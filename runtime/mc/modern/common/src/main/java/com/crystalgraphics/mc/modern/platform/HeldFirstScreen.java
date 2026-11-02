package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.loading.CgLoadingScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//? if <1.20.1 {
/*import net.minecraft.client.gui.screens.TitleScreen;
import javax.annotation.Nullable;
*///?}
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
 * // 1.20.1-26.3: a node mixin where the game opens its first screen, once its load finished
 * if (HeldFirstScreen.hold(() -> setInitialScreen(realms, reload, quickPlay))) ci.cancel();
 *
 * // 1.13.2-1.19.4: the first screen is set in Minecraft's constructor, so it is swapped as it opens
 * Screen instead = HeldFirstScreen.instead(screen);   // a Forge screen-open event, or Fabric's setScreen mixin
 * }</pre>
 */
public final class HeldFirstScreen extends Screen {

    //? if <1.20.1 {
    /*private static boolean firstSeen;
    *///?}

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
        show(new HeldFirstScreen());
        return true;
    }

    //? if <1.20.1 {
    /*/^*
     * The screen to open instead of {@code screen}, or null to open it as asked: this, for the game's first title
     * screen while our loading screen shows, which then opens once ours hides. Only the title screen: a connect
     * screen (a server on the command line) connects as it is made. A screen something opened meanwhile (Forge's
     * loading errors) stays.
     ^/
    @Nullable
    public static Screen instead(@Nullable Screen screen) {
        if (firstSeen || screen == null) return null;
        firstSeen = true;
        if (!(screen instanceof TitleScreen)) return null;
        boolean held = CgLoadingScreen.holdUntilHidden(() -> {
            if (Minecraft.getInstance().screen instanceof HeldFirstScreen) show(screen);
        });
        return held ? new HeldFirstScreen() : null;
    }
    *///?}

    /**
     * Whether our screen may be drawn over the frame: not under Mojang's loading overlay, which 1.14.3-1.19.4 put up
     * after the first screen was held.
     */
    static boolean overHostLoading() {
        //? if >=1.14.3 <1.20.1 {
        /*return Minecraft.getInstance().getOverlay() != null;
        *///?} else {
        return false;
        //?}
    }

    private static void show(Screen screen) {
        // 26.2 kept only setScreenAndShow
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(screen);
        *///?} else {
        Minecraft.getInstance().setScreen(screen);
        //?}
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
