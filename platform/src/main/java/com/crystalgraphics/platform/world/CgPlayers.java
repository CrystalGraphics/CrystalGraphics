package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The host's players, by profile id: who a player entity is, the server's player for an id, this client's own player,
 * and whose client has a chunk or an entity loaded. What {@code CgAudience} and networked objects are made from.
 *
 * <pre>{@code
 * UUID who = CgPlayers.idOf(entity);                         // null for anything but a player
 * ServerPlayer p = CgPlayers.player(who);                    // the server's player, or null when offline
 * ServerPlayer caster = CgPlayers.player(hit.entityId());    // by network id, on the server
 * LocalPlayer me = CgPlayers.local();                        // this client's, or null
 *
 * CgPlayers.trackingEntity(boss, id -> CgNetwork.forPlayer(id).send(BAR, health));
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgPlayers.HOST, PlayersModern.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>The server's questions on the server thread, {@link #local()} on the render thread.</li>
 *   <li>The {@code <V>} getters cast to the type the caller names; a wrong one throws {@code ClassCastException} there.</li>
 *   <li>{@link #player(int)} is the server's: on a client, a player in view is
 *       {@code CgEntityQueries.client().entity(id)}.</li>
 *   <li>Without a host (the harness, a client with no integrated server) there are no players.</li>
 * </ul>
 */
public final class CgPlayers {

    /** What a host answers, both sides. One class per era: {@code PlayersModern}, {@code PlayersLegacy}, {@code Players1710}. */
    public interface Host {

        /** A player entity's profile id; null for any other object. */
        UUID playerId(Object entity);

        /** The server's online player with this profile id; null for none. */
        <V> V player(UUID id);

        /** The server's online player whose entity has this network id; null for none, or on a client. */
        <V> V player(int entityId);

        /** This client's own player; null on a server, or with none. */
        <V> V localPlayer();

        /** Hands {@code out} each player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
        void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out);

        /** Hands {@code out} each player whose client has {@code entity} loaded, never the entity itself. */
        void trackingEntity(Object entity, Consumer<UUID> out);

        /** Whether the online player with this name is an operator; false for none online. */
        default boolean isOperator(String name) {
            return false;
        }

        /** Whether this name is the single-player world's owner; false on a dedicated server. */
        default boolean isOwner(String name) {
            return false;
        }

        /** No players. */
        Host NONE = new Host() {
            @Override public UUID playerId(Object entity) { return null; }
            @Override public <V> V player(UUID id) { return null; }
            @Override public <V> V player(int entityId) { return null; }
            @Override public <V> V localPlayer() { return null; }
            @Override public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) { }
            @Override public void trackingEntity(Object entity, Consumer<UUID> out) { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:players", Host.NONE);

    private CgPlayers() {
    }

    /** A player entity's profile id; null for any other object. */
    public static UUID idOf(Object entity) {
        return host().playerId(entity);
    }

    /** The server's online player with this profile id ({@code ServerPlayer}, {@code EntityPlayerMP}); null for none. */
    public static <V> V player(UUID id) {
        return host().player(id);
    }

    /** The server's online player whose entity has this network id; null for none, or on a client. */
    public static <V> V player(int entityId) {
        return host().player(entityId);
    }

    /** This client's own player ({@code LocalPlayer}, {@code EntityPlayerSP}); null on a server, or with none. */
    public static <V> V local() {
        return host().localPlayer();
    }

    /** Hands {@code out} each player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
    public static void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        host().trackingChunk(level, chunkX, chunkZ, out);
    }

    /** Hands {@code out} each player whose client has {@code entity} loaded, never the entity itself. */
    public static void trackingEntity(Object entity, Consumer<UUID> out) {
        host().trackingEntity(entity, out);
    }

    /** Whether the online player with this name may use commands as an operator. Server thread. */
    public static boolean isOperator(String name) {
        return host().isOperator(name);
    }

    /** Whether this name is the single-player world's owner; false on a dedicated server. Server thread. */
    public static boolean isOwner(String name) {
        return host().isOwner(name);
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
