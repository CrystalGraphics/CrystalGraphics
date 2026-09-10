package com.crystalgraphics.mixins.early.impl.client;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the CrystalGraphics render pipeline into {@link EntityRenderer#renderWorld}
 * at two points: once before the translucent terrain pass (opaque hook) and once
 * before {@code ForgeHooksClient.dispatchRenderLast} (transparent hook). Also injects
 * a single per-frame tick signal at the tail of {@link EntityRenderer#updateCameraAndRender}.
 *
 * <h3>Validated injection targets (EntityRenderer.java, MC 1.7.10 deobf)</h3>
 * <ul>
 *   <li><strong>Opaque hook</strong> — second call to
 *       {@code TextureManager.bindTexture(ResourceLocation)} in {@code renderWorld}
 *       (ordinal=1, line ~1367). MC has finished all opaque geometry and entities;
 *       depth buffer is fully populated. CG's opaque passes run here.</li>
 *   <li><strong>Transparent hook</strong> — call to
 *       {@code ForgeHooksClient.dispatchRenderLast(RenderGlobal, float)}
 *       (line ~1430). MC has finished sortAndRender(pass=1) and translucent entities;
 *       CG's transparent pass runs here.</li>
 *   <li><strong>Frame-tick hook</strong> — {@code @At("TAIL")} of
 *       {@code updateCameraAndRender} (line ~1013-1172). Verified against the decompiled
 *       source: the method body is a single straight-line sequence with no early returns,
 *       entirely gated by one outer {@code if (!this.mc.skipRenderWorld)} that wraps both
 *       the world-render branch and the no-world overlay-setup branch, followed by the
 *       unconditional (whenever {@code currentScreen != null}) GUI screen draw at line
 *       ~1130-1170. So TAIL fires exactly once per call to this method, covering the
 *       in-world case, the no-world-with-GUI case (main menu, etc.), and the
 *       skip-render-world case uniformly — unlike {@code renderWorld} alone, which never
 *       fires without a loaded world.</li>
 * </ul>
 */
@Mixin(value = EntityRenderer.class)
public class CgRenderHook {

    /**
     * Fires just before {@code sortAndRender(pass=1)} — MC opaque world + entities fully drawn.
     * Validated ordinal: ordinal=1 of TextureManager.bindTexture at EntityRenderer.java line ~1367.
     */
    @Inject(
        method = "renderWorld",
        at = @At(
            value   = "INVOKE",
            target  = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V",
            ordinal = 1,
            shift   = At.Shift.BEFORE
        )
    )
    private void onBeforeTranslucentBlocks(float partialTicks, long finishTimeNano,
                                            CallbackInfo ci) {
        // 1.7.10 renders into mc.getFramebuffer().framebufferObject (the MC main FBO).
        // Validated: Framebuffer.java field `public int framebufferObject`.
        int sourceFboId = Minecraft.getMinecraft().getFramebuffer().framebufferObject;
        int w = Minecraft.getMinecraft().displayWidth;
        int h = Minecraft.getMinecraft().displayHeight;
        CgGraphicsLifecycle.onOpaquePass(partialTicks, w, h, sourceFboId);
    }

    /**
     * Fires after {@code sortAndRender(pass=1)} + translucent entities — all translucent
     * content drawn. Validated target: ForgeHooksClient.dispatchRenderLast at
     * EntityRenderer.java line ~1430. Descriptor: (Lnet/minecraft/client/renderer/RenderGlobal;F)V
     */
    @Inject(
        method = "renderWorld",
        at = @At(
            value  = "INVOKE",
            target = "Lnet/minecraftforge/client/ForgeHooksClient;dispatchRenderLast(Lnet/minecraft/client/renderer/RenderGlobal;F)V",
            shift  = At.Shift.BEFORE
        )
    )
    private void onAfterTranslucentContent(float partialTicks, long finishTimeNano,
                                            CallbackInfo ci) {
        // Note: CG geometry renders outside Angelica/Iris's GBuffer chain.
        // See CgIrisCompat for detection API if Iris-specific behaviour is needed.
        CgGraphicsLifecycle.onTransparentPass();
    }

    /**
     * Canonical per-frame tick point (see {@code CgLifecycleService.onFrameRendered()}'s
     * javadoc). Fires at the tail of {@code updateCameraAndRender}, which has no early
     * returns — see the class javadoc for the full verification. This covers world
     * rendering, no-world GUI screens (main menu, etc.), and the skip-render-world case
     * uniformly, closing the gap left by hooking {@code renderWorld} alone.
     */
    @Inject(method = "updateCameraAndRender", at = @At("TAIL"))
    private void onFrameRendered(float partialTicks, CallbackInfo ci) {
        CgPlatform.lifecycle().onFrameRendered();
    }
}
