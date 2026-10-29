package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.3 {
/*import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
*///?} else {
import net.minecraft.client.renderer.ItemInHandRenderer;
//?}

// The first-person hands on Fabric, which has no hand event: not drawn while a camera is held, since Minecraft draws
// the local player's hands whatever its camera entity. renderHandsWithItems (render on 1.14.4) to 26.1.2,
// submitHandsWithItems from 26.2, on its own renderer from 26.3. No refmap, so the name in both namespaces.
//? if >=26.3 {
/*@Mixin(value = FirstPersonHandsAndItemsRenderer.class, remap = false)
*///?} else {
@Mixin(value = ItemInHandRenderer.class, remap = false)
//?}
public abstract class HandHook {

    //? if >=26.2 {
    /*@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true, require = 1)
    *///?} elif >=1.15.2 {
    @Inject(method = {"renderHandsWithItems", "method_22976"}, at = @At("HEAD"), cancellable = true, require = 1)
    //?} else {
    /*@Inject(method = {"render", "method_3225"}, at = @At("HEAD"), cancellable = true, require = 1)
    *///?}
    private void crystalgraphics$hands(CallbackInfo ci) {
        if (HostCameraModern.held()) ci.cancel();
    }
}
