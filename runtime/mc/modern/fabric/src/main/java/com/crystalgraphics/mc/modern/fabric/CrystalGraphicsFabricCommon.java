package com.crystalgraphics.mc.modern.fabric;

import com.crystalgraphics.mc.modern.platform.net.NetworkModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;

import com.crystalgraphics.mc.shared.VariantEntry;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
/*import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
*///?} else {
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//?}
import org.apache.logging.log4j.LogManager;

import java.util.function.BiConsumer;

import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * Both sides. The platform bundle is what every consumer reads through {@code CgPlatform}, and a
 * dedicated server needs it as much as a client does -- {@code register} builds no GL backend, so the
 * absence of LWJGL there costs nothing.
 *
 * <p>Separate from {@link CrystalGraphicsFabric} because Fabric runs no {@code client} entrypoint on
 * a server: registering there left {@code CgPlatform} unset for the whole server process, and every
 * accessor threw {@code IllegalStateException: CgPlatform not yet registered}. Found by CrystalGUI's
 * dedicated-server smoke check.</p>
 */
public final class CrystalGraphicsFabricCommon implements VariantEntry {
    
    /** @param context null — Fabric hands an entry point nothing. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the log rather than the crash report: Fabric Loader exposes no crash
        // callable, so unlike Forge and 1.7.10 there is nothing to register with. @see CrashVariant
        LogManager.getLogger("CrystalGraphics").info("[cg] {}: {}", CrashVariant.LABEL,
                CrashVariant.report(CrystalGraphicsFabricCommon.class));
        CgPlatform.register(PlatformServiceModern.getInstance());
        NetworkModern.install(Network.get());
        Network.registerServerReceiver();
        registerServerEvents();
    }

    /** The connection lifecycle's server half. Both sides: a dedicated server opens connections too. */
    private static void registerServerEvents() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> NetworkModern.serverStopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> NetworkModern.serverTick());
        // getPlayer() arrived in 1.17; before it the handler exposes the field.
        //? if >=1.17 {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NetworkModern.playerJoined(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NetworkModern.playerLeft(handler.getPlayer()));
        //?} else {
        /*ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NetworkModern.playerJoined(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NetworkModern.playerLeft(handler.player));
        *///?}
    }

    // -- Network ----------------------------------------------------------------

    /**
     * The Fabric transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s.
     *
     * <p>Two payload APIs: 1.20.1's channel keyed on an id with a raw buffer, and 1.20.5's typed payloads,
     * registered in {@code PayloadTypeRegistry} with a {@code StreamCodec} before any receiver.</p>
     */
    public static final class Network implements CgNetworkChannel {

        private static final ResourceLocation ID = ResourceIds.of(MODID, "wire");

        // 1.20.1: Fabric's custom-payload limit is ~1 MB, so one frame is one packet. 1.20.5+: nothing
        // here has shown Fabric lifting vanilla's 32 767-byte serverbound cap, and Fabric does not split,
        // so a frame is kept under it -- net.wire already splits a message into as many frames as needed.
        //? if >=1.20.5 {
        /*private static final int MAX_FRAME_BYTES = 32_000;
        *///?} else {
        private static final int MAX_FRAME_BYTES = 900_000;
        //?}

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        //? if >=1.20.5 {
        /*public record Frame(byte[] bytes) implements CustomPacketPayload {

            static final CustomPacketPayload.Type<Frame> TYPE = new CustomPacketPayload.Type<>(ID);
            static final StreamCodec<ByteBuf, Frame> CODEC = ByteBufCodecs.BYTE_ARRAY.map(Frame::new, Frame::bytes);

            @Override
            public CustomPacketPayload.Type<Frame> type() {
                return TYPE;
            }
        }
        *///?}

        /**
         * The server half, and on 1.20.5+ the payload type both directions share. Safe on a dedicated
         * server; names no client class. A receiver runs on the netty thread and inbound is the game
         * thread's, so each hands its frame across.
         */
        public static void registerServerReceiver() {
            // Through the player below 1.21.9: Context.server() is fabric-api 0.99+, and 1.20.5's stops at
            // 0.97. 1.21.9 took getServer() off the player, and every fabric-api for it has server().
            // Fabric API for 26.1 names the registries by direction, serverbound and clientbound.
            //? if >=26.1 {
            /*PayloadTypeRegistry.serverboundPlay().register(Frame.TYPE, Frame.CODEC);
            PayloadTypeRegistry.clientboundPlay().register(Frame.TYPE, Frame.CODEC);
            ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.server().execute(() -> INSTANCE.inbound.accept(context.player(), frame.bytes())));
            *///?} elif >=1.21.9 {
            /*PayloadTypeRegistry.playC2S().register(Frame.TYPE, Frame.CODEC);
            PayloadTypeRegistry.playS2C().register(Frame.TYPE, Frame.CODEC);
            ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.server().execute(() -> INSTANCE.inbound.accept(context.player(), frame.bytes())));
            *///?} elif >=1.20.5 {
            /*PayloadTypeRegistry.playC2S().register(Frame.TYPE, Frame.CODEC);
            PayloadTypeRegistry.playS2C().register(Frame.TYPE, Frame.CODEC);
            ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.player().getServer().execute(() -> INSTANCE.inbound.accept(context.player(), frame.bytes())));
            *///?} else {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                server.execute(() -> INSTANCE.inbound.accept(player, frame));
            });
            //?}
        }

        /** The client half, called only from the client initialiser. */
        public static void registerClientReceiver() {
            //? if >=1.20.5 {
            /*ClientPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.client().execute(() -> INSTANCE.inbound.accept(null, frame.bytes())));
            *///?} else {
            ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                client.execute(() -> INSTANCE.inbound.accept(null, frame));
            });
            //?}
        }

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            //? if >=1.20.5 {
            /*ClientPlayNetworking.send(new Frame(frame));
            *///?} else {
            ClientPlayNetworking.send(ID, PacketByteBufs.create().writeByteArray(frame));
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            //? if >=1.20.5 {
            /*ServerPlayNetworking.send(serverPlayer, new Frame(frame));
            *///?} else {
            ServerPlayNetworking.send(serverPlayer, ID, PacketByteBufs.create().writeByteArray(frame));
            //?}
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
            inbound = handler == null ? (sender, frame) -> { } : handler;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
