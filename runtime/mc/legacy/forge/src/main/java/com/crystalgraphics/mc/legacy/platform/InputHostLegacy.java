package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.input.CgInputStage;
import org.lwjgl.input.Mouse;

/** {@link CgInputStage.Host} over LWJGL 2: the game holds the pointer while the mouse is grabbed. */
final class InputHostLegacy implements CgInputStage.Host {

    @Override
    public boolean pointerGrabbed() {
        return Mouse.isGrabbed();
    }
}
