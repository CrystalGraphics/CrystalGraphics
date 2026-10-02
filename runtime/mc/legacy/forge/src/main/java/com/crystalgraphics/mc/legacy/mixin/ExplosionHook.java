package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import net.minecraft.client.network.NetHandlerPlayClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.9 {
import net.minecraft.network.play.server.SPacketExplosion;
//?} else {
/*import net.minecraft.network.play.server.S27PacketExplosion;
*///?}

/**
 * An explosion the client learned of, for {@code CgWorldEvents}: the tail of {@code handleExplosion} at its SRG name,
 * which the network thread leaves early, so only the client thread reaches it.
 */
@Mixin(value = NetHandlerPlayClient.class, remap = false)
public abstract class ExplosionHook {

    @Inject(method = "func_147283_a", remap = false, require = 1, at = @At("TAIL"))
    //? if >=1.9 {
    private void crystalgraphics$explosion(SPacketExplosion packet, CallbackInfo ci) {
    //?} else {
    /*private void crystalgraphics$explosion(S27PacketExplosion packet, CallbackInfo ci) {
    *///?}
        WorldEventsLegacy.explosion(packet);
    }
}
