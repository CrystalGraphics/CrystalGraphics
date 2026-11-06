package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.platform.input.CgInputStage;
import org.lwjgl.input.Mouse;

/** {@link CgInputStage.Host} over LWJGL 2: the game holds the pointer while the mouse is grabbed. */
final class InputHost1710 implements CgInputStage.Host {

    @Override
    public boolean pointerGrabbed() {
        return Mouse.isGrabbed();
    }
}
