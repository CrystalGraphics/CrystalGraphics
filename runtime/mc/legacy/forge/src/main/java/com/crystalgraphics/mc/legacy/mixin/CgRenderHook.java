package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.mc.legacy.platform.world.HostCameraLegacy;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.mc.legacy.platform.LoadingScreenLegacy;
import com.crystalgraphics.mc.legacy.platform.world.EnvironmentLegacy;
import com.crystalgraphics.mc.legacy.platform.world.TexturesLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
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
        crystalgraphics$opaque(Minecraft.getMinecraft(), partialTicks, pass);
    }

    @Inject(method = "func_175068_a", remap = false, require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/ForgeHooksClient;dispatchRenderLast(Lnet/minecraft/client/renderer/RenderGlobal;F)V"))
    private void cg$transparentPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        // The opaque callback's frame: one level render
        CgStageHost.fire(CgStageHost.WORLD_TRANSPARENT);
    }

    /** Fills the world callbacks' frame, which the transparent one shares, and fires the opaque one. */
    @Unique
    private static void crystalgraphics$opaque(Minecraft mc, float partialTicks, int pass) {
        CgHostFrame frame = CgStageHost.frame(CgStageHost.WORLD_OPAQUE)
                .set(partialTicks, mc.displayWidth, mc.displayHeight, mc.getFramebuffer().framebufferObject);
        // renderWorldPass's pass is 0 and 1 for anaglyph's eyes, 2 otherwise: each eye a view, with histories of its own
        frame.view(pass == 1 ? HostCameraLegacy.RIGHT_EYE : CgView.main());
        HostCameraLegacy.capture(mc, partialTicks, frame.camera());
        EnvironmentLegacy.capture(mc, partialTicks, frame.camera(), frame.environment());
        TexturesLegacy.capture(mc, frame.textures());
        WorldEventsLegacy.poll();
        // Minecraft's objects in the scene, by Minecraft's renderers with its state: before the section, into its depth
        CgHostWorld.drawPoint(frame.camera(), partialTicks);
        CgStageHost.fire(CgStageHost.WORLD_OPAQUE);
    }

    @Inject(method = "func_181560_a", remap = false, require = 1, at = @At("TAIL"))
    private void cg$frameRendered(float partialTicks, long nanoTime, CallbackInfo ci) {
        // The engine starts on the first frame, the title screen's, so its shaders compile before a world exists.
        Minecraft mc = Minecraft.getMinecraft();
        CgEngineLifecycle.advanceGraphicsContext(mc.displayWidth, mc.displayHeight);
        LoadingScreenLegacy.draw(mc);
        CgGraphicsLifecycle.tickFrame();
        CgLoadingScreen.runHeld();
    }
}
