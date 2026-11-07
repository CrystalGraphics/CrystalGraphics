package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import net.minecraft.client.Minecraft;

/**
 * What a loader's input hook calls: it builds the event in CrystalGraphics' vocabulary (raw surface pixels from the top
 * left, {@code CgKeyCodes}, a wheel notch positive rolled down) and fires {@link CgInputStage#SCREEN}. A loader decides
 * which hook reaches it and cancels the host's own handling when the answer is true; nothing else is its business.
 *
 * <pre>{@code
 * // A loader's screen mouse-press event
 * if (ScreenInputModern.mouseButton(event.getButton(), true)) event.setCanceled(true);
 * }</pre>
 *
 * <p>Numbers are the host's own, GLFW's before 26.3 and SDL's from it; they are translated here. The pointer is read
 * from {@code MouseHandler}, never from the screen callback, whose doubles are GUI-scaled.</p>
 */
public final class ScreenInputModern {

    private static int lastX;
    private static int lastY;

    private ScreenInputModern() {}

    /** A button went down or up. @return whether a listener consumed it, and the screen must not see it */
    public static boolean mouseButton(int hostButton, boolean pressed) {
        if (noScreen()) return false;
        int x = rawX();
        int y = rawY();
        lastX = x;
        lastY = y;
        return CgInputStage.SCREEN.mouseButton(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                CgPlatform.input().translateMouseCodes(hostButton), pressed, 0f, System.currentTimeMillis()));
    }

    /** The wheel turned; {@code platformDelta} is the host's, positive up. */
    public static boolean scrolled(double platformDelta) {
        if (noScreen()) return false;
        return CgInputStage.SCREEN.scrolled(new CgSystemInput.Mouse.Event(rawX(), rawY(), 0, 0,
                CgMouseCodes.NONE, false, (float) -platformDelta, -1L));
    }

    /** A key with no character, in the host's numbering. */
    public static boolean key(int hostKey, boolean pressed) {
        if (noScreen()) return false;
        return CgInputStage.SCREEN.key(new CgSystemInput.Keyboard.Event((char) 0,
                CgPlatform.input().translateKeyboardCodes(hostKey), pressed, false, System.currentTimeMillis()));
    }

    /** A character with no key. */
    public static boolean character(int codepoint) {
        if (noScreen()) return false;
        return CgInputStage.SCREEN.character(new CgSystemInput.Keyboard.Event((char) codepoint, CgKeyCodes.KEY_NONE,
                true, false, System.currentTimeMillis()));
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public static boolean composition(String text, int caret) {
        if (noScreen()) return false;
        return CgInputStage.SCREEN.composition(text, caret);
    }

    /** No screen in front: the stage is the screen's, so nothing fires. Fabric's window callbacks run in play too. */
    private static boolean noScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || CgMinecraftClient.screen(mc) == null;
    }

    /** The pointer where it is, once a frame while a screen is up: 1.20 posts no move event to a foreign screen. */
    static void frame() {
        if (!CgInputStage.SCREEN.listening()) return;
        int x = rawX();
        int y = rawY();
        CgInputStage.SCREEN.mouseMoved(new CgSystemInput.Mouse.Event(x, y, x - lastX, y - lastY,
                CgMouseCodes.NONE, false, 0f, -1L));
        lastX = x;
        lastY = y;
    }

    private static int rawX() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.xpos();
    }

    private static int rawY() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.ypos();
    }
}
