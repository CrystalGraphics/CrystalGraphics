package com.crystalgraphics.mc.legacy.platform.actor;

import com.crystalgraphics.mc.legacy.platform.world.LevelEntityQueryLegacy;
import com.crystalgraphics.world.CgEntityLifecycle;
import com.crystalgraphics.world.actor.CgEntityActors;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * {@link CgEntityActors} on Forge 1.8.9 to 1.12.2: entities joining the client world, from Forge's join event, and the
 * render manager's renderer swapped for {@link ActorRenderLegacy} while it draws an entity an actor replaces. Forge
 * has no leave event here: an actor goes the frame its entity is gone ({@code CgEntityActors}).
 */
public final class ActorsLegacy {

    /** The entity the render manager is drawing, while its actor replaces its model; null otherwise. */
    private static Entity replacing;

    private ActorsLegacy() {
    }

    /** Forge's bus, client only: {@code MinecraftForge.EVENT_BUS.register(new ActorsLegacy.Joins())}. */
    public static final class Joins {
        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            //? if >=1.10 {
            World world = event.getWorld();
            Entity entity = event.getEntity();
            //?} else {
            /*World world = event.world;
            Entity entity = event.entity;
            *///?}
            if (world.isRemote) CgEntityLifecycle.entered(entity.getEntityId(), LevelEntityQueryLegacy.typeOf(entity));
        }
    }

    /** {@code ActorRenderHook}: the render manager starts drawing {@code entity}. */
    public static void drawing(Entity entity) {
        replacing = CgEntityActors.replacesModel(entity.getEntityId()) ? entity : null;
    }

    /** {@code ActorRenderHook}: the renderer looked up for {@code entity}, or its stand-in while drawing an actor's. */
    public static Render<?> renderer(Entity entity, Render<?> found) {
        return found != null && entity == replacing ? ActorRenderLegacy.of(found) : found;
    }

    /** {@code ActorRenderHook}: the render manager finished drawing an entity. */
    public static void drawn() {
        replacing = null;
    }
}
