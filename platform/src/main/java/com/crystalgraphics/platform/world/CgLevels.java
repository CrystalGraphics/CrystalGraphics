package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;

/**
 * The host's levels: a {@link CgWorldQuery} and a {@link CgEntityQuery} for any of them, the client's included, and the
 * facts core asks of a level or of an object in one (its dimension, a loaded chunk, an entity's id and position). Core
 * reads it through {@code com.crystalgraphics.world.CgWorldQueries} and {@code CgEntityQueries}; a mod never names it.
 *
 * <pre>{@code
 * // core: the facades are the only readers
 * CgWorldQuery blocks = CgWorldQueries.of(level);              // levels.world(level)
 * CgEntityQuery here = CgEntityQueries.client();               // levels.clientEntities()
 *
 * // a host, once, on both sides (an integrated server is a server)
 * CgPlatform.provide(CgLevels.SERVICE, LevelsModern.INSTANCE);
 * // and, on a client, its client half: the queries that follow the client's level
 * LevelsModern.INSTANCE.client(new WorldQueryModern(), new EntityQueryModern(), () -> Minecraft.getInstance().level);
 * }</pre>
 *
 * <ul>
 *   <li>A level is the loader's level, or an entity in it, passed as {@code Object}; anything else answers {@code NONE},
 *       null, false or -1.</li>
 *   <li>Each answer is bound to its level and kept while the level lives: hold it no longer than that. The client's
 *       queries follow whatever level the client is in.</li>
 *   <li>Asked on the thread that owns the level: the server thread for a server level, the render thread for the
 *       client's.</li>
 *   <li>A server level's answers have no tint, biome colour or sprite (0 and false), no local or camera entity, and
 *       poses at the current tick whatever the partial tick.</li>
 *   <li>{@link #NONE} has no levels: the harness, a test. {@link #single} answers one level of fakes.</li>
 * </ul>
 */
public interface CgLevels {

    /** No levels: what anything without a host reads. */
    CgLevels NONE = single(CgWorldQuery.NONE, CgEntityQuery.NONE);

    CgService<CgLevels> SERVICE = CgService.of("crystalgraphics:levels", NONE);

    /** The blocks of {@code level}: the loader's level, or an entity in it. The client's level answers with its extras. */
    CgWorldQuery world(Object level);

    /** The entities of {@code level}: the loader's level, or an entity in it. */
    CgEntityQuery entities(Object level);

    /** The blocks of the level the client is in, following it from level to level; {@code NONE} on a server. */
    CgWorldQuery clientWorld();

    /** The entities of the level the client is in, with the local and camera entity; {@code NONE} on a server. */
    CgEntityQuery clientEntities();

    /** The level the client is in; null on a server, or between levels. */
    Object clientLevel();

    /**
     * The dimension a level, an entity or a player is in, as a namespaced id ({@code "minecraft:the_nether"}); null for
     * any other object. Hosts that number dimensions name them through {@link #dimensionId(int)}.
     */
    String dimension(Object handle);

    /**
     * The server's level for a dimension id, {@code handle} being the server or anything on it; null when it has no
     * such level, or without a running server.
     */
    Object level(Object handle, String dimension);

    /** Whether chunk ({@code chunkX}, {@code chunkZ}) of a server level is loaded. Never loads it. */
    boolean chunkLoaded(Object level, int chunkX, int chunkZ);

    /**
     * The root directory of the world a level, entity or player belongs to, where anything saved with it goes; null
     * without a running server.
     */
    Path saveDirectory(Object handle);

    /** An entity's network id, the one its clients know it by; -1 for any other object. */
    int entityId(Object entity);

    /** An entity's absolute position into {@code out[0..2]}. False, with {@code out} untouched, for a non-entity. */
    boolean position(Object entity, double[] out);

    /** Whether an entity is alive and in its level; false for any other object. */
    boolean alive(Object entity);

    /** The id of a numbered dimension: vanilla's three by their modern names, any other {@code "legacy:dim<n>"}. */
    static String dimensionId(int dimension) {
        switch (dimension) {
            case 0: return "minecraft:overworld";
            case -1: return "minecraft:the_nether";
            case 1: return "minecraft:the_end";
            default: return "legacy:dim" + dimension;
        }
    }

    /**
     * Levels that are all one level: every question about blocks or entities answers {@code world} and
     * {@code entities}, the client's included, and every other answers nothing. What the harness and a test provide.
     *
     * <pre>{@code
     * CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(new FakeYard(), new FakeMobs()));
     * }</pre>
     */
    static CgLevels single(CgWorldQuery world, CgEntityQuery entities) {
        return new CgLevels() {
            @Override public CgWorldQuery world(Object level) { return world; }
            @Override public CgEntityQuery entities(Object level) { return entities; }
            @Override public CgWorldQuery clientWorld() { return world; }
            @Override public CgEntityQuery clientEntities() { return entities; }
            @Override public Object clientLevel() { return null; }
            @Override public String dimension(Object handle) { return null; }
            @Override public Object level(Object handle, String dimension) { return null; }
            @Override public boolean chunkLoaded(Object level, int chunkX, int chunkZ) { return false; }
            @Override public Path saveDirectory(Object handle) { return null; }
            @Override public int entityId(Object entity) { return -1; }
            @Override public boolean position(Object entity, double[] out) { return false; }
            @Override public boolean alive(Object entity) { return false; }
        };
    }
}
