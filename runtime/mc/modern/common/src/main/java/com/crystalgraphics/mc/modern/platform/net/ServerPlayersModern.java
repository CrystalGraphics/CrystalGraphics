package com.crystalgraphics.mc.modern.platform.net;

import java.util.UUID;
import java.util.function.Consumer;

import com.crystalgraphics.platform.service.CgServerPlayers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
//? if >=1.14 {
import net.minecraft.world.level.ChunkPos;
//?}
//? if <1.16 {
/*import net.minecraft.world.level.dimension.DimensionType;
*///?}

/**
 * {@link CgServerPlayers} over the server's levels, 1.13.2 to 26.3. Server-safe; installed by {@link NetworkModern}.
 *
 * <ul>
 *   <li>Minecraft has no public answer to "who has this entity loaded", so {@link #trackingEntity} ports the rule
 *       vanilla's {@code ChunkMap.TrackedEntity.updatePlayer} applies: the players with the entity's chunk, within its
 *       type's tracking range horizontally. Passengers and the server's range percentage are not counted.</li>
 *   <li>1.13.2 has no {@code ChunkMap}: a chunk's players are those within the server's view distance of it, the rule
 *       1.14 brought in; its tracking range is Forge's and unseen here, so an entity's players are its chunk's.</li>
 * </ul>
 */
final class ServerPlayersModern implements CgServerPlayers {

    @Override
    public String dimension(Object handle) {
        Level level = handle instanceof Level ? (Level) handle : handle instanceof Entity ? levelOf((Entity) handle) : null;
        if (level == null) return null;
        //? if >=1.21.11 {
        /*return level.dimension().identifier().toString();
        *///?} elif >=1.16 {
        return level.dimension().location().toString();
        //?} else {
        /*return DimensionType.getName(level.getDimension().getType()).toString();
        *///?}
    }

    @Override
    public boolean position(Object entity, double[] out) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        //? if >=1.15 {
        out[0] = e.getX();
        out[1] = e.getY();
        out[2] = e.getZ();
        //?} else {
        /*out[0] = e.x;
        out[1] = e.y;
        out[2] = e.z;
        *///?}
        return true;
    }

    @Override
    public UUID playerId(Object entity) {
        return entity instanceof ServerPlayer && ((ServerPlayer) entity).getGameProfile() != null
                ? ((ServerPlayer) entity).getGameProfile().getId() : null;
    }

    @Override
    public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        if (!(level instanceof ServerLevel)) return;
        chunkPlayers((ServerLevel) level, chunkX, chunkZ, player -> {
            UUID id = playerId(player);
            if (id != null) out.accept(id);
        });
    }

    @Override
    public void trackingEntity(Object entity, Consumer<UUID> out) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        Level level = levelOf(e);
        if (!(level instanceof ServerLevel)) return;
        double[] at = new double[3];
        position(e, at);
        double x = at[0], z = at[2];
        double range = trackingRange(e);
        double limit = range * range;
        chunkPlayers((ServerLevel) level, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, player -> {
            if (player == e || !position(player, at)) return;
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

    private static Level levelOf(Entity e) {
        //? if >=1.20 {
        return e.level();
        //?} else {
        /*return e.level;
        *///?}
    }
}
