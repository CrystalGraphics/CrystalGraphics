package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=26.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.3 resets the particles state while it EXTRACTS the frame, before anything is drawn, so reset is no
// longer "after particles". The translucent world ends with executeOit (after its composite) or, with OIT
// off, executeClassicTransparency, and both draw clouds and weather first. @see OpaquePassHook
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = {"executeOit", "executeClassicTransparency"}, at = @At("TAIL"), require = 2)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?} elif >=26.2 {
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
*///?} elif >=1.21.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The transparent pass on Forge 1.21.3+, which has no render-stage event: after clouds and weather, so hazes
// bend them. The level's frame graph -- clouds, weather and Fabulous's composite -- executes inside this
// method, and Forge adds no overload of it. @see com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = "renderLevel", at = @At("TAIL"), require = 1)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?}
