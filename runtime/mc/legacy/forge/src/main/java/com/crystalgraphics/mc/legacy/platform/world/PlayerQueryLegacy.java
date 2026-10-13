package com.crystalgraphics.mc.legacy.platform.world;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.crystalgraphics.platform.world.CgPlayerQuery;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * {@link CgPlayerQuery} over Forge 1.8–1.12.2's server players and this client's own; provided by
 * {@code NetworkLegacy}, the client's player from {@link #client}. The host's own entity tracker and chunk map answer,
 * so both tracking answers are exact.
 */
public final class PlayerQueryLegacy implements CgPlayerQuery {

    public static final PlayerQueryLegacy INSTANCE = new PlayerQueryLegacy();

    private volatile Supplier<Object> localPlayer = () -> null;

    private PlayerQueryLegacy() {
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
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || id == null) return null;
        //? if <1.10 {
        /*return (V) server.getConfigurationManager().getPlayerByUUID(id);
        *///?} else {
        return (V) server.getPlayerList().getPlayerByUUID(id);
        //?}
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V player(int entityId) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return null;
        //? if <1.10 {
        /*List<?> players = server.getConfigurationManager().playerEntityList;
        *///?} else {
        List<?> players = server.getPlayerList().getPlayers();
        //?}
        for (Object player : players) {
            if (((Entity) player).getEntityId() == entityId) return (V) player;
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
            //? if <1.9 {
            /*boolean watching = world.getPlayerManager().isPlayerWatchingChunk((EntityPlayerMP) player, chunkX, chunkZ);
            *///?} else {
            boolean watching = world.getPlayerChunkMap().isPlayerWatchingChunk((EntityPlayerMP) player, chunkX, chunkZ);
            //?}
            UUID id = playerId(player);
            if (watching && id != null) out.accept(id);
        }
    }

    @Override
    public void trackingEntity(Object entity, Consumer<UUID> out) {
        if (!(entity instanceof Entity)) return;
        World world = LevelsLegacy.worldOf(entity);
        if (!(world instanceof WorldServer)) return;
        for (Object player : ((WorldServer) world).getEntityTracker().getTrackingPlayers((Entity) entity)) {
            UUID id = playerId(player);
            if (id != null) out.accept(id);
        }
    }
}
