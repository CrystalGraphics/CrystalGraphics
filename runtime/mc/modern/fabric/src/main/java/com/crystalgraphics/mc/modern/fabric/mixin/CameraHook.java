package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A camera shake on Fabric, which has no camera event: the camera's angles turned by the offset core gave
// (HostCameraModern) once it has aligned with its entity -- at the end of setup to 1.21.11, and from 26.1 just before
// the frame's render state is taken from it. Roll from 1.21.11, where Minecraft renders from the camera's quaternion:
// vanilla's setRotation takes two angles. No refmap, so each name in both namespaces.
// Priority 2000: applied after other mods' mixins on the camera, so a held pose is written last.
@Mixin(value = Camera.class, priority = 2000, remap = false)
public abstract class CameraHook {

    @Shadow(aliases = "method_19325")
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow(aliases = "method_19327")
    protected abstract void setPosition(double x, double y, double z);

    //? if >=26.1 {
    /*@Inject(method = "extractRenderState", at = @At("HEAD"), require = 1)
    *///?} else {
    @Inject(method = {"setup", "method_19321"}, at = @At("TAIL"), require = 1)
    //?}
    private void crystalgraphics$shake(CallbackInfo ci) {
        crystalgraphics$turn((Camera) (Object) this);
    }

    @Unique
    private void crystalgraphics$turn(Camera camera) {
        //? if >=1.21.11 {
        /*setRotation(HostCameraModern.yaw(camera.yRot()), HostCameraModern.pitch(camera.xRot()));
        float roll = HostCameraModern.roll(0f);
        if (roll != 0f) camera.rotation().rotateZ((float) Math.toRadians(-roll));
        *///?} else {
        setRotation(HostCameraModern.yaw(camera.getYRot()), HostCameraModern.pitch(camera.getXRot()));
        //?}
        // A held pose's position and the offset's translation, written here: from 1.21.11 the camera is set up before
        // our entity is posed.
        if (HostCameraModern.moving()) {
            //? if >=1.21.6 {
            /*Vec3 at = camera.position();
            *///?} else {
            Vec3 at = camera.getPosition();
            //?}
            setPosition(HostCameraModern.x(at.x), HostCameraModern.y(at.y), HostCameraModern.z(at.z));
        }
    }
}
