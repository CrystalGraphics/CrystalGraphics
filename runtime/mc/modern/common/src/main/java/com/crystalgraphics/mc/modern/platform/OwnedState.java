package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.service.CgScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * What an engine screen of the host's does whatever its class: a plain {@code Screen} ({@link ScreensModern}'s) or a
 * container screen over a server's menu ({@link OwnedContainer}). Its owner hears it come and go and takes Escape; a
 * close lands at the next tick; on 1.21.6 to 26.0 an owner that hides the HUD gets the player's setting back.
 */
final class OwnedState {

    final CgScreens.Owner owner;
    /** Set by a close: taken down at the next tick. */
    boolean closing;
    //? if >=1.21.6 <26.1 {
    /*/^* The player's own HUD setting, kept once an opening: init runs again on every resize. ^/
    private Boolean hudHiddenBefore;
    *///?}

    OwnedState(CgScreens.Owner owner) {
        this.owner = owner;
    }

    /** From the screen's {@code init}: up, and again on every resize. */
    void init(Screen screen, Minecraft minecraft) {
        //? if >=26.3 {
        /*minecraft.onTextInputFocusChange(screen, true);
        *///?}
        //? if >=1.21.6 <26.1 {
        /*if (owner.hidesHud()) {
            if (hudHiddenBefore == null) hudHiddenBefore = CgMinecraftClient.hudHidden(minecraft);
            CgMinecraftClient.setHudHidden(minecraft, true);
        }
        *///?}
        owner.shown();
    }

    /** From the screen's {@code removed}. */
    void removed(Screen screen, Minecraft minecraft) {
        //? if >=26.3 {
        /*minecraft.onTextInputFocusChange(screen, false);
        *///?}
        //? if >=1.21.6 <26.1 {
        /*if (hudHiddenBefore != null) CgMinecraftClient.setHudHidden(minecraft, hudHiddenBefore);
        hudHiddenBefore = null;
        *///?}
        owner.hidden();
    }

    /** From the screen's {@code tick}: whether a close was asked for, to be done now. */
    boolean takeClose() {
        if (!closing) return false;
        closing = false;
        return true;
    }

    /** A key no input listener took: Escape is its owner's. */
    boolean escape(int hostKey) {
        if (CgInput.key(hostKey) != CgKeyCodes.KEY_ESCAPE) return false;
        owner.escape();
        return true;
    }
}
