package com.crystalgraphics.mc.modern.platform.actor;

import com.crystalgraphics.mc.modern.platform.world.LevelEntityQueryModern;
import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.world.actor.CgEntityActors;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
//? if >=1.21.10 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.Arrays;
*///?}

/**
 * Entities entering and leaving the client level ({@link CgNetEntities}) on 1.13.2 to 26.3, and the dispatcher's
 * renderer swapped for {@link ActorRendererModern} where an actor draws the entity; which entity is drawn, for
 * {@link VanillaPosesModern}. Each loader's
 * {@code ActorRenderHook} calls it.
 *
 * <ul>
 *   <li>To 1.21.8 the dispatcher draws an entity in one call, looking its renderer up first: {@link #drawing} at that
 *       call's start, {@link #renderer(Entity, EntityRenderer)} as the lookup returns, {@link #drawn} at its end.</li>
 *   <li>From 1.21.10 it takes every entity's state, then submits each through the renderer its state names:
 *       {@link #extracted} keeps an actor's entity's state, {@code renderer(EntityRenderState, ...)} answers for a
 *       kept one. A pass is its takes then its submits: the world's, or an inventory's preview of one entity.</li>
 *   <li>The render thread's.</li>
 * </ul>
 */
@SuppressWarnings("rawtypes")
public final class ActorsModern {

    private ActorsModern() {
    }

    /** An entity joined the client level. */
    public static void joined(Entity entity) {
        CgNetEntities.entered(entity.getId(), LevelEntityQueryModern.typeOf(entity));
    }

    /** An entity left the client level. */
    public static void left(Entity entity) {
        CgNetEntities.left(entity.getId());
    }

    //? if >=1.21.10 {
    /*/^* The states taken this pass from entities whose actors replace their models; a few, scanned. ^/
    private static EntityRenderState[] marked = new EntityRenderState[8];
    private static int marks;
    /^* A state was submitted since the last was taken: the next taken starts a new pass. ^/
    private static boolean submitting;

    /^* The dispatcher took {@code entity}'s state: kept for its submit when its actor replaces its model. ^/
    public static void extracted(Entity entity, EntityRenderState state) {
        boolean newPass = submitting;
        if (submitting) {
            Arrays.fill(marked, 0, marks, null);
            marks = 0;
            submitting = false;
        }
        VanillaPosesModern.extracted(entity, state, newPass);
        if (!CgEntityActors.replacesModel(entity.getId())) return;
        if (marks == marked.length) marked = Arrays.copyOf(marked, marks * 2);
        marked[marks++] = state;
        HeldItemsModern.extracted(entity, state);
    }

    /^* The renderer a state is submitted through: {@code found}, or its stand-in for a kept state. ^/
    public static EntityRenderer renderer(EntityRenderState state, EntityRenderer found) {
        submitting = true;
        if (found == null) return null;
        for (int i = 0; i < marks; i++) {
            if (marked[i] == state) return ActorRendererModern.of(found);
        }
        return found;
    }
    *///?} else {
    /** The entity the dispatcher is drawing, while its actor replaces its model; null otherwise. */
    private static Entity replacing;

    /** The dispatcher starts drawing {@code entity}. */
    public static void drawing(Entity entity) {
        replacing = CgEntityActors.replacesModel(entity.getId()) ? entity : null;
        VanillaPosesModern.drawing(entity);
    }

    /** The renderer the dispatcher looked up for {@code entity}: {@code found}, or its stand-in while drawing an actor's. */
    public static EntityRenderer renderer(Entity entity, EntityRenderer found) {
        return found != null && entity == replacing ? ActorRendererModern.of(found) : found;
    }

    /** The dispatcher finished drawing an entity. */
    public static void drawn() {
        replacing = null;
        VanillaPosesModern.drawing(null);
    }
    //?}
}
