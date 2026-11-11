package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgModifiers;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * {@link CgInput} over LWJGL 2, the whole of it on 1.7.10 and legacy Forge: the game holds the pointer while LWJGL's
 * mouse is grabbed. The engine's codes are LWJGL 2's numbers, so both translations are the identity.
 *
 * <pre>{@code
 * CgPlatform.provide(CgInput.HOST, InputLwjgl2.INSTANCE);
 * }</pre>
 */
public final class InputLwjgl2 implements CgInput.Host {

    public static final InputLwjgl2 INSTANCE = new InputLwjgl2();

    private InputLwjgl2() {
    }

    @Override
    public boolean isKeyDown(int key) {
        return Keyboard.isKeyDown(key);
    }

    @Override
    public boolean isMouseDown(int button) {
        return Mouse.isButtonDown(button);
    }

    @Override
    public int modifiers() {
        int held = CgModifiers.NONE;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) held |= CgModifiers.SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) held |= CgModifiers.CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) held |= CgModifiers.ALT;
        return held;
    }

    @Override
    public boolean pointerGrabbed() {
        return Mouse.isGrabbed();
    }

    @Override
    public void grabPointer() {
        Mouse.setGrabbed(true);
    }

    /** LWJGL 2's pointer origin is the bottom left. */
    @Override
    public void movePointer(int x, int y) {
        Mouse.setCursorPosition(x, Display.getHeight() - y);
    }

    @Override
    public int mouseButtons() {
        return Mouse.getButtonCount();
    }

    @Override
    public int key(int toolkitKey) {
        return toolkitKey;
    }

    @Override
    public int mouseButton(int toolkitButton) {
        return toolkitButton;
    }
}
