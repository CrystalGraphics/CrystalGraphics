package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.modern.platform.ScreenInputModern;
//? if >=1.18 {
import net.minecraftforge.client.event.ScreenEvent;
//?} else {
/*import net.minecraftforge.client.event.GuiScreenEvent;
*///?}
//? if <1.21.6 {
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
//?}

/**
 * The screen's input on Forge, fired on {@code CgInputStage.SCREEN}: each event is built by {@link ScreenInputModern}
 * and cancelled when a listener took it. Client only, and a class of its own so a dedicated server never links the
 * event types.
 *
 * <p>Forge 25-37 name the events {@code GuiScreenEvent.*Event}, 38-40 {@code ScreenEvent.*Event}, 41+ {@code
 * ScreenEvent.*}; from 1.21.6 (EventBus 7) each carries its own {@code BUS} and a cancelling listener is a predicate;
 * Forge 59 (1.21.9) hands Minecraft's own event object through {@code getInfo()}.</p>
 */
final class ScreenInputForge {

    private ScreenInputForge() {}

    //? if >=1.21.6 {
    /*static void register() {
        ScreenEvent.MouseButtonPressed.Pre.BUS.addListener(ScreenInputForge::onMousePressed);
        ScreenEvent.MouseButtonReleased.Pre.BUS.addListener(ScreenInputForge::onMouseReleased);
        ScreenEvent.MouseScrolled.Pre.BUS.addListener(ScreenInputForge::onMouseScrolled);
        ScreenEvent.KeyPressed.Pre.BUS.addListener(ScreenInputForge::onKeyPressed);
        ScreenEvent.KeyReleased.Pre.BUS.addListener(ScreenInputForge::onKeyReleased);
        ScreenEvent.CharacterTyped.Pre.BUS.addListener(ScreenInputForge::onCharTyped);
    }
    *///?} elif >=1.19 {
    static void register() {
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonPressed.Pre.class,
                e -> { if (onMousePressed(e)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonReleased.Pre.class,
                e -> { if (onMouseReleased(e)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseScrolled.Pre.class,
                e -> { if (onMouseScrolled(e)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyPressed.Pre.class,
                e -> { if (onKeyPressed(e)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyReleased.Pre.class,
                e -> { if (onKeyReleased(e)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.CharacterTyped.Pre.class,
                e -> { if (onCharTyped(e)) e.setCanceled(true); });
    }
    //?} elif >=1.18 {
    /*static void register() {
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseClickedEvent.Pre.class,
                e -> { if (ScreenInputModern.mouseButton(e.getButton(), true)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseReleasedEvent.Pre.class,
                e -> { if (ScreenInputModern.mouseButton(e.getButton(), false)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseScrollEvent.Pre.class,
                e -> { if (ScreenInputModern.scrolled(e.getScrollDelta())) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyboardKeyPressedEvent.Pre.class,
                e -> { if (ScreenInputModern.key(e.getKeyCode(), true)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyboardKeyReleasedEvent.Pre.class,
                e -> { if (ScreenInputModern.key(e.getKeyCode(), false)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyboardCharTypedEvent.Pre.class,
                e -> { if (ScreenInputModern.character(e.getCodePoint())) e.setCanceled(true); });
    }
    *///?} else {
    /*static void register() {
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.MouseClickedEvent.Pre.class,
                e -> { if (ScreenInputModern.mouseButton(e.getButton(), true)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.MouseReleasedEvent.Pre.class,
                e -> { if (ScreenInputModern.mouseButton(e.getButton(), false)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.MouseScrollEvent.Pre.class,
                e -> { if (ScreenInputModern.scrolled(e.getScrollDelta())) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.KeyboardKeyPressedEvent.Pre.class,
                e -> { if (ScreenInputModern.key(e.getKeyCode(), true)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.KeyboardKeyReleasedEvent.Pre.class,
                e -> { if (ScreenInputModern.key(e.getKeyCode(), false)) e.setCanceled(true); });
        forgeBus.addListener(EventPriority.NORMAL, false, GuiScreenEvent.KeyboardCharTypedEvent.Pre.class,
                e -> { if (ScreenInputModern.character(e.getCodePoint())) e.setCanceled(true); });
    }
    *///?}

    //? if >=1.21.9 {
    /*private static boolean onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        return ScreenInputModern.mouseButton(event.getInfo().button(), true);
    }

    private static boolean onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        return ScreenInputModern.mouseButton(event.getButton(), false);
    }

    private static boolean onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        return ScreenInputModern.key(event.getInfo().key(), true);
    }

    private static boolean onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        return ScreenInputModern.key(event.getInfo().key(), false);
    }

    private static boolean onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        return ScreenInputModern.character(event.getInfo().codepoint());
    }
    *///?} elif >=1.19 {
    private static boolean onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        return ScreenInputModern.mouseButton(event.getButton(), true);
    }

    private static boolean onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        return ScreenInputModern.mouseButton(event.getButton(), false);
    }

    private static boolean onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        return ScreenInputModern.key(event.getKeyCode(), true);
    }

    private static boolean onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        return ScreenInputModern.key(event.getKeyCode(), false);
    }

    private static boolean onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        return ScreenInputModern.character(event.getCodePoint());
    }
    //?}

    //? if >=1.20.2 {
    /*private static boolean onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        return ScreenInputModern.scrolled(event.getDeltaY());
    }
    *///?} elif >=1.19 {
    private static boolean onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        return ScreenInputModern.scrolled(event.getScrollDelta());
    }
    //?}
}
