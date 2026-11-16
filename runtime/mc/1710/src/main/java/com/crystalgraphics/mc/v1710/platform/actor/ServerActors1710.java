package com.crystalgraphics.mc.v1710.platform.actor;

import com.crystalgraphics.mc.v1710.platform.world.LevelEntityQuery1710;
import com.crystalgraphics.world.CgEntityLifecycle;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

/**
 * Entities joining a server's levels on 1.7.10, for what the engine keeps on them there ({@link CgEntityLifecycle}). Both sides, so it
 * names no client class: {@code MinecraftForge.EVENT_BUS.register(new ServerActors1710())}.
 *
 * <ul>
 *   <li>Forge has no leave event here: what the engine kept on an entity that is gone goes at its next tick.</li>
 * </ul>
 */
public final class ServerActors1710 {

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (!event.world.isRemote) CgEntityLifecycle.enteredServer(event.entity, LevelEntityQuery1710.typeOf(event.entity));
    }
}
