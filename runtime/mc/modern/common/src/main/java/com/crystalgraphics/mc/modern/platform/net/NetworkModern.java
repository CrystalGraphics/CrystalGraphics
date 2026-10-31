package com.crystalgraphics.mc.modern.platform.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgNetwork;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Minecraft's players and connections turned into {@link CgNetwork} calls, for all three modern loaders. Server-safe:
 * it names no client class. Each loader forwards its own events here; the server's start, stop and tick go to
 * {@code ServerModern}.
 */
public final class NetworkModern {

    private NetworkModern() {
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
