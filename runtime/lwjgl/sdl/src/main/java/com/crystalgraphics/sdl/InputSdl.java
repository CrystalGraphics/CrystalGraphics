package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSdlKeyCodes;

import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * {@link CgInput} over SDL3, for a host SDL windows (Minecraft 26.3 on). SDL's keyboard and mouse state are
 * global, so it needs no window.
 *
 * <pre>{@code
 * CgPlatform.provide(CgInput.HOST, InputSdl.INSTANCE);
 * }</pre>
 *
 * Answers "nothing held" until the host starts SDL's video: listeners ask for modifiers that early.
 */
public final class InputSdl implements CgInput.Host {

    public static final InputSdl INSTANCE = new InputSdl();

    /** SDL numbers its buttons 1 to 5: left, middle, right, X1, X2. */
    private static final int MOUSE_BUTTONS = 5;

    private InputSdl() {
    }

    static boolean videoUp() {
        return SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) != 0;
    }

    @Override
    public boolean isKeyDown(int key) {
        int scancode = CgSdlKeyCodes.toSdl(key);
        if (scancode == CgSdlKeyCodes.SDL_SCANCODE_UNKNOWN || key == CgKeyCodes.KEY_NONE || !videoUp()) return false;
        ByteBuffer state = SDLKeyboard.SDL_GetKeyboardState();
        return state != null && scancode < state.capacity() && state.get(scancode) != 0;
    }

    @Override
    public boolean isMouseDown(int button) {
        int sdl = CgSdlKeyCodes.mouseToSdl(button);
        if (sdl == 0 || !videoUp()) return false;
        // SDL_BUTTON_MASK: a bit a button, from bit 0 for button 1.
        return (SDLMouse.nSDL_GetMouseState(MemoryUtil.NULL, MemoryUtil.NULL) & (1 << (sdl - 1))) != 0;
    }

    @Override
    public int modifiers() {
        if (!videoUp()) return CgModifiers.NONE;
        int mod = SDLKeyboard.SDL_GetModState();
        int held = CgModifiers.NONE;
        if ((mod & SDLKeycode.SDL_KMOD_SHIFT) != 0) held |= CgModifiers.SHIFT;
        if ((mod & SDLKeycode.SDL_KMOD_CTRL) != 0) held |= CgModifiers.CTRL;
        if ((mod & SDLKeycode.SDL_KMOD_ALT) != 0) held |= CgModifiers.ALT;
        if ((mod & SDLKeycode.SDL_KMOD_GUI) != 0) held |= CgModifiers.SUPER;
        return held;
    }

    /** Whether the window with the mouse is in relative mode, as SDL grabs a game's pointer. */
    @Override
    public boolean pointerGrabbed() {
        if (!videoUp()) return false;
        long window = SDLMouse.SDL_GetMouseFocus();
        return window != MemoryUtil.NULL && SDLMouse.SDL_GetWindowRelativeMouseMode(window);
    }

    @Override
    public int mouseButtons() {
        return MOUSE_BUTTONS;
    }

    @Override
    public int key(int toolkitKey) {
        return CgSdlKeyCodes.toCg(toolkitKey);
    }

    @Override
    public int mouseButton(int toolkitButton) {
        return CgSdlKeyCodes.mouseToCg(toolkitButton);
    }
}
