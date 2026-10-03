package com.crystalgraphics.mc.legacy.platform.net;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

import com.crystalgraphics.platform.service.CgServerPlayers;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * {@link CgServerPlayers} over Forge 1.8–1.12.2's server worlds; installed by {@link NetworkLegacy}. The host's own
 * entity tracker and chunk map answer, so both answers are exact.
 */
final class ServerPlayersLegacy implements CgServerPlayers {

    @Override
    public String dimension(Object handle) {
        if (handle instanceof Entity) return CgServerPlayers.dimensionId(((Entity) handle).dimension);
        if (!(handle instanceof World)) return null;
        //? if <1.9 {
        /*return CgServerPlayers.dimensionId(((World) handle).provider.getDimensionId());
        *///?} else {
        return CgServerPlayers.dimensionId(((World) handle).provider.getDimension());
        //?}
    }

    @Override
    public boolean position(Object entity, double[] out) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        out[0] = e.posX;
        out[1] = e.posY;
        out[2] = e.posZ;
        return true;
    }

    @Override
    public UUID playerId(Object entity) {
        return entity instanceof EntityPlayer && ((EntityPlayer) entity).getGameProfile() != null
                ? ((EntityPlayer) entity).getGameProfile().getId() : null;
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
    public Path saveDirectory(Object handle) {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : root.toPath().toAbsolutePath().normalize();
    }

    @Override
    public void trackingEntity(Object entity, Consumer<UUID> out) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        //? if <1.9 {
        /*World world = e.worldObj;
        *///?} else {
        World world = e.world;
        //?}
        if (!(world instanceof WorldServer)) return;
        for (Object player : ((WorldServer) world).getEntityTracker().getTrackingPlayers(e)) {
            UUID id = playerId(player);
            if (id != null) out.accept(id);
        }
    }
}
