package com.crystalgraphics.mc.v1710.platform.actor;

import com.crystalgraphics.mc.v1710.platform.world.LevelEntityQuery1710;
import com.crystalgraphics.world.actor.CgEntityActors;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

/**
 * Entities joining a server's levels on 1.7.10, for their actors there ({@link CgEntityActors}). Both sides, so it
 * names no client class: {@code MinecraftForge.EVENT_BUS.register(new ServerActors1710())}.
 *
 * <ul>
 *   <li>Forge has no leave event here: an actor whose entity is gone goes at its next tick.</li>
 * </ul>
 */
public final class ServerActors1710 {

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (!event.world.isRemote) CgEntityActors.enteredServer(event.entity, LevelEntityQuery1710.typeOf(event.entity));
    }
}
