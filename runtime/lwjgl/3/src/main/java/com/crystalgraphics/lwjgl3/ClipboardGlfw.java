package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.input.CgClipboard;

import org.lwjgl.glfw.GLFW;

import java.util.function.LongSupplier;

/**
 * {@link CgClipboard} over GLFW, Minecraft's own clipboard call.
 *
 * <pre>{@code
 * CgPlatform.provide(CgClipboard.HOST, new ClipboardGlfw(() -> window));
 * }</pre>
 */
public final class ClipboardGlfw implements CgClipboard.Host {

    private final LongSupplier window;

    /** {@code window} is asked each call: the window is made after this and may be remade under it. */
    public ClipboardGlfw(LongSupplier window) {
        this.window = window;
    }

    @Override
    public String get() {
        long w = window == null ? 0L : window.getAsLong();
        if (w == 0L) return "";
        String text = GLFW.glfwGetClipboardString(w);   // null where it holds no text
        return text != null ? text : "";
    }

    @Override
    public void set(String text) {
        long w = window == null ? 0L : window.getAsLong();
        if (w != 0L && text != null) GLFW.glfwSetClipboardString(w, text);
    }
}
