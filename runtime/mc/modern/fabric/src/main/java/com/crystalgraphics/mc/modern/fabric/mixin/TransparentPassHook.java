package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// As below; 26.2 renamed renderLevel to render.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?} elif >=1.21.10 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The transparent pass after clouds and weather, so hazes bend them: Fabric API from 1.21.9 has no event past
// END_MAIN, and the level's frame graph executes inside this method. No refmap: the method by both its names.
// @see com.crystalgraphics.mc.shared.CrystalGraphicsFabricFrameMixins
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = {"renderLevel", "method_22710"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty where WorldRenderEvents.LAST (1.16.5-1.21.8) fires the transparent pass.
@Mixin(value = Camera.class, remap = false)
public abstract class TransparentPassHook {
}
//?}
