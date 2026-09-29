package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The frame end on Fabric, which has no frame event: GameRenderer.render's TAIL, after the level AND the
// GUI -- once a frame, a title screen included, where Forge's RenderTickEvent END fires. No refmap, so
// the method is named in both namespaces, descriptor and all: 1.14.4 also has a render(FJ)V.
// @see com.crystalgraphics.mc.shared.CrystalGraphicsFabricMixins
@Mixin(value = GameRenderer.class, remap = false)
public abstract class FrameEndHook {

    //? if >=26.1 {
    /*@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"), require = 1)
    *///?} elif >=1.21 {
    /*@Inject(method = {"render(Lnet/minecraft/client/DeltaTracker;Z)V", "method_3192(Lnet/minecraft/class_9779;Z)V"},
            at = @At("TAIL"), require = 1)
    *///?} else {
    @Inject(method = {"render(FJZ)V", "method_3192(FJZ)V"}, at = @At("TAIL"), require = 1)
    //?}
    private void crystalgraphics$frameEnd(CallbackInfo ci) {
        LifecycleModern.frameEnd();
    }
}
