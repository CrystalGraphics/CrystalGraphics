package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.21.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The transparent pass on Forge 1.21.3+: after particles, where RenderLevelStageEvent's AFTER_PARTICLES
// fired. Forge's own render overload -- the one with a Frustum, which vanilla's now calls -- so the pass
// runs once a frame. Its other parameters moved in 1.21.4; one selector per shape, and one must bind.
// @see com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
@Mixin(value = ParticleEngine.class, remap = false)
public abstract class TransparentPassHook {

    @Inject(method = {
            "render(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/culling/Frustum;)V",
            "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
    }, at = @At("TAIL"), require = 1)
    private void crystalgraphics$transparentPass(CallbackInfo ci) {
        LifecycleModern.transparentPass();
    }
}
*///?}
