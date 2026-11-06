package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgInputStage;
import net.minecraft.client.Minecraft;

/** {@link CgInputStage.Host}'s answer on 1.13-26.3. */
final class InputHostModern implements CgInputStage.Host {

    @Override
    public boolean pointerGrabbed() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null || mc.mouseHandler.isMouseGrabbed();
    }
}
