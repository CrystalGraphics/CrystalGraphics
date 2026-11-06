package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.modern.platform.ScreenInputModern;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The screen's input on NeoForge, fired on {@code CgInputStage.SCREEN}: each event is built by {@link ScreenInputModern}
 * and cancelled when a listener took it. Client only, and a class of its own so a dedicated server never links the
 * event types.
 *
 * <p>The key is Minecraft's numbering: from 26.3 {@code getKey()}, the scancode, since {@code getKeyCode()} is SDL's
 * keycode. A character is an {@code int} from NeoForge 21.9 and a {@code char} before it.</p>
 */
final class ScreenInputNeoForge {

    private ScreenInputNeoForge() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onMousePressed);
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onMouseReleased);
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onMouseScrolled);
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onKeyReleased);
        NeoForge.EVENT_BUS.addListener(ScreenInputNeoForge::onCharTyped);
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (ScreenInputModern.mouseButton(event.getButton(), true)) event.setCanceled(true);
    }

    private static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (ScreenInputModern.mouseButton(event.getButton(), false)) event.setCanceled(true);
    }

    private static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (ScreenInputModern.scrolled(event.getScrollDeltaY())) event.setCanceled(true);
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        //? if >=26.3 {
        /*int key = event.getKey();
        *///?} else {
        int key = event.getKeyCode();
        //?}
        if (ScreenInputModern.key(key, true)) event.setCanceled(true);
    }

    private static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        //? if >=26.3 {
        /*int key = event.getKey();
        *///?} else {
        int key = event.getKeyCode();
        //?}
        if (ScreenInputModern.key(key, false)) event.setCanceled(true);
    }

    private static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (ScreenInputModern.character(event.getCodePoint())) event.setCanceled(true);
    }
}
