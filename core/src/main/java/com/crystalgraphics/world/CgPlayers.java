package com.crystalgraphics.world;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgPlayerQuery;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The host's players, by profile id: who a player entity is, the server's player for an id, this client's own player,
 * and whose client has a chunk or an entity loaded. The only reader of {@link CgPlayerQuery}.
 *
 * <pre>{@code
 * UUID who = CgPlayers.idOf(entity);                         // null for anything but a player
 * ServerPlayer p = CgPlayers.player(who);                    // the server's player, or null when offline
 * ServerPlayer caster = CgPlayers.player(entityId);          // by network id: the server's, or the client level's
 * LocalPlayer me = CgPlayers.local();                        // this client's, or null
 *
 * CgPlayers.trackingEntity(boss, id -> CgNetwork.forPlayer(id).send(BAR, health));
 * }</pre>
 *
 * <ul>
 *   <li>The server's questions on the server thread, {@link #local()} on the render thread.</li>
 *   <li>The {@code <V>} getters cast to the type the caller names; a wrong one throws {@code ClassCastException} there.</li>
 * </ul>
 */
public final class CgPlayers {

    private CgPlayers() {
    }

    /** A player entity's profile id; null for any other object. */
    public static UUID idOf(Object entity) {
        return players().playerId(entity);
    }

    /** The server's online player with this profile id ({@code ServerPlayer}, {@code EntityPlayerMP}); null for none. */
    public static <V> V player(UUID id) {
        return players().player(id);
    }

    /**
     * The player whose entity has this network id, cast where it is asked: on the server side ({@link CgNetwork#side()})
     * the server's online player, on the client the client level's; null for none, or an entity that is not a player.
     *
     * <pre>{@code
     * ServerPlayer caster = CgPlayers.player(hit.entityId());   // server
     * Player seen = CgPlayers.player(obj.entityId());           // a client, the same call
     * }</pre>
     */
    public static <V> V player(int entityId) {
        if (CgNetwork.side() == CgNetwork.Side.SERVER) return players().player(entityId);
        CgEntityQuery client = CgEntityQueries.client();
        return (client.flags(entityId) & CgEntityQuery.PLAYER) != 0 ? client.entity(entityId) : null;
    }

    /** This client's own player ({@code LocalPlayer}, {@code EntityPlayerSP}); null on a server, or with none. */
    public static <V> V local() {
        return players().localPlayer();
    }

    /** Hands {@code out} each player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
    public static void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        players().trackingChunk(level, chunkX, chunkZ, out);
    }

    /** Hands {@code out} each player whose client has {@code entity} loaded, never the entity itself. */
    public static void trackingEntity(Object entity, Consumer<UUID> out) {
        players().trackingEntity(entity, out);
    }

    private static CgPlayerQuery players() {
        return CgPlatform.get(CgPlayerQuery.SERVICE);
    }
}
