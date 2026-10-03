package com.crystalgraphics.mc.v1710.platform.net;

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
 * {@link CgServerPlayers} over 1.7.10's server worlds; installed by {@link Network1710}. The host's own entity tracker
 * and player manager answer, so both answers are exact.
 */
final class ServerPlayers1710 implements CgServerPlayers {

    @Override
    public String dimension(Object handle) {
        if (handle instanceof Entity) return CgServerPlayers.dimensionId(((Entity) handle).dimension);
        return handle instanceof World ? CgServerPlayers.dimensionId(((World) handle).provider.dimensionId) : null;
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
            UUID id = playerId(player);
            if (id != null && world.getPlayerManager().isPlayerWatchingChunk((EntityPlayerMP) player, chunkX, chunkZ)) {
                out.accept(id);
            }
        }
    }

    @Override
    public Path saveDirectory(Object handle) {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : root.toPath().toAbsolutePath().normalize();
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
