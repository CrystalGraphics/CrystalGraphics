package com.crystalgraphics.net;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Which players a server-side send reaches. Each answers profile ids; {@link CgMessage#send} turns them into
 * connections, so a player without one is passed over.
 *
 * <pre>{@code
 * PLAY.send(CgAudience.all(), play);
 * PLAY.send(CgAudience.player(playerUuid), play);
 * PLAY.send(CgAudience.near(level, x, y, z, 96), play);                  // a blast: whoever can see it
 * PLAY.send(CgAudience.tracking(entity), play);                          // whoever has the entity loaded
 * PLAY.send(CgAudience.except(CgAudience.trackingAndSelf(player), id), play);  // all but the one who predicted it
 * }</pre>
 *
 * <ul>
 *   <li>A level, entity or player is the loader's own object, passed as {@code Object}. Where a dimension is asked
 *       for, its id string ({@code "minecraft:overworld"}) serves as well.</li>
 *   <li>Evaluated at send time, on the server thread: build one once and send through it as often as needed.</li>
 *   <li>Everything but {@link #player}, {@link #all} and {@link #except} asks the host's players ({@link CgPlayers})
 *       and levels; where no host fills them they reach nobody.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAudience {

    /** Hands each player's profile id to {@code out}, once. */
    void forEach(Consumer<UUID> out);

    /** One player. */
    static CgAudience player(UUID id) {
        Objects.requireNonNull(id, "id");
        return out -> out.accept(id);
    }

    /** Every player with a connection. */
    static CgAudience all() {
        return CgNetwork::forEachPlayer;
    }

    /** Every player in a dimension. */
    static CgAudience dimension(Object levelOrId) {
        Objects.requireNonNull(levelOrId, "levelOrId");
        return out -> {
            String dimension = dimensionOf(levelOrId);
            if (dimension == null) return;
            CgNetwork.forEachPeer(peer -> {
                Object player = peer.player();
                if (player != null && dimension.equals(CgWorldQueries.dimension(player))) out.accept(peer.id());
            });
        };
    }

    /** Every player in a dimension within {@code radius} blocks of a point. */
    static CgAudience near(Object levelOrId, double x, double y, double z, double radius) {
        Objects.requireNonNull(levelOrId, "levelOrId");
        double limit = radius * radius;
        return out -> {
            String dimension = dimensionOf(levelOrId);
            if (dimension == null) return;
            double[] at = new double[3];
            CgNetwork.forEachPeer(peer -> {
                Object player = peer.player();
                if (player == null || !dimension.equals(CgWorldQueries.dimension(player)) || !CgEntityQueries.position(player, at)) {
                    return;
                }
                double dx = at[0] - x, dy = at[1] - y, dz = at[2] - z;
                if (dx * dx + dy * dy + dz * dz <= limit) out.accept(peer.id());
            });
        };
    }

    /** Every player whose client has {@code entity} loaded, never the entity itself. */
    static CgAudience tracking(Object entity) {
        Objects.requireNonNull(entity, "entity");
        return out -> CgPlayers.trackingEntity(entity, out);
    }

    /** {@link #tracking(Object)}, and the entity too when it is a player. */
    static CgAudience trackingAndSelf(Object entity) {
        Objects.requireNonNull(entity, "entity");
        return out -> {
            UUID self = CgPlayers.idOf(entity);
            if (self != null) out.accept(self);
            CgPlayers.trackingEntity(entity, out);
        };
    }

    /** Every player whose client has chunk ({@code chunkX}, {@code chunkZ}) of {@code level} loaded. */
    static CgAudience tracking(Object level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level");
        return out -> CgPlayers.trackingChunk(level, chunkX, chunkZ, out);
    }

    /** {@code audience} without one player: the one who caused it and has already shown it. */
    static CgAudience except(CgAudience audience, UUID excluded) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(excluded, "excluded");
        return out -> audience.forEach(id -> {
            if (!excluded.equals(id)) out.accept(id);
        });
    }

    private static String dimensionOf(Object levelOrId) {
        return levelOrId instanceof String ? (String) levelOrId : CgWorldQueries.dimension(levelOrId);
    }
}
