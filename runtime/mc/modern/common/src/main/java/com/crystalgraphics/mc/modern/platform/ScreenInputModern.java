package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
//? if >=26.3 {
/*import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
*///?} else {
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
//?}
//? if >=26.1.2 <26.3 {
/*import org.lwjgl.glfw.GLFWCharCallback;
*///?} elif <26.1.2 {
import org.lwjgl.glfw.GLFWCharModsCallback;
//?}

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
    /** A key being given back: the stage's own hooks step aside, so it reaches everything else. */
    private static boolean givingBack;

    private ScreenInputModern() {}

    /** A button went down or up. @return whether a listener consumed it, and the screen must not see it */
    public static boolean mouseButton(int hostButton, boolean pressed) {
        if (noScreen()) return false;
        int x = rawX();
        int y = rawY();
        lastX = x;
        lastY = y;
        return CgInputStage.SCREEN.mouseButton(new CgSystemInput.Mouse.Event(x, y, 0, 0,
                CgInput.mouseButton(hostButton), pressed, 0f, System.currentTimeMillis()));
    }

    /** The wheel turned; {@code platformDelta} is the host's, positive up. */
    public static boolean scrolled(double platformDelta) {
        if (noScreen() || givingBack) return false;
        return CgInputStage.SCREEN.scrolled(new CgSystemInput.Mouse.Event(rawX(), rawY(), 0, 0,
                CgMouseCodes.NONE, false, (float) -platformDelta, -1L));
    }

    /** A key with no character, in the host's numbering. */
    public static boolean key(int hostKey, boolean pressed) {
        if (noScreen() || givingBack) return false;
        return CgInputStage.SCREEN.key(new CgSystemInput.Keyboard.Event((char) 0,
                CgInput.key(hostKey), pressed, false, System.currentTimeMillis()));
    }

    /** A character with no key. */
    public static boolean character(int codepoint) {
        if (noScreen() || givingBack) return false;
        return CgInputStage.SCREEN.character(new CgSystemInput.Keyboard.Event((char) codepoint, CgKeyCodes.KEY_NONE,
                true, false, System.currentTimeMillis()));
    }

    /**
     * {@code key} delivered again through the window's own input, as the system delivers a key, with the stage stepping
     * aside: Minecraft, the loader's screen events and every mod listening to them see it as though nothing had taken
     * it. How a key the document left goes back, as Chromium returns an unhandled key to the browser's own handling.
     * Render thread.
     */
    public static void giveBack(CgSystemInput.Keyboard.Event key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        long window = Windows.handle(mc);
        givingBack = true;
        try {
            //? if >=26.3 {
            /*if (key.character() != 0) {
                mc.keyboardHandler.charTyped(window, new CharacterEvent(key.character()));
                return;
            }
            mc.keyboardHandler.keyPress(window, key.pressed() ? InputConstants.PRESS : InputConstants.RELEASE,
                    new KeyEvent(HostKeys.key(key.key()), 0, 0));
            *///?} else {
            if (key.character() != 0) {
                //? if >=26.1.2 {
                /*GLFWCharCallback chars = GLFW.glfwSetCharCallback(window, null);
                GLFW.glfwSetCharCallback(window, chars);
                if (chars != null) chars.invoke(window, key.character());
                *///?} else {
                GLFWCharModsCallback chars = GLFW.glfwSetCharModsCallback(window, null);
                GLFW.glfwSetCharModsCallback(window, chars);
                if (chars != null) chars.invoke(window, key.character(), modifiers(window));
                //?}
                return;
            }
            GLFWKeyCallback keys = GLFW.glfwSetKeyCallback(window, null);
            GLFW.glfwSetKeyCallback(window, keys);
            if (keys == null) return;
            int code = HostKeys.key(key.key());
            int action = !key.pressed() ? GLFW.GLFW_RELEASE : key.repeat() ? GLFW.GLFW_REPEAT : GLFW.GLFW_PRESS;
            keys.invoke(window, code, GLFW.glfwGetKeyScancode(code), action, modifiers(window));
            //?}
        } finally {
            givingBack = false;
        }
    }

    /** {@link #giveBack} for a wheel of {@code notches}, positive down, where the pointer is. Render thread. */
    public static void giveBackWheel(float notches) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        long window = Windows.handle(mc);
        givingBack = true;
        try {
            //? if >=26.3 {
            /*mc.mouseHandler.onScroll(window, 0, -notches);
            *///?} else {
            GLFWScrollCallback scroll = GLFW.glfwSetScrollCallback(window, null);
            GLFW.glfwSetScrollCallback(window, scroll);
            if (scroll != null) scroll.invoke(window, 0, -notches);
            //?}
        } finally {
            givingBack = false;
        }
    }

    //? if <26.3 {
    // The modifiers held now, as GLFW reports them with a key
    private static int modifiers(long window) {
        int mods = 0;
        if (down(window, GLFW.GLFW_KEY_LEFT_SHIFT) || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT)) mods |= GLFW.GLFW_MOD_SHIFT;
        if (down(window, GLFW.GLFW_KEY_LEFT_CONTROL) || down(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) mods |= GLFW.GLFW_MOD_CONTROL;
        if (down(window, GLFW.GLFW_KEY_LEFT_ALT) || down(window, GLFW.GLFW_KEY_RIGHT_ALT)) mods |= GLFW.GLFW_MOD_ALT;
        if (down(window, GLFW.GLFW_KEY_LEFT_SUPER) || down(window, GLFW.GLFW_KEY_RIGHT_SUPER)) mods |= GLFW.GLFW_MOD_SUPER;
        return mods;
    }

    private static boolean down(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
    //?}

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
        return (int) surfaceX();
    }

    private static int rawY() {
        return (int) surfaceY();
    }

    /**
     * MouseHandler's pointer in surface pixels. It counts in the window's screen units, which can differ from the
     * framebuffer's by a pixel (26.2's Vulkan window: 1441 over 1440); the game scales it by the screen size, so this
     * does too, or a pointer on a slot's edge hovers it here and not there.
     */
    static double surfaceX() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null) return 0;
        Window w = Windows.of(mc);
        return mc.mouseHandler.xpos() * w.getWidth() / Math.max(1, w.getScreenWidth());
    }

    static double surfaceY() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null) return 0;
        Window w = Windows.of(mc);
        return mc.mouseHandler.ypos() * w.getHeight() / Math.max(1, w.getScreenHeight());
    }
}
