package com.crystalgraphics.mc.modern.neoforge.mixin;

//? if >=1.21.6 {
/*import com.crystalgraphics.mc.modern.platform.world.WorldEventsModern;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// An explosion the client learned of, for CgWorldEvents: the tail of the packet handler, reached only on the client
// thread. NeoForge from 1.21.6, the nodes that pin a mixin plugin; the older ones have no hook for it.
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ExplosionHook {

    @Inject(method = "handleExplosion", at = @At("TAIL"), require = 1)
    private void crystalgraphics$explosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        WorldEventsModern.explosion(packet);
    }
}
*///?}
