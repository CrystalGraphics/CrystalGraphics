package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The host's players, on both sides: who a player entity is, the server's player for a profile id, this client's own
 * player, and whose client has a chunk or an entity loaded. What {@code CgAudience}, networked objects and
 * {@code com.crystalgraphics.world.CgPlayers} are made from; a mod reaches it through those.
 *
 * <pre>{@code
 * // core, through the facade
 * UUID who = CgPlayers.idOf(entity);                           // players.playerId(entity)
 * ServerPlayer p = CgPlayers.player(who);                      // players.player(who)
 * CgPlayers.trackingEntity(entity, id -> send(id));            // every client that has it loaded
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgPlayerQuery.SERVICE, new PlayerQueryModern());
 * }</pre>
 *
 * <ul>
 *   <li>Players are named by profile id, the key every connection is opened under. Host objects cross as
 *       {@code Object}; the {@code <V>} getters cast at the caller's site.</li>
 *   <li>The server's questions on the server thread; {@link #localPlayer} on the render thread.</li>
 *   <li>{@link #NONE} has no players: the harness, a client with no integrated server for the server's questions.</li>
 * </ul>
 */
public interface CgPlayerQuery {

    /** No players: what anything without a host reads. */
    CgPlayerQuery NONE = new CgPlayerQuery() {
        @Override public UUID playerId(Object entity) { return null; }
        @Override public <V> V player(UUID id) { return null; }
        @Override public <V> V player(int entityId) { return null; }
        @Override public <V> V localPlayer() { return null; }
        @Override public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) { }
        @Override public void trackingEntity(Object entity, Consumer<UUID> out) { }
    };

    CgService<CgPlayerQuery> SERVICE = CgService.of("crystalgraphics:players", NONE);

    /** A player entity's profile id; null for any other object. */
    UUID playerId(Object entity);

    /** The server's online player with this profile id ({@code ServerPlayer}, {@code EntityPlayerMP}); null for none. */
    <V> V player(UUID id);

    /** The server's online player whose entity has this network id; null for none, or on a client. */
    <V> V player(int entityId);

    /** This client's own player ({@code LocalPlayer}, {@code EntityPlayerSP}); null on a server, or with none. */
    <V> V localPlayer();

    /** Hands {@code out} each player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
    void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out);

    /** Hands {@code out} each player whose client has {@code entity} loaded, never the entity itself. */
    void trackingEntity(Object entity, Consumer<UUID> out);
}
