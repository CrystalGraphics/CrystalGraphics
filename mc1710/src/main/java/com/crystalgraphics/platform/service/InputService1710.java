package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * MC 1.7.10 input and clipboard, over LWJGL2.
 *
 * <p>{@link CgKeyCodes} and {@code CgMouseCodes} are LWJGL2-shaped by construction, so both translation
 * methods are the identity here and the lookup tables a GLFW platform needs do not exist.</p>
 *
 * <p>The clipboard goes through {@link GuiScreen#getClipboardString()} /
 * {@link GuiScreen#setClipboardString(String)} rather than AWT directly. Both are thin AWT wrappers, but
 * Minecraft's already swallow the exceptions that clipboard access throws for reasons outside this process
 * — another application owning it, a locked session — and reusing them keeps this mod's copy/paste
 * behaving identically to vanilla's.</p>
 */
public final class InputService1710 implements CgInputService {

    @Override
    public int getCurrentModifiers() {
        int mods = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            mods |= CgModifiers.SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
            mods |= CgModifiers.CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
            mods |= CgModifiers.ALT;
        return mods;
    }

    @Override
    public int translateKeyboardCodes(int platformCode) {
        return platformCode;
    }

    /** LWJGL2 button ids are already {@code CgMouseCodes} values. */
    @Override
    public int translateMouseCodes(int platformCode) {
        return platformCode;
    }

    @Override
    public boolean isKeyDown(int localKeyCode) {
        return Keyboard.isKeyDown(localKeyCode);
    }

    @Override
    public boolean isMouseDown(int localMouseCode) {
        return Mouse.isButtonDown(localMouseCode);
    }

    @Override
    public int howManyMouseButtons() {
        return Mouse.getButtonCount();
    }

    @Override
    public String getClipboard() {
        String contents = GuiScreen.getClipboardString();
        // GuiScreen returns "" on failure, but be explicit — the contract here is "never null".
        return contents != null ? contents : "";
    }

    @Override
    public void setClipboard(String text) {
        GuiScreen.setClipboardString(text);
    }
}
