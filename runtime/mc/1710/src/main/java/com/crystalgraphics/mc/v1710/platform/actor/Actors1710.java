package com.crystalgraphics.mc.v1710.platform.actor;

import com.crystalgraphics.mc.v1710.platform.world.LevelEntityQuery1710;
import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.world.actor.CgEntityActors;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

/**
 * {@link CgEntityActors} on 1.7.10: entities joining the client world, from Forge's join event, and the render
 * manager's renderer swapped for {@link ActorRender1710} while it draws an entity an actor replaces. Forge has no leave
 * event here: an actor goes the frame its entity is gone ({@code CgEntityActors}).
 */
public final class Actors1710 {

    /** The entity the render manager is drawing, while its actor replaces its model; null otherwise. */
    private static Entity replacing;

    private Actors1710() {
    }

    /** Forge's bus, client only: {@code MinecraftForge.EVENT_BUS.register(new Actors1710.Joins())}. */
    public static final class Joins {
        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            if (event.world.isRemote) {
                CgNetEntities.entered(event.entity.getEntityId(), LevelEntityQuery1710.typeOf(event.entity));
            }
        }
    }

    /** {@code ActorRenderHook}: the render manager starts drawing {@code entity}. */
    public static void drawing(Entity entity) {
        replacing = CgEntityActors.replacesModel(entity.getEntityId()) ? entity : null;
    }

    /** {@code ActorRenderHook}: the renderer looked up for {@code entity}, or its stand-in while drawing an actor's. */
    public static Render renderer(Entity entity, Render found) {
        return found != null && entity == replacing ? ActorRender1710.of(found) : found;
    }

    /** {@code ActorRenderHook}: the render manager finished drawing an entity. */
    public static void drawn() {
        replacing = null;
    }
}
