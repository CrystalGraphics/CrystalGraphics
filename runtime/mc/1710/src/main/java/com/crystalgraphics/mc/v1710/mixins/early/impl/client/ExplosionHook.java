package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.world.WorldEvents1710;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S27PacketExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An explosion the client learned of, for {@code CgWorldEvents}: the tail of {@code handleExplosion}. */
@Mixin(NetHandlerPlayClient.class)
public abstract class ExplosionHook {

    @Inject(method = "handleExplosion", at = @At("TAIL"))
    private void crystalgraphics$explosion(S27PacketExplosion packet, CallbackInfo ci) {
        WorldEvents1710.explosion(packet);
    }
}
