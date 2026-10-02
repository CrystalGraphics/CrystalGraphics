package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.world.WorldEvents1710;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block broken, for {@code CgWorldEvents}: the head of the level renderer's {@code playAuxSFX}, which sees event 2001
 * from the server and from the local player alike.
 */
@Mixin(RenderGlobal.class)
public abstract class LevelEventHook {

    @Inject(method = "playAuxSFX", at = @At("HEAD"))
    private void crystalgraphics$levelEvent(EntityPlayer source, int type, int x, int y, int z, int data, CallbackInfo ci) {
        WorldEvents1710.levelEvent(type, x, y, z, data);
    }
}
