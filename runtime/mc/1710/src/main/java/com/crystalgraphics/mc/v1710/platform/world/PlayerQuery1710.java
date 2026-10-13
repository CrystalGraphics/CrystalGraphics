package com.crystalgraphics.mc.v1710.platform.world;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.crystalgraphics.platform.world.CgPlayerQuery;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * {@link CgPlayerQuery} over 1.7.10's server players and this client's own; provided by {@code Network1710}, the
 * client's player from {@link #client}. The host's own entity tracker and player manager answer, so both tracking
 * answers are exact; the server's player list is walked for a player by id.
 */
public final class PlayerQuery1710 implements CgPlayerQuery {

    public static final PlayerQuery1710 INSTANCE = new PlayerQuery1710();

    private volatile Supplier<Object> localPlayer = () -> null;

    private PlayerQuery1710() {
    }

    /** The client's half, from client code once: its own player. */
    public void client(Supplier<Object> localPlayer) {
        this.localPlayer = localPlayer;
    }

    @Override
    public UUID playerId(Object entity) {
        return entity instanceof EntityPlayer && ((EntityPlayer) entity).getGameProfile() != null
                ? ((EntityPlayer) entity).getGameProfile().getId() : null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V player(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || id == null) return null;
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            if (id.equals(playerId(player))) return (V) player;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V player(int entityId) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            if (player.getEntityId() == entityId) return (V) player;
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
        if (!(level instanceof WorldServer)) return;
        WorldServer world = (WorldServer) level;
        for (Object player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) continue;
            UUID id = playerId(player);
            if (id != null && world.getPlayerManager().isPlayerWatchingChunk((EntityPlayerMP) player, chunkX, chunkZ)) {
                out.accept(id);
            }
        }
    }

    @Override
    public void trackingEntity(Object entity, Consumer<UUID> out) {
        if (!(entity instanceof Entity) || !(((Entity) entity).worldObj instanceof WorldServer)) return;
        Entity e = (Entity) entity;
        for (Object player : ((WorldServer) e.worldObj).getEntityTracker().getTrackingPlayers(e)) {
            UUID id = playerId(player);
            if (id != null) out.accept(id);
        }
    }
}
