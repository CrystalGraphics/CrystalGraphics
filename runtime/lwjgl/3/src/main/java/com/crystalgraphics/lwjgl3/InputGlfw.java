package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.input.CgGlfwKeyCodes;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgModifiers;

import org.lwjgl.glfw.GLFW;

import java.util.function.LongSupplier;

/**
 * {@link CgInput} over GLFW: its keys through {@link CgGlfwKeyCodes}, since the engine's are LWJGL 2's numbers.
 *
 * <pre>{@code
 * CgPlatform.provide(CgInput.HOST, new InputGlfw(() -> window));
 * }</pre>
 *
 * Answers "nothing held" until the window exists: listeners ask for modifiers that early.
 */
public final class InputGlfw implements CgInput.Host {

    /** {@code GLFW_MOUSE_BUTTON_LAST} is 7; GLFW has no query for the count. */
    private static final int MOUSE_BUTTONS = 8;

    private final LongSupplier window;

    /** {@code window} is asked each call: the window is made after this and may be remade under it. */
    public InputGlfw(LongSupplier window) {
        this.window = window;
    }

    private long window() {
        return window == null ? 0L : window.getAsLong();
    }

    private static boolean down(long window, int glfwKey) {
        return window != 0L && glfwKey != CgGlfwKeyCodes.GLFW_KEY_UNKNOWN && GLFW.glfwGetKey(window, glfwKey) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean isKeyDown(int key) {
        return down(window(), CgGlfwKeyCodes.toGlfw(key));
    }

    @Override
    public boolean isMouseDown(int button) {
        long window = window();
        return window != 0L && button >= 0 && button < MOUSE_BUTTONS && GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    @Override
    public int modifiers() {
        long w = window();
        if (w == 0L) return CgModifiers.NONE;
        int held = CgModifiers.NONE;
        if (down(w, GLFW.GLFW_KEY_LEFT_SHIFT) || down(w, GLFW.GLFW_KEY_RIGHT_SHIFT)) held |= CgModifiers.SHIFT;
        if (down(w, GLFW.GLFW_KEY_LEFT_CONTROL) || down(w, GLFW.GLFW_KEY_RIGHT_CONTROL)) held |= CgModifiers.CTRL;
        if (down(w, GLFW.GLFW_KEY_LEFT_ALT) || down(w, GLFW.GLFW_KEY_RIGHT_ALT)) held |= CgModifiers.ALT;
        if (down(w, GLFW.GLFW_KEY_LEFT_SUPER) || down(w, GLFW.GLFW_KEY_RIGHT_SUPER)) held |= CgModifiers.SUPER;
        return held;
    }

    /** Whether the window's cursor is disabled, as Minecraft and the harness grab it. */
    @Override
    public boolean pointerGrabbed() {
        long window = window();
        return window != 0L && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED;
    }

    @Override
    public void grabPointer() {
        long window = window();
        if (window != 0L) GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    @Override
    public void movePointer(int x, int y) {
        long window = window();
        if (window != 0L) GLFW.glfwSetCursorPos(window, x, y);
    }

    @Override
    public int mouseButtons() {
        return MOUSE_BUTTONS;
    }

    @Override
    public int key(int toolkitKey) {
        return CgGlfwKeyCodes.toCg(toolkitKey);
    }

    /** GLFW numbers left, right and middle 0, 1 and 2, as the engine does. */
    @Override
    public int mouseButton(int toolkitButton) {
        return toolkitButton;
    }
}
