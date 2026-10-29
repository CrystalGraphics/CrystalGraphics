package com.crystalgraphics.mc.modern.fabric.mixin;

//? if <1.15 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The HUD on Fabric 1.14, whose Fabric API has no HudRenderCallback: once a frame, after vanilla's. Each name in both
// namespaces, as there is no refmap.
@Mixin(value = Gui.class, remap = false)
public abstract class HudHook {

    @Inject(method = {"render", "method_1753"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$hud(CallbackInfo ci) {
        LifecycleModern.hud();
    }
}
*///?}
