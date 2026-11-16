package com.crystalgraphics.world;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetObjects;
import com.crystalgraphics.world.actor.CgEntityActors;

/**
 * Where a host says an entity came or went, on either side: once, for everything the engine keeps on entities. Its
 * properties ({@code CgNetType.Builder.onEntities}) are made first, so its actor finds them at its spawn.
 *
 * <pre>{@code
 * // a host's client, its level's entity join and leave events
 * CgEntityLifecycle.entered(entity.getId(), "minecraft:zombie");
 * CgEntityLifecycle.left(entity.getId());
 *
 * // its server
 * CgEntityLifecycle.enteredServer(entity, "minecraft:zombie");
 * CgEntityLifecycle.leftServer(entity);
 * }</pre>
 *
 * <ul>
 *   <li>A host with no leave event (Forge before 1.13) leaves {@code left} uncalled: an entity its host no longer
 *       answers for is dropped on its own.</li>
 *   <li>A null type is an entity class the host's registry does not hold: nothing is put on it.</li>
 * </ul>
 */
public final class CgEntityLifecycle {

    private CgEntityLifecycle() {
    }

    /** Entity {@code id} of {@code type} joined the client's level. The client thread. */
    public static void entered(int id, @Nullable String type) {
        if (type != null) CgNetObjects.entityEntered(id, type);
        CgEntityActors.entered(id, type);
    }

    /** Entity {@code id} left the client's level. */
    public static void left(int id) {
        CgEntityActors.left(id);
        CgNetObjects.entityLeft(id);
    }

    /** {@code entity}, the host's own object, of {@code type} joined one of the server's levels. The server thread. */
    public static void enteredServer(Object entity, @Nullable String type) {
        if (type != null) CgNetObjects.entityEntered(entity, type);
        CgEntityActors.enteredServer(entity, type);
    }

    /** {@code entity} left the server's level. */
    public static void leftServer(Object entity) {
        CgEntityActors.leftServer(entity);
        CgNetObjects.entityLeft(entity);
    }
}
