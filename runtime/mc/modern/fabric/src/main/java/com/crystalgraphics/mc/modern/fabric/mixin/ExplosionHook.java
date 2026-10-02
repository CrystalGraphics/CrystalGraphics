package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.world.WorldEventsModern;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// An explosion the client learned of, for CgWorldEvents: the tail of the packet handler, reached only on the client thread
// (the head re-queues it there). No refmap, so the name in both namespaces; it has no overloads.
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ExplosionHook {

    @Inject(method = {"handleExplosion", "method_11124"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$explosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        WorldEventsModern.explosion(packet);
    }
}
