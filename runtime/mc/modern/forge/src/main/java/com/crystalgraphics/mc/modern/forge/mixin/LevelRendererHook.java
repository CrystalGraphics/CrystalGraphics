package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.21.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The opaque pass on Forge 1.21.3+: just before translucent terrain, where RenderLevelStageEvent's
// AFTER_BLOCK_ENTITIES fired until Forge 53 removed it. Mojang's names at runtime, so no remap.
// @see com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class LevelRendererHook {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 1)
    private void crystalgraphics$opaquePass(RenderType layer, double x, double y, double z,
                                            Matrix4f frustum, Matrix4f projection, CallbackInfo ci) {
        if (layer == RenderType.translucent()) {
            LifecycleModern.opaquePass(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        }
    }
}
*///?}
