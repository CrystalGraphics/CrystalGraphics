package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.world.HostCameraLegacy;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where {@code HostCameraLegacy} adds the camera offset's translation on Forge 1.8–1.12.2: the tail of
 * {@code orientCamera} ({@code func_78467_g}), after Forge's camera event and the view entity's turn. SRG names without
 * a refmap, as {@code CgRenderHook}. Priority 2000: applied after other mods' mixins here.
 */
@Mixin(value = EntityRenderer.class, priority = 2000, remap = false)
public abstract class CameraHook {

    @Inject(method = "func_78467_g", remap = false, require = 1, at = @At("TAIL"))
    private void crystalgraphics$translate(float partialTicks, CallbackInfo ci) {
        HostCameraLegacy.translate();
    }
}
