package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Where a networked object lives, which decides who sees it by default and what ends it.
 *
 * <pre>{@code
 * TURRET.spawn(CgAnchor.at(level, x, y, z)).start();      // players within the type's range, in that dimension
 * AURA.spawn(CgAnchor.on(zombie)).start();                // players tracking the zombie; ends with it
 * QUEST.spawn(CgAnchor.player(player)).start();           // that player alone, wherever they go
 * WARD.spawn(CgAnchor.chunk(level, cx, cz)).start();      // players tracking the chunk; dormant while it is unloaded
 * STORM.spawn(CgAnchor.dimension(level)).start();         // everyone in the dimension
 * EVENT.spawn(CgAnchor.global()).start();                 // every connected player
 * }</pre>
 *
 * <table>
 *   <caption>What each anchor means</caption>
 *   <tr><th>Anchor</th><th>Seen by</th><th>Ends</th></tr>
 *   <tr><td>{@code at}</td><td>players within the type's range</td><td>{@code despawn}, its lifetime</td></tr>
 *   <tr><td>{@code on}</td><td>players tracking the entity, and the entity if a player</td><td>the entity dying,
 *       leaving its level or changing dimension</td></tr>
 *   <tr><td>{@code player}</td><td>that player; also its trackers with {@code visibleToTrackers()}</td><td>the player
 *       leaving, unless persisted (then dormant until they return); dying, unless {@code copyOnDeath()}</td></tr>
 *   <tr><td>{@code chunk}</td><td>players tracking the chunk</td><td>{@code despawn}; dormant, not ended, while the
 *       chunk is unloaded</td></tr>
 *   <tr><td>{@code dimension}, {@code global}</td><td>everyone there</td><td>{@code despawn}</td></tr>
 * </table>
 *
 * <ul>
 *   <li>A level, an entity and a player are the loader's own objects; a dimension id ({@code "minecraft:overworld"})
 *       serves for a level where a point or a whole dimension is meant.</li>
 *   <li>A point anchor's object moves with {@link CgNetObject#moveTo}; an entity's or a player's follows it, and its
 *       clients find it by {@link CgNetObject#entityId()}.</li>
 *   <li>An entity-anchored object is never persisted: a persisted type refuses one.</li>
 * </ul>
 *
 * <p>A client {@link CgNetType#predict predicting} an object names what it can see; the server resolves it in the
 * predicting player's dimension:</p>
 *
 * <pre>{@code
 * HIT_SPARK.predict(CgAnchor.at(x, y, z)).fire();
 * SLASH.predict(CgAnchor.onEntity(target.getId())).start();
 * }</pre>
 */
public final class CgAnchor {

    /** What an object is anchored to. Persisted by ordinal: append only. */
    public enum Kind {
        /** A point in a dimension: seen within the type's range. */
        POINT,
        /** A whole dimension. */
        DIMENSION,
        /** Every connected player. */
        GLOBAL,
        /** An entity, followed until it ends. */
        ENTITY,
        /** A player by profile id, across deaths and dimensions. */
        PLAYER,
        /** A chunk of a level. */
        CHUNK
    }

    private static final CgAnchor GLOBAL = new CgAnchor(Kind.GLOBAL, null, 0, 0, 0, null, null, 0, 0, -1);

    final Kind kind;
    @Nullable
    final String dimension;
    final double x, y, z;
    /** The entity of {@code on}, or the level a point, dimension or chunk was given: a level, or an entity's. */
    @Nullable
    final Object handle;
    /** The player of {@code player}. */
    @Nullable
    final UUID player;
    final int chunkX, chunkZ;
    /** The network id of {@code on}'s or {@code onEntity}'s entity. */
    final int entityId;

    private CgAnchor(Kind kind, @Nullable String dimension, double x, double y, double z, @Nullable Object handle,
                     @Nullable UUID player, int chunkX, int chunkZ, int entityId) {
        this.kind = kind;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.handle = handle;
        this.player = player;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.entityId = entityId;
    }

    /** A point in {@code levelOrDimension}: a level, an entity in it, or the dimension's id. */
    public static CgAnchor at(Object levelOrDimension, double x, double y, double z) {
        String dimension = dimensionOf(levelOrDimension);
        return new CgAnchor(Kind.POINT, dimension, x, y, z, levelOf(levelOrDimension, dimension), null, 0, 0, -1);
    }

    /** A point in the predicting player's dimension: for {@link CgNetType#predict}, on a client. */
    public static CgAnchor at(double x, double y, double z) {
        return new CgAnchor(Kind.POINT, null, x, y, z, null, null, 0, 0, -1);
    }

    /** All of {@code levelOrDimension}. */
    public static CgAnchor dimension(Object levelOrDimension) {
        String dimension = dimensionOf(levelOrDimension);
        return new CgAnchor(Kind.DIMENSION, dimension, 0, 0, 0, levelOf(levelOrDimension, dimension), null, 0, 0, -1);
    }

    public static CgAnchor global() {
        return GLOBAL;
    }

    /** On {@code entity}, a loader entity in a server level. */
    public static CgAnchor on(Object entity) {
        int id = CgEntityQueries.idOf(Objects.requireNonNull(entity, "entity"));
        if (id < 0) throw new IllegalArgumentException("not an entity: " + entity);
        double[] at = new double[3];
        CgEntityQueries.position(entity, at);
        return new CgAnchor(Kind.ENTITY, dimensionOf(entity), at[0], at[1], at[2], entity, null, 0, 0, id);
    }

    /** On the entity a client knows by network id, in the predicting player's dimension: for {@link CgNetType#predict}. */
    public static CgAnchor onEntity(int networkId) {
        return new CgAnchor(Kind.ENTITY, null, 0, 0, 0, null, null, 0, 0, networkId);
    }

    /** On a player: a player entity, or a profile id, online or not. */
    public static CgAnchor player(Object playerOrId) {
        UUID id = playerOrId instanceof UUID ? (UUID) playerOrId
                : CgPlayers.idOf(Objects.requireNonNull(playerOrId, "playerOrId"));
        if (id == null) throw new IllegalArgumentException("not a player: " + playerOrId);
        return new CgAnchor(Kind.PLAYER, null, 0, 0, 0, null, id, 0, 0, -1);
    }

    /** Chunk ({@code chunkX}, {@code chunkZ}) of {@code levelOrDimension}: a server level, or the dimension's id. */
    public static CgAnchor chunk(Object levelOrDimension, int chunkX, int chunkZ) {
        String dimension = dimensionOf(levelOrDimension);
        return new CgAnchor(Kind.CHUNK, dimension, 0, 0, 0, levelOf(levelOrDimension, dimension), null, chunkX, chunkZ, -1);
    }

    static CgAnchor restore(Kind kind, @Nullable String dimension, double x, double y, double z, @Nullable UUID player,
                            int chunkX, int chunkZ) {
        return kind == Kind.GLOBAL ? GLOBAL : new CgAnchor(kind, dimension, x, y, z, null, player, chunkX, chunkZ, -1);
    }

    /** Named from a client, to be resolved by the server: a point without a level, an entity by id. */
    boolean predictionOnly() {
        return kind == Kind.POINT && dimension == null || kind == Kind.ENTITY && handle == null;
    }

    private static String dimensionOf(Object levelOrDimension) {
        Objects.requireNonNull(levelOrDimension, "levelOrDimension");
        if (levelOrDimension instanceof String) return (String) levelOrDimension;
        String dimension = CgWorldQueries.dimension(levelOrDimension);
        if (dimension == null) {
            throw new IllegalArgumentException("not a level, an entity or a dimension id: " + levelOrDimension);
        }
        return dimension;
    }

    /** The level {@code levelOrDimension} is or is in; null for a dimension id. */
    @Nullable
    private static Object levelOf(Object levelOrDimension, String dimension) {
        if (levelOrDimension instanceof String) return null;
        return CgEntityQueries.idOf(levelOrDimension) < 0 ? levelOrDimension : CgWorldQueries.level(levelOrDimension, dimension);
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        switch (kind) {
            case GLOBAL: return "CgAnchor[global]";
            case POINT: return "CgAnchor[POINT " + dimension + " " + x + ", " + y + ", " + z + "]";
            case ENTITY: return "CgAnchor[ENTITY " + (handle != null ? handle : "#" + entityId) + "]";
            case PLAYER: return "CgAnchor[PLAYER " + player + "]";
            case CHUNK: return "CgAnchor[CHUNK " + dimension + " " + chunkX + ", " + chunkZ + "]";
            default: return "CgAnchor[" + kind + " " + dimension + "]";
        }
    }
}
