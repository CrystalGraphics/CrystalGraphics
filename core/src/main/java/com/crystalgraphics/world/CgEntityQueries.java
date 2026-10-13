package com.crystalgraphics.world;

import com.crystalgraphics.platform.world.CgEntityQuery;

/**
 * The host's entities: the {@link CgEntityQuery} of any level, the client's or a server's, and what core asks of one
 * entity object (its network id, where it is, whether it is alive).
 *
 * <pre>{@code
 * CgEntityQuery here = CgEntityQueries.client();             // the client's level, with the local and camera entity
 * CgEntityQuery there = CgEntityQueries.of(caster);          // a level, or an entity in one: a server's too
 * Entity target = there.entity(hit.entityId());              // the host's entity, on the level's thread
 *
 * int id = CgEntityQueries.idOf(entity);                     // -1 for a non-entity
 * double[] at = new double[3];
 * if (CgEntityQueries.position(entity, at)) aimAt(at[0], at[1], at[2]);
 * }</pre>
 *
 * <ul>
 *   <li>A query is asked on its level's thread, and held while its level lives, not across levels.</li>
 *   <li>{@code NONE} wherever the host has no such level: a client query on a server, anything in the harness.</li>
 * </ul>
 */
public final class CgEntityQueries {

    private CgEntityQueries() {
    }

    /** The entities of {@code level}: the loader's level, or an entity in it. {@code NONE} for anything else. */
    public static CgEntityQuery of(Object level) {
        return CgWorldQueries.levels().entities(level);
    }

    /** The entities of the level the client is in, following it from level to level; {@code NONE} on a server. */
    public static CgEntityQuery client() {
        return CgWorldQueries.levels().clientEntities();
    }

    /** An entity's network id, the one its clients know it by; -1 for any other object. */
    public static int idOf(Object entity) {
        return CgWorldQueries.levels().entityId(entity);
    }

    /** An entity's absolute position into {@code out[0..2]}. False, with {@code out} untouched, for a non-entity. */
    public static boolean position(Object entity, double[] out) {
        return CgWorldQueries.levels().position(entity, out);
    }

    /** Whether an entity is alive and in its level; false for any other object. */
    public static boolean alive(Object entity) {
        return CgWorldQueries.levels().alive(entity);
    }
}
