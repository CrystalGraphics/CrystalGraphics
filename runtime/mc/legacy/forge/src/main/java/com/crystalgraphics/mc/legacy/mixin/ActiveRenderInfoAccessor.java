package com.crystalgraphics.mc.legacy.mixin;

import net.minecraft.client.renderer.ActiveRenderInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.FloatBuffer;

/**
 * The matrices {@code ActiveRenderInfo.updateRenderInfo} reads back from GL at the start of every render pass, after
 * the camera is set up: reading them here costs no second {@code glGetFloat}, which is a driver sync on a threaded
 * driver. {@code MODELVIEW} and {@code PROJECTION} at their SRG names, the same on every plateau.
 */
@Mixin(value = ActiveRenderInfo.class, remap = false)
public interface ActiveRenderInfoAccessor {

    @Accessor(value = "field_178812_b", remap = false)
    static FloatBuffer crystalgraphics$modelview() {
        throw new AssertionError();
    }

    @Accessor(value = "field_178813_c", remap = false)
    static FloatBuffer crystalgraphics$projection() {
        throw new AssertionError();
    }
}
