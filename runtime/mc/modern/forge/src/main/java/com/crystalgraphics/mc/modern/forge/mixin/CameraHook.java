package com.crystalgraphics.mc.modern.forge.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 <26.3 {
/*import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

// A camera shake on Forge 26.1.1 to 26.2, whose camera-angle event comes after renderLevel has taken the view: the
// camera's angles turned by the offset core gave (HostCameraModern) just before the frame's render state is taken from
// it, roll about the camera's own axis. The event that follows meets angles already turned and adds nothing. Empty on
// the other nodes that pin this plugin.
@Mixin(value = Camera.class, remap = false)
public abstract class CameraHook {

    //? if >=26.1 <26.3 {
    /*@Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 1)
    private void crystalgraphics$shake(CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        setRotation(HostCameraModern.yaw(camera.yRot()), HostCameraModern.pitch(camera.xRot()));
        float roll = HostCameraModern.roll(0f);
        if (roll != 0f) camera.rotation().rotateZ((float) Math.toRadians(-roll));
    }
    *///?}
}
