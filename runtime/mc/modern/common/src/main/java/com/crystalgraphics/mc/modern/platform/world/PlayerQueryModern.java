package com.crystalgraphics.mc.modern.platform.world;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.crystalgraphics.platform.world.CgPlayerQuery;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
//? if >=1.14 {
import net.minecraft.world.level.ChunkPos;
//?}

/**
 * {@link CgPlayerQuery} over the server's players and this client's own, 1.13.2 to 26.3. Server-safe; provided by
 * {@code NetworkModern} on both sides, which gives it the server at its start; the client's player comes from
 * {@link #client}.
 *
 * <ul>
 *   <li>Minecraft has no public answer to "who has this entity loaded", so {@link #trackingEntity} ports the rule
 *       vanilla's {@code ChunkMap.TrackedEntity.updatePlayer} applies: the players with the entity's chunk, within its
 *       type's tracking range horizontally. Passengers and the server's range percentage are not counted.</li>
 *   <li>1.13.2 has no {@code ChunkMap}: a chunk's players are those within the server's view distance of it, the rule
 *       1.14 brought in; its tracking range is Forge's and unseen here, so an entity's players are its chunk's.</li>
 * </ul>
 */
public final class PlayerQueryModern implements CgPlayerQuery {

    public static final PlayerQueryModern INSTANCE = new PlayerQueryModern();

    private volatile MinecraftServer server;
    private volatile Supplier<Object> localPlayer = () -> null;
    private final double[] at = new double[3];

    private PlayerQueryModern() {
    }

    /** The running server, at its start; null once it stopped. */
    public void server(MinecraftServer server) {
        this.server = server;
    }

    /** The client's half, from client code once: its own player. */
    public void client(Supplier<Object> localPlayer) {
        this.localPlayer = localPlayer;
    }

    @Override
    public UUID playerId(Object entity) {
        return entity instanceof ServerPlayer && ((ServerPlayer) entity).getGameProfile() != null
                ? ((ServerPlayer) entity).getGameProfile().getId() : null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V player(UUID id) {
        MinecraftServer s = server;
        return s == null || id == null ? null : (V) s.getPlayerList().getPlayer(id);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V player(int entityId) {
        MinecraftServer s = server;
        if (s == null) return null;
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (player.getId() == entityId) return (V) player;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V localPlayer() {
        return (V) localPlayer.get();
    }

    @Override
    public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        if (!(level instanceof ServerLevel)) return;
        chunkPlayers((ServerLevel) level, chunkX, chunkZ, player -> {
            UUID id = playerId(player);
            if (id != null) out.accept(id);
        });
    }

    /** Server thread only: {@link #at} is its scratch. */
    @Override
    public void trackingEntity(Object entity, Consumer<UUID> out) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        Level level = LevelsModern.levelOf(e);
        if (!(level instanceof ServerLevel)) return;
        LevelsModern.INSTANCE.position(e, at);
        double x = at[0], z = at[2];
        double range = trackingRange(e);
        double limit = range * range;
        chunkPlayers((ServerLevel) level, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, player -> {
            if (player == e || !LevelsModern.INSTANCE.position(player, at)) return;
            double dx = at[0] - x, dz = at[2] - z;
            UUID id = playerId(player);
            if (id != null && dx * dx + dz * dz <= limit) out.accept(id);
        });
    }

    private static void chunkPlayers(ServerLevel level, int chunkX, int chunkZ, Consumer<ServerPlayer> out) {
        //? if >=1.14 {
        // A Stream to 1.17.1, a List from 1.18.2: forEach is the same call on both.
        level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunkX, chunkZ), false).forEach(out);
        //?} else {
        /*int view = level.getServer().getPlayerList().getViewDistance();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level == level && Math.max(Math.abs(player.xChunk - chunkX), Math.abs(player.zChunk - chunkZ)) <= view) {
                out.accept(player);
            }
        }
        *///?}
    }

    /** Blocks within which a client keeps {@code e}; unbounded where the version does not say. */
    private static double trackingRange(Entity e) {
        //? if >=1.16 {
        return e.getType().clientTrackingRange() * 16.0;
        //?} elif >=1.14 {
        /*return e.getType().chunkRange() * 16.0;
        *///?} else {
        /*return Double.MAX_VALUE;
        *///?}
    }
}
