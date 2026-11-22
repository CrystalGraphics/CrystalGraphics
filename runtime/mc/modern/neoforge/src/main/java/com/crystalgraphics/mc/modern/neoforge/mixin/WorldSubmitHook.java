package com.crystalgraphics.mc.modern.neoforge.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.10 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
//? if >=1.21.10 <26.1 {
/*import net.minecraft.client.renderer.state.LevelRenderState;
*///?} elif >=26.1 {
/*import net.minecraft.client.renderer.state.level.LevelRenderState;
*///?}

// From 1.21.10 the level submits its entities into a collector its feature frame renders: the scene's host-drawn
// components are submitted after them, under the level's camera (LifecycleModern.worldSubmit). Before 1.21.10 they
// draw at the opaque hook, and this mixin is empty. Mojang's names at
// runtime, so no remap.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class WorldSubmitHook {
    //? if >=1.21.10 {
    /*@Inject(method = "submitEntities", at = @At("RETURN"), require = 1)
    private void crystalgraphics$worldSubmit(PoseStack pose, LevelRenderState level, SubmitNodeCollector out, CallbackInfo ci) {
        LifecycleModern.worldSubmit(level, out);
    }
    *///?}
}
