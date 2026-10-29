package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.world.HostDuckLegacy;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.9 {
import net.minecraft.util.SoundCategory;
//?} else {
/*import net.minecraft.client.audio.SoundCategory;
*///?}

/**
 * A hush on the host's own sounds ({@code CgHostDuck}): each category volume {@code SoundManager} computes, times our
 * factor for it, at its SRG name: {@code getVolume} from 1.9, {@code getSoundCategoryVolume} on 1.8.9.
 */
@Mixin(value = SoundManager.class, remap = false)
public abstract class SoundVolumeHook {

    //? if >=1.9 {
    @Inject(method = "func_188769_a", remap = false, require = 1, at = @At("RETURN"), cancellable = true)
    //?} else {
    /*@Inject(method = "func_148595_a", remap = false, require = 1, at = @At("RETURN"), cancellable = true)
    *///?}
    private void crystalgraphics$duck(SoundCategory category, CallbackInfoReturnable<Float> cir) {
        float f = HostDuckLegacy.factor(category);
        if (f != 1f) cir.setReturnValue(cir.getReturnValueF() * f);
    }
}
