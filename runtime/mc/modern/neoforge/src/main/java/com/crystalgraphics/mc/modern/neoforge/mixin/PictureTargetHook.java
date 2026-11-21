package com.crystalgraphics.mc.modern.neoforge.mixin;

//? if >=26.2 <26.3 {
/*import com.crystalgraphics.mc.modern.platform.PicturesProbeModern;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A GuiRenderer of ours draws into the main target and sizes itself by the window: while a picture draws, the main
// target is the picture's (NR0, renderer § 19.14 2).
@Mixin(value = GameRenderer.class, remap = false)
public abstract class PictureTargetHook {

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$pictureTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget picture = PicturesProbeModern.pictureTarget();
        if (picture != null) cir.setReturnValue(picture);
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty on the nodes that pin this plugin and have no picture host code.
@Mixin(value = Camera.class, remap = false)
public abstract class PictureTargetHook {
}
//?}
