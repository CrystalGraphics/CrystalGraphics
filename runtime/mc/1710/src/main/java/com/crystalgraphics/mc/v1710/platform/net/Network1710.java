package com.crystalgraphics.mc.v1710.platform.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.net.CgNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

/**
 * FML's players and connections on 1.7.10, turned into {@link CgNetwork} calls. Both sides, from init: the channel
 * registered at preInit on 1.7.10 delivered nothing in either direction. The ticks and the server's start and stop are
 * the engine's, not the network's.
 */
public final class Network1710 {

    private static boolean installed;

    private Network1710() {
    }

    /** Registers the channel, installs the network over it, and hears players and connections. Idempotent. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgEngineLifecycle.init(NetworkChannel1710.create());   // after the platform's slots: it reads player ids through them
        FMLCommonHandler.instance().bus().register(new Handler());
    }

    @Nullable
    private static UUID idOf(@Nullable EntityPlayer player) {
        return player == null || player.getGameProfile() == null ? null : player.getGameProfile().getId();
    }

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            UUID id = idOf(player);
            // The handler outlives the entity, which 1.7.10 replaces on every respawn and dimension change.
            NetHandlerPlayServer handler = player.playerNetServerHandler;
            if (id == null || handler == null) return;   // a fake player: nothing to talk to
            CgNetwork.playerJoined(id, player.getCommandSenderName(), () -> handler.playerEntity);
        }

        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID id = idOf(event.player);
            if (id != null) CgNetwork.playerLeft(id);
        }

        @SubscribeEvent
        public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            CgNetwork.clientConnected();
        }

        @SubscribeEvent
        public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            CgNetwork.clientDisconnected();
        }
    }
}
