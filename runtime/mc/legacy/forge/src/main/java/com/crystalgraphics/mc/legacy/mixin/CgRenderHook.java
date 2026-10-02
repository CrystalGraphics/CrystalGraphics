package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.mc.legacy.platform.HostViewLegacy;
import com.crystalgraphics.mc.legacy.platform.world.EnvironmentLegacy;
import com.crystalgraphics.mc.legacy.platform.world.TexturesLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world render passes and the frame tick on Forge 1.8–1.12.2, at SRG names and without a refmap —
 * the names are the same on all three plateaus.
 *
 * <ul>
 *   <li>Opaque: before {@code renderWorldPass} starts its {@code translucent} profiler section, when
 *       opaque terrain, entities and weather are drawn and the depth buffer is full.</li>
 *   <li>Transparent: before Forge's {@code dispatchRenderLast}, after translucent terrain and entities.</li>
 *   <li>Frame: the tail of {@code updateCameraAndRender}, which runs with or without a world.</li>
 * </ul>
 */
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class CgRenderHook {

    @Inject(method = "func_175068_a", remap = false, require = 1, at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/Profiler;func_76318_c(Ljava/lang/String;)V", args = "ldc=translucent"))
    private void cg$opaquePass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        crystalgraphics$fire(CgRenderStage.WORLD_OPAQUE, Minecraft.getMinecraft(), partialTicks);
    }

    @Inject(method = "func_175068_a", remap = false, require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/ForgeHooksClient;dispatchRenderLast(Lnet/minecraft/client/renderer/RenderGlobal;F)V"))
    private void cg$transparentPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        crystalgraphics$fire(CgRenderStage.WORLD_TRANSPARENT, Minecraft.getMinecraft(), partialTicks);
    }

    @Unique
    private static void crystalgraphics$fire(CgRenderStage stage, Minecraft mc, float partialTicks) {
        CgHostFrame frame = stage.host()
                .set(partialTicks, mc.displayWidth, mc.displayHeight, mc.getFramebuffer().framebufferObject);
        HostViewLegacy.capture(mc, partialTicks, frame.view());
        if (stage == CgRenderStage.WORLD_OPAQUE) {
            EnvironmentLegacy.capture(mc, partialTicks, frame.view(), frame.environment());
            TexturesLegacy.capture(mc, frame.textures());
            WorldEventsLegacy.poll();
        } else {
            // The same level render as the opaque pass.
            frame.environment().set(CgRenderStage.WORLD_OPAQUE.host().environment());
            frame.textures().set(CgRenderStage.WORLD_OPAQUE.host().textures());
        }
        stage.fire();
    }

    @Inject(method = "func_181560_a", remap = false, require = 1, at = @At("TAIL"))
    private void cg$frameRendered(float partialTicks, long nanoTime, CallbackInfo ci) {
        CgPlatform.lifecycle().onFrameRendered();
    }
}
