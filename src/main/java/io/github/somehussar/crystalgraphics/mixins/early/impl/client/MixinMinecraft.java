package io.github.somehussar.crystalgraphics.mixins.early.impl.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class)
public class MixinMinecraft {

    @Shadow
    public int displayWidth;

    @Shadow
    public int displayHeight;

    @Inject(method = "resize", at = @At("TAIL"))
    private void onResize(int width, int height, CallbackInfo ci) {
    }

    @Inject(method = "toggleFullscreen", at = @At("TAIL"))
    private void updateDisplayMode(CallbackInfo ci) {
    }

    @Inject(method = "refreshResources", at = @At("TAIL"))
    private void onRefresh(CallbackInfo ci) {
    }
}
