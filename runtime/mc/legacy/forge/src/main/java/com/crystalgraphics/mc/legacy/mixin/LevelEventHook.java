package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.9 {
import net.minecraft.util.math.BlockPos;
//?} else {
/*import net.minecraft.util.BlockPos;
*///?}

/**
 * A block broken, for {@code CgWorldEvents}: the head of the level renderer's level-event handler ({@code playEvent},
 * {@code playAuxSFX} on 1.8.9) at its SRG name, which sees event 2001 from the server and from the local player alike.
 */
@Mixin(value = RenderGlobal.class, remap = false)
public abstract class LevelEventHook {

    @Inject(method = "func_180439_a", remap = false, require = 1, at = @At("HEAD"))
    private void crystalgraphics$levelEvent(EntityPlayer source, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldEventsLegacy.levelEvent(type, pos, data);
    }
}
