package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.21.2 <26.2 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While a picture draws, the main target is the picture's: 1.21.2-1.21.5's render types (MAIN_TARGET binds it) and
// 1.21.6+'s GuiRenderer draw into whatever this answers (renderer § 19.7).
@Mixin(value = Minecraft.class, remap = false)
public abstract class GuiTargetHook {

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$pictureTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget picture = PicturesModern.target(null);
        if (picture != null) cir.setReturnValue(picture);
    }
}
*///?} elif >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While a picture draws, the main target is the picture's: the GuiRenderer draws into whatever this answers
// (renderer § 19.7).
@Mixin(value = GameRenderer.class, remap = false)
public abstract class GuiTargetHook {

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$pictureTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget picture = PicturesModern.target(null);
        if (picture != null) cir.setReturnValue(picture);
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty below 1.21.2: a picture's target is bound there, and no render type binds the main one over it.
@Mixin(value = Camera.class, remap = false)
public abstract class GuiTargetHook {
}
//?}
