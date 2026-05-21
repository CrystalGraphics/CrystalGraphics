package com.crystalgraphics.mc.mixin;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link GameRenderer#render(float, long, boolean)} to call
 * {@link CgGraphicsLifecycle#onRenderFrame} immediately after {@code renderLevel()}
 * returns each frame.
 *
 * <p>The injection fires only when {@code renderLevel} is {@code true} (the guard below
 * ensures we skip frames where no level is being rendered, matching the check at
 * {@link GameRenderer#render} line 1105). Descriptor verified against extracted
 * MC 1.20.1 source at {@code mc1201/fabric/build/mc-src/java/net/minecraft/client/renderer/GameRenderer.java:1107}.</p>
 *
 * <p><b>NeoForge note (MC 1.20.4)</b>: This mixin is compiled against the 1.20.1 descriptor.
 * If {@code renderLevel} changes signature between 1.20.1 and 1.20.4, Mixin will silently
 * not apply on the NeoForge loader. A separate audit task will verify this at runtime.</p>
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Inject(
        method = "render",
        at = @At(
            value  = "INVOKE",
            // Descriptor verified from GameRenderer.java:1107:
            // this.renderLevel(partialTicks, nanoTime, new PoseStack());
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            shift  = At.Shift.AFTER
        )
    )
    private void cg_onAfterRenderLevel(float partialTicks, long nanoTime,
                                        boolean renderLevel, CallbackInfo ci) {
        if (!renderLevel) return;
        int w = Minecraft.getInstance().getWindow().getWidth();
        int h = Minecraft.getInstance().getWindow().getHeight();
        CgGraphicsLifecycle.onRenderFrame(partialTicks, w, h);
    }
}
