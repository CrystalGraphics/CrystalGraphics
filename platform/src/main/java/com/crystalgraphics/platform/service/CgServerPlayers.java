package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Answers the server's questions about its players: which dimension a level, entity or player is in, where an entity
 * is, whose client has a chunk or an entity loaded, and where the world is saved. What {@code CgAudience} composes its audiences from; a mod
 * reaches it through those rather than here.
 *
 * <pre>{@code
 * CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
 * String dim = players.dimension(level);                       // "minecraft:overworld"
 * players.trackingEntity(entity, id -> send(id));              // every client that has it loaded
 *
 * // a host, once, at install, on both sides (an integrated server is a server)
 * CgPlatform.provide(CgServerPlayers.SERVICE, new ServerPlayersModern());
 * }</pre>
 *
 * <ul>
 *   <li>Handles are the loader's own level, entity and player objects, passed as {@code Object}; a handle of the
 *       wrong kind answers null, false or nothing.</li>
 *   <li>Players are named by profile id, the key every connection is opened under.</li>
 *   <li>Server thread only.</li>
 *   <li>{@link #NONE} answers nothing: the harness, a client with no integrated server.</li>
 * </ul>
 */
public interface CgServerPlayers {

    /** No players: what anything without a server reads. */
    CgServerPlayers NONE = new CgServerPlayers() {
        @Override public String dimension(Object handle) { return null; }
        @Override public boolean position(Object entity, double[] out) { return false; }
        @Override public UUID playerId(Object entity) { return null; }
        @Override public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) { }
        @Override public void trackingEntity(Object entity, Consumer<UUID> out) { }
        @Override public Path saveDirectory(Object handle) { return null; }
    };

    CgService<CgServerPlayers> SERVICE = CgService.of("crystalgraphics:server_players", NONE);

    /**
     * The dimension a level, an entity or a player is in, as a namespaced id ({@code "minecraft:the_nether"}); null for
     * any other object. Hosts that number dimensions name them through {@link #dimensionId(int)}.
     */
    String dimension(Object handle);

    /** An entity's absolute position into {@code out[0..2]}. False, with {@code out} untouched, for a non-entity. */
    boolean position(Object entity, double[] out);

    /** A player entity's profile id; null for any other object. */
    UUID playerId(Object entity);

    /** Hands {@code out} each player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
    void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out);

    /** Hands {@code out} each player whose client has {@code entity} loaded, never the entity itself. */
    void trackingEntity(Object entity, Consumer<UUID> out);

    /**
     * The root directory of the world a level, entity or player belongs to, where anything saved with it goes; null
     * without a running server.
     */
    Path saveDirectory(Object handle);

    /** The id of a numbered dimension: vanilla's three by their modern names, any other {@code "legacy:dim<n>"}. */
    static String dimensionId(int dimension) {
        switch (dimension) {
            case 0: return "minecraft:overworld";
            case -1: return "minecraft:the_nether";
            case 1: return "minecraft:the_end";
            default: return "legacy:dim" + dimension;
        }
    }
}
