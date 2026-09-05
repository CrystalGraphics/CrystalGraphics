package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.platform.input.CgGlfwKeyCodes;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.service.CgInputService;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * Key state, modifiers and the clipboard on MC 1.20.x.
 *
 * <p>Unlike {@code InputService1710}, translation is a real table: {@link CgKeyCodes} is LWJGL2
 * scancode numbering and this host is GLFW. See {@link CgGlfwKeyCodes}.</p>
 *
 * <p>Every method tolerates being called before the window exists — the UI layer asks for modifier
 * state from listeners that can run early.</p>
 */
public final class InputService1201 implements CgInputService {

    /** GLFW_MOUSE_BUTTON_LAST is 7, so eight buttons. GLFW has no runtime query for this. */
    private static final int MOUSE_BUTTON_COUNT = 8;

    private static long window() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return 0L;
        return mc.getWindow().getWindow();
    }

    private static boolean glfwKeyDown(long window, int glfwKey) {
        return window != 0L && glfwKey != CgGlfwKeyCodes.GLFW_KEY_UNKNOWN
                && InputConstants.isKeyDown(window, glfwKey);
    }

    @Override
    public int getCurrentModifiers() {
        long window = window();
        if (window == 0L) return CgModifiers.NONE;

        int modifiers = CgModifiers.NONE;
        if (glfwKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || glfwKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT))
            modifiers |= CgModifiers.SHIFT;
        if (glfwKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || glfwKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL))
            modifiers |= CgModifiers.CTRL;
        if (glfwKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || glfwKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT))
            modifiers |= CgModifiers.ALT;
        if (glfwKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER) || glfwKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER))
            modifiers |= CgModifiers.SUPER;
        return modifiers;
    }

    @Override
    public int translateKeyboardCodes(int platformCode) {
        return CgGlfwKeyCodes.toCg(platformCode);
    }

    @Override
    public boolean isKeyDown(int localKeyCode) {
        return glfwKeyDown(window(), CgGlfwKeyCodes.toGlfw(localKeyCode));
    }

    /** Identity: GLFW and LWJGL2 both number left/right/middle 0/1/2. */
    @Override
    public int translateMouseCodes(int platformCode) {
        return platformCode;
    }

    @Override
    public boolean isMouseDown(int localMouseCode) {
        long window = window();
        if (window == 0L || localMouseCode < 0 || localMouseCode >= MOUSE_BUTTON_COUNT) return false;
        return GLFW.glfwGetMouseButton(window, localMouseCode) == GLFW.GLFW_PRESS;
    }

    @Override
    public int howManyMouseButtons() {
        return MOUSE_BUTTON_COUNT;
    }

    @Override
    public String getClipboard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null) return "";
        String contents = mc.keyboardHandler.getClipboard();
        return contents != null ? contents : "";
    }

    @Override
    public void setClipboard(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null || text == null) return;
        mc.keyboardHandler.setClipboard(text);
    }
}
