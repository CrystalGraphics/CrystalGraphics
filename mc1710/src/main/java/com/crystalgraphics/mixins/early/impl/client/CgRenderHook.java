package com.crystalgraphics.mixins.early.impl.client;

import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the CrystalGraphics render pipeline into {@link EntityRenderer#renderWorld}
 * immediately before MC renders its transparent terrain pass.
 *
 * <p>At this hook point MC has already drawn all opaque world geometry into FBO 0
 * and the depth buffer is fully populated. CG renders its full pass sequence
 * (depth prepass → opaque forward → transparent) directly into FBO 0, then returns.
 * MC then continues and renders transparent terrain into the same FBO, naturally
 * depth-interleaving with CG geometry.</p>
 *
 * <p>Injects BEFORE the call to {@code renderTranslucentBlocks()} inside
 * {@code renderWorld(float, long)}. Source: render-pipeline-curation.md Section 11 Q7.</p>
 */
@Mixin(value = EntityRenderer.class)
public class CgRenderHook {

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
        CgPlatform.rendering().onFrameBegin(partialTicks);
    }
}
