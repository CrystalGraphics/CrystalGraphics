package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=1.15.2 <1.21.5 {
import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// While a picture draws, each blend gets the alpha factors Mojang pairs with its colour factors from 1.21.6
// (BlendFunction): the GUI's own leave alpha raw, so one draw over a clear page would hold the top layer's alpha, not
// the coverage. Named as Mojang, SRG (1.17 on, then 1.15-1.16's) and Fabric's intermediary name them.
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GuiBlendHook {

    @Inject(method = {"_blendFunc", "m_84328_", "func_227676_b_", "method_21984"}, at = @At("HEAD"), cancellable = true, require = 1)
    private static void crystalgraphics$blendFunc(int src, int dst, CallbackInfo ci) {
        if (!PicturesModern.pairsAlpha()) return;
        GlStateManager._blendFuncSeparate(src, dst, src, dst);
        ci.cancel();
    }

    @Inject(method = {"_blendFuncSeparate", "m_84335_", "func_227644_a_", "method_21950"}, at = @At("HEAD"), cancellable = true, require = 1)
    private static void crystalgraphics$blendFuncSeparate(int src, int dst, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (!PicturesModern.pairsAlpha()) return;
        int pairedSrc = PicturesModern.pairedSrcAlpha(src, dst, srcAlpha), pairedDst = PicturesModern.pairedDstAlpha(src, dst, dstAlpha);
        if (pairedSrc == srcAlpha && pairedDst == dstAlpha) return;
        GlStateManager._blendFuncSeparate(src, dst, pairedSrc, pairedDst);
        ci.cancel();
    }

    // An unblended draw is opaque on screen, so on a page it writes alpha 1: blending kept on, the colour as drawn and
    // the alpha the blend colour's (1 while a picture draws). The disable lands as Minecraft's draw ends.
    @Inject(method = {"_disableBlend", "m_84519_", "func_227737_l_", "method_22053"}, at = @At("HEAD"), cancellable = true, require = 1)
    private static void crystalgraphics$disableBlend(CallbackInfo ci) {
        if (!PicturesModern.pairsAlpha()) return;
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(1, 0, 32771, 0);
        PicturesModern.holdBlend();
        ci.cancel();
    }
}
//?} else {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty outside 1.15.2 - 1.21.4: from 1.21.5 the GUI's pipelines blend with these factors themselves; below 1.15.2
// pictures keep the two clears.
@Mixin(value = Camera.class, remap = false)
public abstract class GuiBlendHook {
}
*///?}
