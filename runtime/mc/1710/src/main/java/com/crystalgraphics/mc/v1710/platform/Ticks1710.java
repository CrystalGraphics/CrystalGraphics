package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * FML's server and client ticks turned into the engine's, on 1.7.10. Both sides, on FML's bus; registered by
 * {@link PlatformService1710#install}.
 *
 * <p>At {@code END}, as the modern hosts do: 1.7.10 hands our frames over inside the tick (the server's
 * {@code networkTick}, the client's {@code updateController}), so a {@code START} pump left each a tick late.</p>
 */
public final class Ticks1710 {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) CgEngineLifecycle.serverTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) CgEngineLifecycle.clientTick();
    }
}
