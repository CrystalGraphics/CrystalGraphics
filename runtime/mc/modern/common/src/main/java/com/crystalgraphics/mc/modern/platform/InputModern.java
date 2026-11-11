package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgInput;
import net.minecraft.client.Minecraft;

/**
 * {@link CgInput} on 1.13-26.3: the window toolkit's keys and buttons (GLFW, SDL from 26.3), and Minecraft's own word on
 * whether it holds the pointer.
 */
final class InputModern implements CgInput.Host {

    private final CgInput.Host toolkit;

    InputModern(CgInput.Host toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public boolean pointerGrabbed() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null || mc.mouseHandler.isMouseGrabbed();
    }

    @Override public boolean isKeyDown(int key) { return toolkit.isKeyDown(key); }
    @Override public boolean isMouseDown(int button) { return toolkit.isMouseDown(button); }
    @Override public int modifiers() { return toolkit.modifiers(); }
    @Override public int mouseButtons() { return toolkit.mouseButtons(); }
    @Override public int key(int toolkitKey) { return toolkit.key(toolkitKey); }
    @Override public int mouseButton(int toolkitButton) { return toolkit.mouseButton(toolkitButton); }
    @Override public void movePointer(int x, int y) { toolkit.movePointer(x, y); }

    /** Minecraft's own grab, so the game knows the pointer is its camera. */
    @Override
    public void grabPointer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) mc.mouseHandler.grabMouse();
    }
}
