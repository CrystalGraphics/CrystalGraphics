package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * {@link CgWorldQuery} and {@link CgEntityQuery} over any level the host has, the server's included: what an effect's
 * authoritative half asks on the server tick (where a beam stops, what it strikes). The {@code SERVICE}s of those two
 * answer for the client's level alone.
 *
 * <pre>{@code
 * CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
 * CgWorldQuery world = levels.world(caster);              // the caster's level: an entity, or a level itself
 * CgEntityQuery entities = levels.entities(caster);
 * CgWorldQueries.raycastAll(world, entities, x, y, z, dx, dy, dz, 64, 0.5, casterId, hit);
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgLevelQueries.SERVICE, new LevelQueriesModern());
 * }</pre>
 *
 * <ul>
 *   <li>Each answer is bound to its level and kept while the level lives: hold it no longer than that.</li>
 *   <li>Asked on the thread that owns the level: the server thread for a server level.</li>
 *   <li>A server level's answers have no tint, biome colour or sprite (0 and false), no local or camera entity, and
 *       poses at the current tick whatever the partial tick.</li>
 *   <li>{@link #NONE}, and anything not a level or an entity in one, answers {@code NONE}.</li>
 * </ul>
 */
public interface CgLevelQueries {

    CgLevelQueries NONE = new CgLevelQueries() {
        @Override public CgWorldQuery world(Object level) { return CgWorldQuery.NONE; }
        @Override public CgEntityQuery entities(Object level) { return CgEntityQuery.NONE; }
    };

    CgService<CgLevelQueries> SERVICE = CgService.of("crystalgraphics:level_queries", NONE);

    /** The blocks of {@code level}: the loader's level, or an entity in it. */
    CgWorldQuery world(Object level);

    /** The entities of {@code level}: the loader's level, or an entity in it. */
    CgEntityQuery entities(Object level);
}
