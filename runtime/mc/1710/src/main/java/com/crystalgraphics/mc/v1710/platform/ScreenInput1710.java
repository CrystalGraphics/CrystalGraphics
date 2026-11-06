package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.v1710.mixins.early.impl.client.MixinGuiScreen;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The screen's input on 1.7.10, fired on {@code CgInputStage.SCREEN}. 1.7.10's Forge posts no screen input event, so
 * {@link MixinGuiScreen} replaces {@code GuiScreen.handleInput}'s drain of LWJGL's queue with {@link #drainInto}: the
 * same loop, a stage between each event and the screen's own handler. LWJGL's {@code getEvent*} describe the event the
 * last {@code next()} produced, so a screen can only see one while this still stands on it.
 *
 * <ul>
 *   <li>It runs only while a listener {@link CgInputStage#wanted() wants} input; otherwise the screen's own drain runs
 *       untouched.</li>
 *   <li>{@code handleInput} runs from {@code runTick} at 20 Hz, so {@link #onRenderTick} also drains on the render tick
 *       (FML's bus). The mixin drains too: {@code runTick}'s in-world loop, guarded by {@code allowUserInput}, which
 *       the inventory sets, must find the empty queue vanilla leaves it.</li>
 *   <li>LWJGL measures from the bottom of the display and reports a notch as 120 the other way up; events leave here
 *       from the top, a notch positive rolled down.</li>
 * </ul>
 */
public final class ScreenInput1710 {

    private static final float WHEEL_NOTCH = -1f / 120f;

    /** Drains LWJGL's queue through the stage, handing {@code screen} what no listener took. */
    public static void drainInto(GuiScreen screen) {
        if (screen == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            while (Mouse.next()) {
                if (!mouse(mc)) screen.handleMouseInput();
            }
            while (Keyboard.next()) {
                if (!keyboard()) screen.handleKeyboardInput();
            }
        } catch (RuntimeException | LinkageError failed) {
            LogManager.getLogger("CrystalGraphics").error("screen input failed; the screen keeps its own", failed);
        }
    }

    /** Whether the mixin should take over the drain. */
    public static boolean wanted() {
        return CgInputStage.SCREEN.wanted();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !wanted()) return;
        drainInto(Minecraft.getMinecraft().currentScreen);
    }

    private static boolean mouse(Minecraft mc) {
        int x = Mouse.getEventX();
        int y = mc.displayHeight - Mouse.getEventY();
        int button = Mouse.getEventButton();
        float wheel = Mouse.getEventDWheel() * WHEEL_NOTCH;
        if (wheel != 0f) {
            return CgInputStage.SCREEN.scrolled(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                    CgMouseCodes.NONE, false, wheel, -1L));
        }
        if (button != -1) {
            return CgInputStage.SCREEN.mouseButton(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                    button, Mouse.getEventButtonState(), 0f, System.currentTimeMillis()));
        }
        CgInputStage.SCREEN.mouseMoved(new CgSystemInput.Mouse.Event(x, y, Mouse.getEventDX(),
                -Mouse.getEventDY(), CgMouseCodes.NONE, false, 0f, -1L));
        return false;
    }

    private static boolean keyboard() {
        int key = Keyboard.getEventKey();
        boolean pressed = Keyboard.getEventKeyState();
        long millis = System.currentTimeMillis();
        boolean consumed = CgInputStage.SCREEN.key(new CgSystemInput.Keyboard.Event((char) 0, key, pressed, false, millis));
        char typed = Keyboard.getEventCharacter();
        // Both are asked: a key can be taken while its character is not, and the reverse.
        if (pressed && typed >= ' ' && typed != 127) {
            consumed |= CgInputStage.SCREEN.character(new CgSystemInput.Keyboard.Event(typed, 0, true, false, millis));
        }
        return consumed;
    }
}
