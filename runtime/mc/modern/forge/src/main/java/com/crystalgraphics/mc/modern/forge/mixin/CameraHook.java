package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The camera itself, where Forge's events fall short. A held camera's position, written over the one setup took from
// our entity, whose eye the camera eases toward over ticks; from 1.21.11 the camera is also set up before our entity
// is posed. At the end of setup (26.x: just before the frame's render state is taken from it). And a camera shake on
// Forge 26.1.1 to 26.2, whose camera-angle event comes after renderLevel has taken the view: the camera's angles turned
// by the offset core gave (HostCameraModern), roll about the camera's own axis; the event that follows meets angles
// already turned and adds nothing. No refmap, so each name in every runtime namespace. Priority 2000: applied after
// other mods' mixins on the camera, so a held pose is written last.
@Mixin(value = Camera.class, priority = 2000, remap = false)
public abstract class CameraHook {

    @Shadow(aliases = {"m_90584_", "func_216775_b"})
    protected abstract void setPosition(double x, double y, double z);

    //? if >=26.1 <26.3 {
    /*@Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 1)
    private void crystalgraphics$shake(CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        setRotation(HostCameraModern.yaw(camera.yRot()), HostCameraModern.pitch(camera.xRot()));
        float roll = HostCameraModern.roll(0f);
        if (roll != 0f) camera.rotation().rotateZ((float) Math.toRadians(-roll));
        if (HostCameraModern.holding()) setPosition(HostCameraModern.heldX(), HostCameraModern.heldY(), HostCameraModern.heldZ());
    }
    *///?} elif >=26.3 {
    /*@Inject(method = "extractRenderState", at = @At("HEAD"), require = 1)
    private void crystalgraphics$hold(CallbackInfo ci) {
        if (HostCameraModern.holding()) setPosition(HostCameraModern.heldX(), HostCameraModern.heldY(), HostCameraModern.heldZ());
    }
    *///?} else {
    @Inject(method = {"setup", "m_90575_", "func_216772_a"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$hold(CallbackInfo ci) {
        if (HostCameraModern.holding()) setPosition(HostCameraModern.heldX(), HostCameraModern.heldY(), HostCameraModern.heldZ());
    }
    //?}
}
