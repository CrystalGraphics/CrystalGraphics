package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.world.HostDuckModern;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A hush on the host's own sounds (CgHostDuck): each volume the engine computes for a source, times our factor for it.
// getVolume to 1.21.8, which every computation calls; calculateVolume(float, SoundSource) from 1.21.10, where it went.
// Mojang names 1.21.3+, SRG (m_) 1.17-1.21.1, MCP (func_) 1.13.2-1.16.5.
@Mixin(value = SoundEngine.class, remap = false)
public abstract class SoundVolumeHook {

    //? if >=1.21.10 {
    /*@Inject(method = {"calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$duck(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
        float f = HostDuckModern.factor(source);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
    *///?} else {
    @Inject(method = {"getVolume", "m_120258_", "func_188769_a"}, at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$duck(SoundSource source, CallbackInfoReturnable<Float> cir) {
        float f = HostDuckModern.factor(source);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
    //?}
}
