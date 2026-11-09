package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.input.CgClipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

/**
 * {@link CgClipboard} through AWT, which is all {@code GuiScreen.getClipboardString} ever was.
 *
 * <pre>{@code
 * CgPlatform.provide(CgClipboard.HOST, ClipboardLwjgl2.INSTANCE);
 * }</pre>
 *
 * A clipboard another application holds, or none at all, reads as empty and drops a write, as vanilla's does.
 */
public final class ClipboardLwjgl2 implements CgClipboard.Host {

    public static final ClipboardLwjgl2 INSTANCE = new ClipboardLwjgl2();

    private ClipboardLwjgl2() {
    }

    @Override
    public String get() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object text = clipboard.getData(DataFlavor.stringFlavor);
                if (text != null) return text.toString();
            }
        } catch (Exception busyOrHeadless) {
            // Another application's, or no clipboard: empty, as vanilla's
        }
        return "";
    }

    @Override
    public void set(String text) {
        if (text == null) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception busyOrHeadless) {
            // As vanilla: a copy that cannot happen does nothing
        }
    }
}
