package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A held camera's position, written over the one setup took from our entity, whose eye the camera eases toward over
// ticks; from 1.20.6 the camera is also set up before our entity is posed on the frame the hold begins. At the end of
// setup (26.x: just before the frame's render state is taken from it). No refmap, so each name in every runtime
// namespace. Priority 2000: applied after other mods' mixins on the camera, so a held pose is written last.
@Mixin(value = Camera.class, priority = 2000, remap = false)
public abstract class CameraHook {

    @Shadow(aliases = "m_90584_")
    protected abstract void setPosition(double x, double y, double z);

    //? if >=26.1 {
    /*@Inject(method = "extractRenderState", at = @At("HEAD"), require = 1)
    *///?} else {
    @Inject(method = {"setup", "m_90575_"}, at = @At("TAIL"), require = 1)
    //?}
    private void crystalgraphics$hold(CallbackInfo ci) {
        if (HostCameraModern.holding()) setPosition(HostCameraModern.heldX(), HostCameraModern.heldY(), HostCameraModern.heldZ());
    }
}
