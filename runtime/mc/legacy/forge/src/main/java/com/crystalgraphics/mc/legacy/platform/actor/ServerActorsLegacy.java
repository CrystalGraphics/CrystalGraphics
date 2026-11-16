package com.crystalgraphics.mc.legacy.platform.actor;

import com.crystalgraphics.mc.legacy.platform.world.LevelEntityQueryLegacy;
import com.crystalgraphics.net.object.CgNetEntities;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Entities joining a server's levels on Forge 1.8.9 to 1.12.2, for what the engine keeps on them there ({@link CgNetEntities}). Both
 * sides, so it names no client class: {@code MinecraftForge.EVENT_BUS.register(new ServerActorsLegacy())}.
 *
 * <ul>
 *   <li>Forge has no leave event here: what the engine kept on an entity that is gone goes at its next tick.</li>
 * </ul>
 */
public final class ServerActorsLegacy {

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        //? if >=1.10 {
        World world = event.getWorld();
        Entity entity = event.getEntity();
        //?} else {
        /*World world = event.world;
        Entity entity = event.entity;
        *///?}
        if (!world.isRemote) CgNetEntities.enteredServer(entity, LevelEntityQueryLegacy.typeOf(entity));
    }
}
