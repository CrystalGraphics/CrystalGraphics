package com.crystalgraphics.mc.modern.neoforge.mixin;

//? if >=1.21.10 <26.1 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The level's view and projection, where 1.21.6-1.21.11 hand them over: Minecraft keeps no CPU copy of the
// projection after this call. 1.21.10 adds the culling projection after them.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class LevelViewHook {

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelView(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                           boolean blockOutline, Camera camera, Matrix4f view, Matrix4f projection,
                                           Matrix4f cullingProjection, GpuBufferSlice fog, Vector4f fogColor,
                                           boolean sky, CallbackInfo ci) {
        LifecycleModern.levelMatrices(view, projection);
    }
}
*///?} elif >=1.21.6 <26.1 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The level's view and projection, where 1.21.6-1.21.11 hand them over: Minecraft keeps no CPU copy of the
// projection after this call.
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class LevelViewHook {

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelView(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                           boolean blockOutline, Camera camera, Matrix4f view, Matrix4f projection,
                                           GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        LifecycleModern.levelMatrices(view, projection);
    }
}
*///?}
