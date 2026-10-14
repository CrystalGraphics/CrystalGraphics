package com.crystalgraphics.mc.modern.platform.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.mc.modern.platform.world.HostFormatsModern;
import com.crystalgraphics.mc.modern.platform.world.HostShapesModern;
import com.crystalgraphics.mc.modern.platform.world.LevelsModern;
import com.crystalgraphics.mc.modern.platform.world.MirrorHostModern;
import com.crystalgraphics.mc.modern.platform.world.PlayerQueryModern;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Minecraft's players turned into {@link CgNetwork} calls, for all three modern loaders. Server-safe: it names no
 * client class. Each loader forwards its own events here.
 */
public final class NetworkModern {

    private NetworkModern() {
    }

    /** Mod init, both sides. */
    public static void install(CgNetworkChannel channel) {
        CgNetwork.install(channel, player -> player instanceof ServerPlayer ? idOf((ServerPlayer) player) : null);
        CgPlatform.provide(CgLevels.SERVICE, LevelsModern.INSTANCE);
        CgPlatform.provide(CgPlayerQuery.SERVICE, PlayerQueryModern.INSTANCE);
        CgPlatform.provide(CgHostShapes.SERVICE, new HostShapesModern());
        CgPlatform.provide(CgMirrorHost.SERVICE, new MirrorHostModern());
        CgPlatform.provide(CgHostFormats.SERVICE, new HostFormatsModern());
    }

    public static void playerJoined(@Nullable ServerPlayer player) {
        UUID id = idOf(player);
        if (id == null || player.connection == null) return;
        // The listener is re-pointed at the new entity on a respawn or a dimension change; the entity is not.
        ServerGamePacketListenerImpl listener = player.connection;
        CgNetwork.playerJoined(id, player.getGameProfile().getName(), () -> listener.player);
    }

    public static void playerLeft(@Nullable ServerPlayer player) {
        UUID id = idOf(player);
        if (id != null) CgNetwork.playerLeft(id);
    }

    public static void serverStarting(MinecraftServer server) {
        PlayerQueryModern.INSTANCE.server(server);
        CgNetwork.serverStarting(server);
    }

    public static void serverTick() {
        CgNetwork.serverTick();
    }

    public static void serverStopping() {
        CgNetwork.closeAll("server stopping");
        PlayerQueryModern.INSTANCE.server(null);
    }

    public static void clientConnected() {
        CgNetwork.clientConnected();
    }

    public static void clientDisconnected() {
        CgNetwork.clientDisconnected();
    }

    @Nullable
    private static UUID idOf(@Nullable ServerPlayer player) {
        return player == null || player.getGameProfile() == null ? null : player.getGameProfile().getId();
    }
}
