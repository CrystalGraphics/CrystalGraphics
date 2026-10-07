package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=26.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.3 draws clouds and weather inside executeOit (after its composite) or, with OIT off,
// executeClassicTransparency: their tail, as Forge 26.3's hook.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = {"executeOit", "executeClassicTransparency"}, at = @At("TAIL"), require = 2)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?} elif >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.renderer.WorldBorderRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Inside the frame graph, not after it: under Vulkan the world's depth is gone once the graph has run, and the
// showcase's sky covered everything. The world border is the weather pass's last draw, after clouds and weather;
// LevelRenderer calls it once a frame.
@Mixin(value = WorldBorderRenderer.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lnet/minecraft/world/phys/Vec3;DD)V",
            at = @At("RETURN"), require = 1)
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
