package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import net.minecraft.client.renderer.ActiveRenderInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.FloatBuffer;

/**
 * The matrices {@code ActiveRenderInfo.updateRenderInfo} reads back from GL once a frame, after the camera is set
 * up: reading them here costs no second {@code glGetFloat}, which is a driver sync on a threaded driver.
 */
@Mixin(ActiveRenderInfo.class)
public interface ActiveRenderInfoAccessor {

    @Accessor("modelview")
    static FloatBuffer crystalgraphics$modelview() {
        throw new AssertionError();
    }

    @Accessor("projection")
    static FloatBuffer crystalgraphics$projection() {
        throw new AssertionError();
    }
}
