package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * The screen's input on Forge 1.8-1.12.2, fired on {@code CgInputStage.SCREEN}. Forge posts
 * {@code GuiScreenEvent.MouseInputEvent.Pre} and {@code KeyboardInputEvent.Pre} once per queued event from inside
 * {@code GuiScreen.handleInput}, standing on it, and skips the screen's own handler when one is cancelled.
 *
 * <p>Register on both of Forge's buses (the render tick is FML's; on 1.12 they are one):
 * {@code MinecraftForge.EVENT_BUS.register(handler)} and {@code FMLCommonHandler.instance().bus().register(handler)}.
 * {@code handleInput} runs from {@code runTick} at 20 Hz, so while a listener wants input it is also called on the
 * render tick, and the tick's own call finds an empty queue.</p>
 *
 * <p>LWJGL 2 measures from the bottom of the display and reports a wheel notch as 120 the other way up; events leave
 * here from the top, a notch positive rolled down.</p>
 */
public final class ScreenInputLegacy {

    private static final float WHEEL_NOTCH = -1f / 120f;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !CgInputStage.SCREEN.wanted()) return;
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen == null) return;
        try {
            screen.handleInput();
        } catch (IOException | RuntimeException | LinkageError failed) {
            LogManager.getLogger("CrystalGraphics").error("screen input drain failed; the screen keeps its own", failed);
        }
    }

    @SubscribeEvent
    public void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!CgInputStage.SCREEN.listening()) return;
        int x = Mouse.getEventX();
        int y = Minecraft.getMinecraft().displayHeight - Mouse.getEventY();
        int button = Mouse.getEventButton();
        float wheel = Mouse.getEventDWheel() * WHEEL_NOTCH;
        boolean consumed;
        if (wheel != 0f) {
            consumed = CgInputStage.SCREEN.scrolled(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                    CgMouseCodes.NONE, false, wheel, -1L));
        } else if (button != -1) {
            consumed = CgInputStage.SCREEN.mouseButton(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                    button, Mouse.getEventButtonState(), 0f, System.currentTimeMillis()));
        } else {
            CgInputStage.SCREEN.mouseMoved(new CgSystemInput.Mouse.Event(x, y, Mouse.getEventDX(),
                    -Mouse.getEventDY(), CgMouseCodes.NONE, false, 0f, -1L));
            consumed = false;
        }
        if (consumed) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!CgInputStage.SCREEN.listening()) return;
        int key = Keyboard.getEventKey();
        boolean pressed = Keyboard.getEventKeyState();
        long millis = System.currentTimeMillis();
        boolean consumed = CgInputStage.SCREEN.key(new CgSystemInput.Keyboard.Event((char) 0, key, pressed, false, millis));
        char typed = Keyboard.getEventCharacter();
        // Both are asked: a key can be taken while its character is not, and the reverse.
        if (pressed && typed >= ' ' && typed != 127) {
            consumed |= CgInputStage.SCREEN.character(new CgSystemInput.Keyboard.Event(typed, 0, true, false, millis));
        }
        if (consumed) event.setCanceled(true);
    }
}
