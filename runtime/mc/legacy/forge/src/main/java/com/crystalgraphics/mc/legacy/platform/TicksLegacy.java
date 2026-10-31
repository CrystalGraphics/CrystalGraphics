package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * FML's server and client ticks turned into the engine's, on Forge 1.8–1.12.2. Both sides, on FML's bus; registered by
 * {@link PlatformServiceLegacy#install}.
 *
 * <p>At {@code END}, as every other host does: what arrived during the tick is taken in it, and what the world changed
 * in it goes out at once.</p>
 */
public final class TicksLegacy {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) CgEngineLifecycle.serverTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) CgEngineLifecycle.clientTick();
    }
}
