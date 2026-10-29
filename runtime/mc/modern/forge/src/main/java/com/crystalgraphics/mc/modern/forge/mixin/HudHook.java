package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.21.6 <1.21.8 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The HUD on Forge 1.21.6-1.21.7, which has no HUD event: once a frame, after vanilla's own HUD. Mojang's names at
// runtime, so no remap.
@Mixin(value = Gui.class, remap = false)
public abstract class HudHook {

    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void crystalgraphics$hud(CallbackInfo ci) {
        LifecycleModern.hud();
    }
}
*///?}
