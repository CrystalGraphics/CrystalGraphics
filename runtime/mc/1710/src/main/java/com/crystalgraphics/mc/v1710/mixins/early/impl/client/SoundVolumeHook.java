package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.world.HostDuck1710;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hush on the host's own sounds ({@code CgHostDuck}): each category volume computed, times our factor for it. */
@Mixin(SoundManager.class)
public abstract class SoundVolumeHook {

    @Inject(method = "getSoundCategoryVolume", at = @At("RETURN"), cancellable = true)
    private void crystalgraphics$duck(SoundCategory category, CallbackInfoReturnable<Float> cir) {
        float f = HostDuck1710.factor(category);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
}
