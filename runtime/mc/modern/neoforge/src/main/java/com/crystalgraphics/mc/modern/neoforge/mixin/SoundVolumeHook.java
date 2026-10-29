package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostDuckModern;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A hush on the host's own sounds (CgHostDuck): each volume the engine computes for a source, times our factor for it.
// getVolume to 1.21.8, which every computation calls; calculateVolume(float, SoundSource) from 1.21.10, where it went.
// Mojang names from 1.20.5, SRG (m_) on 1.20.2-1.20.4.
@Mixin(value = SoundEngine.class, remap = false)
public abstract class SoundVolumeHook {

    //? if >=1.21.10 {
    /*@Inject(method = {"calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$duck(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
        float f = HostDuckModern.factor(source);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
    *///?} else {
    @Inject(method = {"getVolume", "m_120258_"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$duck(SoundSource source, CallbackInfoReturnable<Float> cir) {
        float f = HostDuckModern.factor(source);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
    //?}
}
