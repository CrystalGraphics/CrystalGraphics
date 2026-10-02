package com.crystalgraphics.mc.modern.fabric.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <26.1 {
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

// An FOV kick on Fabric, which has no FOV event: GameRenderer.getFov's answer scaled by the offset core gave. A double
// to 1.21.1, a float after. No refmap, so the name in both namespaces. Empty on 26.x until its FOV path is settled.
@Mixin(value = GameRenderer.class, remap = false)
public abstract class FovHook {

    //? if >=1.21.3 <26.1 {
    /*@Inject(method = {"getFov", "method_3196"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$fov(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(HostCameraModern.fov(cir.getReturnValue()));
    }
    *///?} elif <1.21.3 {
    @Inject(method = {"getFov", "method_3196"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$fov(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(HostCameraModern.fov(cir.getReturnValue()));
    }
    //?}
}
