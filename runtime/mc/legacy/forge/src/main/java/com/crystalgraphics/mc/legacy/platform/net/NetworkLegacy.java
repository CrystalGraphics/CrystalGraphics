package com.crystalgraphics.mc.legacy.platform.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * FML's lifecycle on Forge 1.8–1.12.2, turned into {@link CgNetwork} calls. Both sides, from init: the channel
 * registered at preInit on 1.7.10 delivered nothing in either direction.
 *
 * <p>Ticks at {@code START}, so what arrived since the last tick is applied before the world runs on it.</p>
 */
public final class NetworkLegacy {

    private static boolean installed;

    private NetworkLegacy() {
    }

    /** Registers the channel and the lifecycle. Idempotent. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgNetwork.install(NetworkChannelLegacy.create(),
                player -> player instanceof EntityPlayer ? idOf((EntityPlayer) player) : null);
        CgPlatform.provide(CgServerPlayers.SERVICE, new ServerPlayersLegacy());
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

    @Nullable
    static NetHandlerPlayServer handler(EntityPlayerMP player) {
        //? if <1.9 {
        /*return player.playerNetServerHandler;
        *///?} else {
        return player.connection;
        //?}
    }

    /** The entity a connection points at now: replaced on every respawn, while the handler is re-pointed. */
    @Nullable
    static EntityPlayerMP player(NetHandlerPlayServer handler) {
        //? if <1.12 {
        /*return handler.playerEntity;
        *///?} else {
        return handler.player;
        //?}
    }

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            UUID id = idOf(player);
            NetHandlerPlayServer handler = handler(player);
            if (id == null || handler == null) return;   // a fake player: nothing to talk to
            CgNetwork.playerJoined(id, player.getName(), () -> player(handler));
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
