package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=26.1 {
/*import net.minecraft.client.Camera;
*///?} else {
import net.minecraft.client.renderer.GameRenderer;
//?}

// An FOV kick on Fabric, which has no FOV event: the level's field of view scaled by the offset core gave. From
// GameRenderer.getFov to 1.21.11 (a double to 1.21.1, a float after), from Camera.calculateFov on 26.x. No refmap, so
// the name in both namespaces where there are two.
//? if >=26.1 {
/*@Mixin(value = Camera.class, remap = false)
*///?} else {
@Mixin(value = GameRenderer.class, remap = false)
//?}
public abstract class FovHook {

    //? if >=26.1 {
    /*@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$fov(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(HostCameraModern.fov(cir.getReturnValue()));
    }
    *///?} elif >=1.21.3 {
    /*@Inject(method = {"getFov", "method_3196"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$fov(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(HostCameraModern.fov(cir.getReturnValue()));
    }
    *///?} else {
    @Inject(method = {"getFov", "method_3196"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$fov(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(HostCameraModern.fov(cir.getReturnValue()));
    }
    //?}
}
