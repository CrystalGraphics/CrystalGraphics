package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=26.3 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.3 hands renderGroup a render pass its caller opened, so its head is inside Minecraft's pass. Opaque
// terrain is done when translucency starts: executeOit (OIT, passes of its own) or, with OIT off,
// executeClassicTransparency, still inside the solid pass. One of the two runs a frame.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class OpaquePassHook {

    @Inject(method = {"executeOit", "executeClassicTransparency"}, at = @At("HEAD"), require = 2)
    private void crystalgraphics$opaquePass(CallbackInfo ci) {
        LifecycleModern.opaquePass(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }
}
*///?} elif >=1.21.11 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// As below; 1.21.11 hands renderGroup the sampler too.
@Mixin(value = ChunkSectionsToRender.class, remap = false)
public abstract class OpaquePassHook {

    @Inject(method = "renderGroup", at = @At("HEAD"), require = 1)
    private void crystalgraphics$opaquePass(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            LifecycleModern.opaquePass(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        }
    }
}
*///?} elif >=1.21.6 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The opaque pass on Forge 1.21.3+: just before translucent terrain, where RenderLevelStageEvent's
// AFTER_BLOCK_ENTITIES fired until Forge 53 removed it. 1.21.6 draws terrain by layer GROUP.
// Mojang's names at runtime, so no remap. @see com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins
@Mixin(value = ChunkSectionsToRender.class, remap = false)
public abstract class OpaquePassHook {

    @Inject(method = "renderGroup", at = @At("HEAD"), require = 1)
    private void crystalgraphics$opaquePass(ChunkSectionLayerGroup group, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            LifecycleModern.opaquePass(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        }
    }
}
*///?} elif >=1.21.3 {
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
public abstract class OpaquePassHook {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 1)
    private void crystalgraphics$opaquePass(RenderType layer, double x, double y, double z,
                                            Matrix4f frustum, Matrix4f projection, CallbackInfo ci) {
        if (layer == RenderType.translucent()) {
            LifecycleModern.opaquePass(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        }
    }
}
*///?}
