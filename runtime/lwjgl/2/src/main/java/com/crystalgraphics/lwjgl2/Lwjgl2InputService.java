package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.service.CgInputService;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import com.crystalgraphics.platform.input.CgModifiers;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * MC 1.7.10 input and clipboard, over LWJGL2.
 *
 * <p>{@link CgKeyCodes} and {@code CgMouseCodes} are LWJGL2-shaped by construction, so both translation
 * methods are the identity here and the lookup tables a GLFW platform needs do not exist.</p>
 *
 * <p><b>The clipboard is AWT directly</b>, which is what it always was: this used to call
 * {@code GuiScreen.getClipboardString()}, and that method's whole body is the four AWT lines below.
 * Going straight to AWT is what makes this tier 1 — the one Minecraft name in the file was a wrapper
 * around the JDK.
 *
 * <p>Both directions swallow their exceptions on purpose. Clipboard access fails for reasons outside
 * this process — another application owning it, a locked session, a headless JVM — and Minecraft's
 * wrappers swallowed them too, so copy/paste behaves as vanilla's does: nothing happens, and the game
 * does not stop.</p>
 */
public final class Lwjgl2InputService implements CgInputService {

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
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object contents = clipboard.getData(DataFlavor.stringFlavor);
                if (contents != null) return contents.toString();
            }
        } catch (Exception e) {
            // Owned by another application, or no clipboard at all. The contract is "never null".
        }
        return "";
    }

    @Override
    public void setClipboard(String text) {
        if (text == null) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception e) {
            // See the class javadoc: a copy that cannot happen is not worth a crash.
        }
    }
}
