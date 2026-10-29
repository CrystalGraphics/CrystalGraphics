package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.world.HostCamera1710;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where {@code HostCamera1710} adds core's camera offset: the head of {@code orientCamera} and the field of view; and the
 * first-person hands, not drawn while a camera is held. Priority 2000: applied after other mods' mixins here.
 */
@Mixin(value = EntityRenderer.class, priority = 2000)
public abstract class CameraHook {

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void crystalgraphics$orient(float partialTicks, CallbackInfo ci) {
        HostCamera1710.rotate(partialTicks);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void crystalgraphics$hands(float partialTicks, int pass, CallbackInfo ci) {
        if (HostCamera1710.handsHidden()) ci.cancel();
    }

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void crystalgraphics$fov(float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(HostCamera1710.fov(cir.getReturnValue(), partialTicks));
    }
}
