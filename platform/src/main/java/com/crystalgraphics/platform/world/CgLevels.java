package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;

/**
 * The host's levels: a {@link CgWorldQuery} and a {@link CgEntityQuery} for any of them, the client's included, and the
 * facts core asks of a level or of an object in one (its dimension, a loaded chunk, an entity's id and position). Core's
 * {@code CgWorldQueries} and {@code CgEntityQueries} are built on it; a mod uses those.
 *
 * <pre>{@code
 * CgWorldQuery blocks = CgLevels.world(level);                 // a level, or an entity in one
 * CgEntityQuery here = CgLevels.clientEntities();              // follows the client from level to level
 *
 * // a host, once, on both sides (an integrated server is a server)
 * CgPlatform.provide(CgLevels.HOST, LevelsModern.INSTANCE);
 * // a test or the harness: one level of fakes
 * CgPlatform.provide(CgLevels.HOST, CgLevels.single(new FakeYard(), new FakeMobs()));
 * }</pre>
 *
 * <ul>
 *   <li>A level is the loader's level, or an entity in it, passed as {@code Object}; anything else answers {@code NONE},
 *       null, false or -1.</li>
 *   <li>Each answer is bound to its level and kept while the level lives: hold it no longer than that.</li>
 *   <li>Asked on the thread that owns the level: the server thread for a server level, the render thread for the
 *       client's.</li>
 *   <li>A server level's answers have no tint, biome colour or sprite (0 and false), no local or camera entity, and
 *       poses at the current tick whatever the partial tick.</li>
 *   <li>Without a host (the harness, a test) there are no levels.</li>
 * </ul>
 */
public final class CgLevels {

    /** What a host answers, both sides. One class per era: {@code LevelsModern}, {@code LevelsLegacy}, {@code Levels1710}. */
    public interface Host {

        /** The blocks of {@code level}. The client's level answers with its extras. */
        CgWorldQuery world(Object level);

        /** The entities of {@code level}. */
        CgEntityQuery entities(Object level);

        /** The blocks of the level the client is in, following it; {@code NONE} on a server. */
        CgWorldQuery clientWorld();

        /** The entities of the level the client is in, with the local and camera entity; {@code NONE} on a server. */
        CgEntityQuery clientEntities();

        /** The level the client is in; null on a server, or between levels. */
        Object clientLevel();

        /** The dimension of a level, entity or player as a namespaced id; null for anything else. */
        String dimension(Object handle);

        /** The server's level for a dimension id, {@code handle} being the server or anything on it; null for none. */
        Object level(Object handle, String dimension);

        /** Whether a chunk of a server level is loaded. Never loads it. */
        boolean chunkLoaded(Object level, int chunkX, int chunkZ);

        /** The root directory of the world a level, entity or player belongs to; null without a running server. */
        Path saveDirectory(Object handle);

        /** An entity's network id; -1 for any other object. */
        int entityId(Object entity);

        /** An entity's absolute position into {@code out[0..2]}; false, {@code out} untouched, for a non-entity. */
        boolean position(Object entity, double[] out);

        /** Whether an entity is alive and in its level; false for any other object. */
        boolean alive(Object entity);

        /** No levels. */
        Host NONE = new Single(CgWorldQuery.NONE, CgEntityQuery.NONE);
    }

    // Not built through single(): Host.NONE initialising CgLevels would read HOST's absent value while it is null
    private static final class Single implements Host {
        private final CgWorldQuery world;
        private final CgEntityQuery entities;

        Single(CgWorldQuery world, CgEntityQuery entities) {
            this.world = world;
            this.entities = entities;
        }

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
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:levels", Host.NONE);

    private CgLevels() {
    }

    /** The blocks of {@code level}: the loader's level, or an entity in it. */
    public static CgWorldQuery world(Object level) {
        return host().world(level);
    }

    /** The entities of {@code level}: the loader's level, or an entity in it. */
    public static CgEntityQuery entities(Object level) {
        return host().entities(level);
    }

    /** The blocks of the level the client is in, following it from level to level; {@code NONE} on a server. */
    public static CgWorldQuery clientWorld() {
        return host().clientWorld();
    }

    /** The entities of the level the client is in, with the local and camera entity; {@code NONE} on a server. */
    public static CgEntityQuery clientEntities() {
        return host().clientEntities();
    }

    /** The level the client is in; null on a server, or between levels. */
    public static Object clientLevel() {
        return host().clientLevel();
    }

    /**
     * The dimension a level, an entity or a player is in ({@code "minecraft:the_nether"}); null for any other object.
     * Hosts that number dimensions name them through {@link #dimensionId(int)}.
     */
    public static String dimension(Object handle) {
        return host().dimension(handle);
    }

    /** The server's level for a dimension id, {@code handle} being the server or anything on it; null for none. */
    public static Object level(Object handle, String dimension) {
        return host().level(handle, dimension);
    }

    /** Whether chunk ({@code chunkX}, {@code chunkZ}) of a server level is loaded. Never loads it. */
    public static boolean chunkLoaded(Object level, int chunkX, int chunkZ) {
        return host().chunkLoaded(level, chunkX, chunkZ);
    }

    /** Where the world a level, entity or player belongs to saves; null without a running server. */
    public static Path saveDirectory(Object handle) {
        return host().saveDirectory(handle);
    }

    /** An entity's network id, the one its clients know it by; -1 for any other object. */
    public static int entityId(Object entity) {
        return host().entityId(entity);
    }

    /** An entity's absolute position into {@code out[0..2]}. False, with {@code out} untouched, for a non-entity. */
    public static boolean position(Object entity, double[] out) {
        return host().position(entity, out);
    }

    /** Whether an entity is alive and in its level; false for any other object. */
    public static boolean alive(Object entity) {
        return host().alive(entity);
    }

    /** The id of a numbered dimension: vanilla's three by their modern names, any other {@code "legacy:dim<n>"}. */
    public static String dimensionId(int dimension) {
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
     * CgPlatform.provide(CgLevels.HOST, CgLevels.single(new FakeYard(), new FakeMobs()));
     * }</pre>
     */
    public static Host single(CgWorldQuery world, CgEntityQuery entities) {
        return new Single(world, entities);
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
