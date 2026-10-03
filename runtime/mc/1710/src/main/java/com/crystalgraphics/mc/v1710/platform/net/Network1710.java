package com.crystalgraphics.mc.v1710.platform.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

/**
 * FML's lifecycle on 1.7.10, turned into {@link CgNetwork} calls. Both sides, from init: the channel registered
 * at preInit delivered nothing in either direction.
 *
 * <p>Ticks at {@code START}, so what arrived since the last tick is applied before the world runs on it.</p>
 */
public final class Network1710 {

    private static boolean installed;

    private Network1710() {
    }

    /** Registers the channel and the lifecycle. Idempotent. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgNetwork.install(NetworkChannel1710.create(),
                player -> player instanceof EntityPlayer ? idOf((EntityPlayer) player) : null);
        CgPlatform.provide(CgServerPlayers.SERVICE, new ServerPlayers1710());
        FMLCommonHandler.instance().bus().register(new Handler());
    }

    /** The server is stopping. A mod-lifecycle event, so it arrives from the mod's own handler. */
    public static void serverStopping() {
        CgNetwork.closeAll("server stopping");
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

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) CgNetwork.serverTick();
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) CgNetwork.clientTick();
        }
    }
}
