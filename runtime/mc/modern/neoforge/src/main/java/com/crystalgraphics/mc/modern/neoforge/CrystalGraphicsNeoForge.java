package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.modern.net.NetworkModern;
import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import java.util.function.BiConsumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
//? if >=1.21.7 {
/*import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
*///?}
//? if >=1.20.5 {
/*import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
*///?} elif >=1.20.4 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;
*///?} else {
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.network.NetworkEvent;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.simple.SimpleChannel;
//?}
import com.mojang.logging.LogUtils;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.ResourceManager;
//? if >=1.21.9 {
/*import net.minecraft.client.Minecraft;
*///?}
import net.neoforged.bus.api.IEventBus;
import com.crystalgraphics.mc.shared.FmlSide;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
//? if >=1.20.6 {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
*///?} else {
import net.neoforged.neoforge.event.TickEvent;
//?}
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
//? if >=1.21.4 {
/*import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
*///?} else {
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
//?}
//? if <1.21.2 {
import net.minecraft.util.profiling.ProfilerFiller;
//?}
//? if >=1.21.8 <26.1 {
/*import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice;
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuTexture;
*///?}

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * Everything NeoForge — the mod entry point, its {@link Network} transport and its {@link Events} subscriptions.
 *
 * <p>Registration only: which event, and which stage of it. What the engine then does is
 * {@code LifecycleModern}'s, shared with Forge and Fabric.</p>
 */
public final class CrystalGraphicsNeoForge implements VariantEntry {

    /** @param context the {@code IEventBus} NeoForge handed {@link NeoForgeBootstrap}. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the log rather than the crash report: NeoForge 20.4 exposes no crash
        // callable — CrashReportExtender is its own — so unlike Forge and 1.7.10 there is nothing to
        // register with, and `latest.log` is the file a report is attached with anyway. @see CrashVariant
        LogUtils.getLogger().info("[cg] {}: {}", CrashVariant.LABEL,
                CrashVariant.report(CrystalGraphicsNeoForge.class));
        CgPlatform.register(PlatformServiceModern.getInstance());
        NetworkModern.install(Network.get());
        Events.register((IEventBus) context);
    }

    // -- Events -----------------------------------------------------------------

    /** NeoForge's engine event subscriptions. */
    public static final class Events {

        private Events() {}

        /** Called once from the entry point. */
        static void register(IEventBus modBus) {
            NeoForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
            NeoForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            NeoForge.EVENT_BUS.addListener(Events::onFrameEnd);
            NeoForge.EVENT_BUS.addListener(Events::onGameShuttingDown);

            // The connection lifecycle's server half: a dedicated server opens connections too.
            //? if >=1.20.4 {
            /*modBus.addListener(Network::register);
            *///?} else {
            Network.register();
            //?}
            NeoForge.EVENT_BUS.addListener(Events::onServerStopping);
            NeoForge.EVENT_BUS.addListener(Events::onServerTick);
            NeoForge.EVENT_BUS.addListener(Events::onPlayerJoin);
            NeoForge.EVENT_BUS.addListener(Events::onPlayerLeave);

            // A SEPARATE CLASS, not a branch here: naming a client-only event type in a method of
            // Events would resolve it when a dedicated server links this class.
            if (FmlSide.isClient(FMLLoader.class)) ModBus.register(modBus);
        }

        private static void onServerStopping(ServerStoppingEvent event) {
            NetworkModern.serverStopping();
        }

