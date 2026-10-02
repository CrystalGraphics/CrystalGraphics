package com.crystalgraphics.mc.modern.fabric.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.11 {
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

// A camera shake on Fabric, which has no camera event: the angles Camera.setup just set, turned by the offset core gave
// (HostCameraModern). No refmap, so each name in both namespaces. 1.21.11 dropped the angle getters and 26.x setup:
// no shake there yet, and the mixin is empty.
@Mixin(value = Camera.class, remap = false)
public abstract class CameraHook {

    //? if <1.21.11 {
    @Shadow(aliases = "method_19325")
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = {"setup", "method_19321"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$shake(CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        setRotation(HostCameraModern.yaw(camera.getYRot()), HostCameraModern.pitch(camera.getXRot()));
    }
    //?}
}
