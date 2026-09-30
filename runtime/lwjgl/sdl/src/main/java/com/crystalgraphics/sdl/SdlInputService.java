package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSdlKeyCodes;
import com.crystalgraphics.platform.service.CgInputService;

import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Key state, modifiers and the clipboard over SDL3 — {@code GlfwInputService} for a host SDL windows.
 *
 * <pre>{@code
 * // a host on SDL3 (Minecraft 26.3+) registers this where an older one registers GlfwInputService
 * public CgInputService input() { return new SdlInputService(); }
 * }</pre>
 *
 * <p>No window handle: SDL3's keyboard, mouse and clipboard state are global. Every method answers
 * "nothing held" and an empty clipboard until the host has started SDL's video subsystem, since the
 * UI layer asks for modifier state from listeners that can run that early.</p>
 */
public final class SdlInputService implements CgInputService {

    /** SDL numbers buttons 1..5: left, middle, right, X1, X2. */
    private static final int MOUSE_BUTTON_COUNT = 5;

    private static boolean videoUp() {
        return SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) != 0;
    }

    @Override
    public int getCurrentModifiers() {
        if (!videoUp()) return CgModifiers.NONE;
        int held = SDLKeyboard.SDL_GetModState();
        int modifiers = CgModifiers.NONE;
        if ((held & SDLKeycode.SDL_KMOD_SHIFT) != 0) modifiers |= CgModifiers.SHIFT;
        if ((held & SDLKeycode.SDL_KMOD_CTRL) != 0) modifiers |= CgModifiers.CTRL;
        if ((held & SDLKeycode.SDL_KMOD_ALT) != 0) modifiers |= CgModifiers.ALT;
        if ((held & SDLKeycode.SDL_KMOD_GUI) != 0) modifiers |= CgModifiers.SUPER;
        return modifiers;
    }

    @Override
    public int translateKeyboardCodes(int platformCode) {
        return CgSdlKeyCodes.toCg(platformCode);
    }

    @Override
    public boolean isKeyDown(int localKeyCode) {
        int scancode = CgSdlKeyCodes.toSdl(localKeyCode);
        if (scancode == CgSdlKeyCodes.SDL_SCANCODE_UNKNOWN || localKeyCode == CgKeyCodes.KEY_NONE || !videoUp()) {
            return false;
        }
        ByteBuffer state = SDLKeyboard.SDL_GetKeyboardState();
        return state != null && scancode < state.capacity() && state.get(scancode) != 0;
    }

    @Override
    public int translateMouseCodes(int platformCode) {
        return CgSdlKeyCodes.mouseToCg(platformCode);
    }

    @Override
    public boolean isMouseDown(int localMouseCode) {
        int button = CgSdlKeyCodes.mouseToSdl(localMouseCode);
        if (button == 0 || !videoUp()) return false;
        // SDL_BUTTON_MASK(X): one bit per button, from bit 0 for button 1.
        return (SDLMouse.nSDL_GetMouseState(MemoryUtil.NULL, MemoryUtil.NULL) & (1 << (button - 1))) != 0;
    }

    @Override
    public int howManyMouseButtons() {
        return MOUSE_BUTTON_COUNT;
    }

    @Override
    public String getClipboard() {
        if (!videoUp()) return "";
        // SDL allocates the copy and the caller frees it; the String overload never would.
        long text = SDLClipboard.nSDL_GetClipboardText();
        if (text == MemoryUtil.NULL) return "";
        try {
            return MemoryUtil.memUTF8(text);
        } finally {
            SDLStdinc.nSDL_free(text);
        }
    }

    @Override
    public void setClipboard(String text) {
        if (text == null || !videoUp()) return;
        SDLClipboard.SDL_SetClipboardText(text);
    }
}
