package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=1.21.6 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Fabric API hands every GuiRenderer made its special-element renderers through onReady, and to about 1.21.8 (its
// registry before 16.x) throws on a second: the pictures' own GuiRenderer is one. Ours takes its renderers itself
// (PicturesModern.PictureInPicture), so Fabric API's step is skipped while ours is made. Absent Fabric API, nothing.
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.SpecialGuiElementRegistryImpl", remap = false)
public abstract class GuiRegistryHook {

    @Inject(method = "onReady", at = @At("HEAD"), cancellable = true, require = 0)
    private static void crystalgraphics$notOurs(CallbackInfo ci) {
        if (PicturesModern.makingRenderer()) ci.cancel();
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty below 1.21.6: no GuiRenderer of the pictures' own.
@Mixin(value = Camera.class, remap = false)
public abstract class GuiRegistryHook {
}
//?}