        // 1.20.5 split the tick events by phase into classes of their own.
        //? if >=1.20.5 {
        /*private static void onServerTick(ServerTickEvent.Post event) {
            NetworkModern.serverTick();
        }
        *///?} else {
        private static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) NetworkModern.serverTick();
        }
        //?}

        private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) NetworkModern.playerJoined(player);
        }

        private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) NetworkModern.playerLeft(player);
        }

        // -- MOD bus ----------------------------------------------------------------

        /** Client-only, and a class of its own so a dedicated server never links one of these types. */
        public static final class ModBus {
            private ModBus() {}

            static void register(IEventBus modBus) {
                modBus.addListener(ModBus::onRegisterReloadListeners);
                // The camera hooks a shake and an FOV kick are added at (HostCameraModern): game-bus events, but
                // client-only types, so they live here.
                NeoForge.EVENT_BUS.addListener(ModBus::onCameraAngles);
                NeoForge.EVENT_BUS.addListener(ModBus::onFov);
                HostCameraModern.declare(CgHostCamera.ROTATION | CgHostCamera.ROLL | CgHostCamera.FOV);
                // The world events, once a client tick and as entities join and leave the client level.
                NeoForge.EVENT_BUS.addListener(ModBus::onClientTick);
                NeoForge.EVENT_BUS.addListener(ModBus::onLoggedIn);
                NeoForge.EVENT_BUS.addListener(ModBus::onLoggedOut);
                // NeoForge 21.7 refuses a clientbound payload with no client-side handler, and the common
                // registrar's no longer counts as one.
                //? if >=1.21.7 {
                /*modBus.addListener(ModBus::onRegisterClientPayloads);
                *///?}
                NeoForge.EVENT_BUS.addListener(ModBus::onEntityJoin);
                NeoForge.EVENT_BUS.addListener(ModBus::onEntityLeave);
                // ExplosionHook and LevelEventHook, the node mixins that report them.
                //? if >=1.21.6 {
                /*CgWorldEvents.declare(CgWorldEvents.EXPLOSION | CgWorldEvents.BLOCK_BROKEN);
                *///?}
                // A dev run wraps every GPU texture and the device for validation; ours are the GL ones under them.
                //? if >=1.21.8 <26.1 {
                /*LifecycleModern.unwrapWith(
                        texture -> texture instanceof ValidationGpuTexture wrapped ? wrapped.getRealTexture() : texture,
                        device -> device instanceof ValidationGpuDevice wrapped ? wrapped.getRealDevice() : device);
                *///?}
            }

            private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
                event.setYaw(HostCameraModern.yaw(event.getYaw()));
                event.setPitch(HostCameraModern.pitch(event.getPitch()));
                event.setRoll(HostCameraModern.roll(event.getRoll()));
            }

            // A double to 1.21.1 and a float after; HostCameraModern.fov takes either.
            private static void onFov(ViewportEvent.ComputeFov event) {
                event.setFOV(HostCameraModern.fov(event.getFOV()));
            }

            // Client levels only: in single player the integrated server's levels post these too.
            private static void onEntityJoin(EntityJoinLevelEvent event) {
                if (event.getLevel().isClientSide()) LifecycleModern.entityJoined(event.getEntity());
            }

            private static void onEntityLeave(EntityLeaveLevelEvent event) {
                if (event.getLevel().isClientSide()) LifecycleModern.entityLeft(event.getEntity());
            }

            private static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
                NetworkModern.clientConnected();
            }

            private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
                NetworkModern.clientDisconnected();
            }

            //? if >=1.21.7 {
            /*private static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
                event.register(Network.Frame.TYPE, Network::receive);
            }
            *///?}

            // TickEvent's client tick at END, ClientTickEvent.Post from NeoForge 20.6.
            //? if >=1.20.6 {
            /*private static void onClientTick(ClientTickEvent.Post event) {
                LifecycleModern.clientTick();
            }
            *///?} else {
            private static void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleModern.clientTick();
            }
            //?}

            // NeoForge 21.4 keys every listener by id.
            //? if >=1.21.4 {
            /*private static void onRegisterReloadListeners(AddClientReloadListenersEvent event) {
                event.addListener(ResourceIds.of(MODID, "asset_reload"), ModBus::reload);
            }
            *///?} else {
            private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
                event.registerReloadListener(ModBus::reload);
            }
            //?}

            // 1.21.2 dropped the two profilers; 1.21.9 hands a SharedState for the manager.
            //? if >=1.21.9 {
            /*private static CompletableFuture<Void> reload(PreparableReloadListener.SharedState state, Executor background,
                                                          PreparationBarrier stage, Executor game) {
                return stage.wait(null).thenRunAsync(LifecycleModern::reload, game);
            }
            *///?} elif >=1.21.2 {
            /*private static CompletableFuture<Void> reload(PreparationBarrier stage, ResourceManager manager,
                                                          Executor background, Executor game) {
                return stage.wait(null).thenRunAsync(LifecycleModern::reload, game);
            }
            *///?} else {
            private static CompletableFuture<Void> reload(PreparationBarrier stage, ResourceManager manager,
                                                          ProfilerFiller prepare, ProfilerFiller apply,
                                                          Executor background, Executor game) {
                return stage.wait(null).thenRunAsync(LifecycleModern::reload, game);
            }
            //?}
        }

        // -- NEOFORGE bus -----------------------------------------------------------

        // NeoForge 21.6 made each stage an event class of its own; 21.9 draws block entities with the
        // entities, so AfterEntities is the last opaque stage. 26.1 names them for what they draw:
        // entities are features, and AfterTranslucentParticles follows the particles' reset.
        //? if >=26.1 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent.AfterOpaqueFeatures event) {
            LifecycleModern.opaquePass(partialTick(event));
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent.AfterTranslucentParticles event) {
            LifecycleModern.transparentPass();
        }
        *///?} elif >=1.21.9 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent.AfterEntities event) {
            LifecycleModern.opaquePass(partialTick(event));
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent.AfterParticles event) {
            LifecycleModern.transparentPass();
        }
        *///?} elif >=1.21.6 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent.AfterBlockEntities event) {
            LifecycleModern.opaquePass(partialTick(event));
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent.AfterParticles event) {
            LifecycleModern.transparentPass();
        }
        *///?} else {
        private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1140 (MC 1.20.4),
            // after block entities, before renderSectionLayer(translucent).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            LifecycleModern.opaquePass(partialTick(event));
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1215/1230 (MC 1.20.4),
            // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            LifecycleModern.transparentPass();
        }
        //?}

        // 1.21 hands a DeltaTracker; `true` is the pause-aware residual 1.20's float already was.
        // NeoForge 21.9's event carries none, so the game's own tracker answers.
        //? if >=1.21.9 {
        /*private static float partialTick(RenderLevelStageEvent event) {
            return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        }
        *///?} elif >=1.21 {
        /*private static float partialTick(RenderLevelStageEvent event) {
            return event.getPartialTick().getGameTimeDeltaPartialTick(true);
        }
        *///?} else {
        private static float partialTick(RenderLevelStageEvent event) {
            return event.getPartialTick();
        }
        //?}

        // The frame end, once a frame after the GUI too: RenderTickEvent at END, RenderFrameEvent.Post from
        // NeoForge 20.6.
        //? if >=1.20.6 {
        /*private static void onFrameEnd(RenderFrameEvent.Post event) {
            LifecycleModern.frameEnd();
        }
        *///?} else {
        private static void onFrameEnd(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) LifecycleModern.frameEnd();
        }
        //?}

        private static void onGameShuttingDown(GameShuttingDownEvent event) {
            LifecycleModern.shutdown();
        }
    }

    // -- Network ----------------------------------------------------------------

    /**
     * The NeoForge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s.
     *
     * <p>Three payload APIs: 20.2-20.3's Forge-shaped {@code SimpleChannel}, 1.20.4's registrar keyed on
     * an id, and 1.20.5's typed payloads with a {@code StreamCodec}. NeoForge splits an oversized payload
     * itself on all three, so one frame may exceed vanilla's 32 KiB serverbound cap.</p>
     */
    public static final class Network implements CgNetworkChannel {

        private static final String VERSION = "1";
        private static final ResourceLocation ID = ResourceIds.of(MODID, "wire");

        /** Under the payload split threshold, so one frame stays one packet. */
        private static final int MAX_FRAME_BYTES = 900_000;

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

        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar(VERSION).optional().playBidirectional(Frame.TYPE, Frame.CODEC, Network::receive);
        }

        private static void receive(Frame frame, IPayloadContext context) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.player() instanceof ServerPlayer p ? p : null;
                INSTANCE.inbound.accept(sender, frame.bytes());
            });
        }
        *///?} elif >=1.20.4 {
        /*// One payload carrying a frame.
        public record Frame(byte[] bytes) implements CustomPacketPayload {

            public Frame(FriendlyByteBuf buf) {
                this(buf.readByteArray());
            }

            @Override
            public void write(FriendlyByteBuf buf) {
                buf.writeByteArray(bytes);
            }

            @Override
            public ResourceLocation id() {
                return ID;
            }
        }

        // Wired to RegisterPayloadHandlerEvent on the mod bus.
        public static void register(RegisterPayloadHandlerEvent event) {
            event.registrar(MODID)
                    .versioned(VERSION)
                    .optional()
                    .play(ID, Frame::new, handler -> handler
                            .client(Network::receive)
                            .server(Network::receive));
        }

        private static void receive(Frame frame, PlayPayloadContext context) {
            // enqueueWork: the handler runs on the network thread and inbound is the game thread's.
            context.workHandler().submitAsync(() -> {
                ServerPlayer sender = context.player().filter(p -> p instanceof ServerPlayer)
                        .map(p -> (ServerPlayer) p).orElse(null);
                INSTANCE.inbound.accept(sender, frame.bytes());
            });
        }
        *///?} else {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(ID)
                .networkProtocolVersion(() -> VERSION)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
                .simpleChannel();

        /** Called once from the entry point, before anything can send: 20.2 has no registration event. */
        public static void register() {
            CHANNEL.registerMessage(0, byte[].class,
                    (frame, buf) -> buf.writeByteArray(frame),
                    FriendlyByteBuf::readByteArray,
                    Network::receive);
        }

        private static void receive(byte[] frame, NetworkEvent.Context ctx) {
            // enqueueWork: the handler runs on the network thread, and inbound is the game thread's.
            ctx.enqueueWork(() -> INSTANCE.inbound.accept(ctx.getSender(), frame));
            ctx.setPacketHandled(true);
        }
        //?}

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            // NeoForge 21.7 moved the client's send to a client-only class.
            //? if >=1.21.7 {
            /*ClientPacketDistributor.sendToServer(new Frame(frame));
            *///?} elif >=1.20.5 {
            /*PacketDistributor.sendToServer(new Frame(frame));
            *///?} elif >=1.20.4 {
            /*PacketDistributor.SERVER.noArg().send(new Frame(frame));
            *///?} else {
            CHANNEL.sendToServer(frame);
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            //? if >=1.20.5 {
            /*PacketDistributor.sendToPlayer(serverPlayer, new Frame(frame));
            *///?} elif >=1.20.4 {
            /*PacketDistributor.PLAYER.with(serverPlayer).send(new Frame(frame));
            *///?} else {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), frame);
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
