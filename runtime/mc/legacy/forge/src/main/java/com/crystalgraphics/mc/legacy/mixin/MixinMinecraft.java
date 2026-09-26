package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resize and shutdown on Forge 1.8–1.12.2, at SRG names.
 *
 * <ul>
 *   <li>Shutdown hooks the HEAD of {@code shutdownMinecraftApplet}, which every exit path reaches: the
 *       context is still whole there, and freeing GL objects after its tail is undefined.</li>
 * </ul>
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraft {

    @Inject(method = "func_71370_a", remap = false, require = 1, at = @At("TAIL"))
    private void cg$resize(int width, int height, CallbackInfo ci) {
        CgPlatform.lifecycle().onResize(width, height);
    }

    @Inject(method = "func_71352_k", remap = false, require = 1, at = @At("TAIL"))
    private void cg$fullscreen(CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        CgPlatform.lifecycle().onResize(mc.displayWidth, mc.displayHeight);
    }

    @Inject(method = "func_71405_e", remap = false, require = 1, at = @At("HEAD"))
    private void cg$shutdown(CallbackInfo ci) {
        CgPlatform.lifecycle().onContextDestroy();
    }
}
