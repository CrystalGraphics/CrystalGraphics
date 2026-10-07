package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.modern.platform.net.NetworkModern;
import com.crystalgraphics.mc.modern.platform.CrystalGraphics;
import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
//? if >=1.14.4 {
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
//?} else {
/*import net.minecraftforge.fml.common.gameevent.PlayerEvent;
*///?}
//? if >=1.18 {
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
//?} elif >=1.17 {
/*import net.minecraftforge.fmlserverevents.FMLServerAboutToStartEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
*///?} else {
/*import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
*///?}
//? if >=1.20.2 {
/*import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
*///?} elif >=1.18 {
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
//?} elif >=1.17 {
/*import net.minecraftforge.fmllegacy.network.PacketDistributor;
import net.minecraftforge.fmllegacy.network.NetworkEvent;
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
import net.minecraftforge.fmllegacy.network.simple.SimpleChannel;
*///?} else {
/*import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
*///?}
//? if >=1.14 {
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.ResourceManager;
//?} else {
/*import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
*///?}
//? if >=1.17 {
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
*///?}
//? if >=1.19 {
import net.minecraftforge.event.GameShuttingDownEvent;
//?}
//? if <1.21.6 {
import net.minecraftforge.common.MinecraftForge;
//?}
//? if >=1.17 {
import net.minecraftforge.fml.CrashReportCallables;
//?} else {
/*import net.minecraftforge.fml.CrashReportExtender;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.common.ICrashCallable;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
*///?}
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//? if >=1.14.4 {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.minecraftforge.fml.common.gameevent.TickEvent;
*///?}
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if >=1.18 <1.21.3 {
import net.minecraftforge.client.event.RenderLevelStageEvent;
//?}
//? if >=1.18 <1.19 {
/*import net.minecraftforge.client.event.RenderLevelLastEvent;
*///?} elif <1.18 {
/*import net.minecraftforge.client.event.RenderWorldLastEvent;
*///?}
//? if >=1.14 <1.21.2 {
import net.minecraft.util.profiling.ProfilerFiller;
//?}
//? if >=1.19 {
import net.minecraftforge.client.event.ViewportEvent;
//?} else {
/*import net.minecraftforge.client.event.EntityViewRenderEvent;
*///?}
//? if >=1.19 {
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
//?} elif >=1.16.5 {
/*import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityLeaveWorldEvent;
*///?} else {
/*import net.minecraftforge.event.entity.EntityJoinWorldEvent;
*///?}

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
//? if <1.20.2 {
import java.util.function.Supplier;
//?}

/**
 * Everything Forge — the mod entry point, its {@link Network} transport and its {@link Events} and
 * {@link ServerEvents} subscriptions.
 *
 * <p>Registration only: which event, and which stage of it. What the engine then does is
 * {@code LifecycleModern}'s, shared with NeoForge and Fabric.</p>
 *
 * <p><b>No {@code @Mod} and no {@code @EventBusSubscriber} here.</b> One jar carries a Forge variant
 * per era, and Forge's scanner reads every class in it — two variants bearing the same annotation are
 * two mods of one id, which it refuses to load rather than choosing between. The single annotated
 * class is {@link ForgeBootstrap}, which reads {@code variants.json} and constructs this.</p>
 */
public final class CrystalGraphicsForge implements VariantEntry {

    /** @param context Forge's {@link FMLJavaModLoadingContext}, from the bootstrapper. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgraphics.mc.forge.common.* is the only
        // thing that says which one ran. @see CrashVariant
        //? if >=1.17 {
        CrashReportCallables.registerCrashCallable(CrashVariant.LABEL,
                () -> CrashVariant.report(CrystalGraphicsForge.class));
        //?} else {
        /*// Forge 29-31 keep callables in a plain list that ForgeMod iterates while mods construct on
        // parallel workers, so registering here races it: register on the main thread after setup.
        ((FMLJavaModLoadingContext) context).getModEventBus().addListener((FMLCommonSetupEvent event) ->
                DeferredWorkQueue.runLater(() -> CrashReportExtender.registerCrashCallable(new ICrashCallable() {
                    @Override
                    public String getLabel() {
                        return CrashVariant.LABEL;
                    }

                    @Override
                    public String call() {
                        return CrashVariant.report(CrystalGraphicsForge.class);
                    }
                })));
        *///?}
        CgPlatform.register(PlatformServiceModern.getInstance());
        NetworkModern.install(Network.register());
        ServerEvents.register();

        // EVERY subscription here is a render hook, so the whole of Events is client-only -- guarded
        // at the call site rather than inside, so a dedicated server never links one of those types.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Events.register((FMLJavaModLoadingContext) context);
        }
    }

    // -- Events -----------------------------------------------------------------

    /** Forge's engine event subscriptions, all of them client-side. */
    public static final class Events {

        private Events() {}

        /** From the entry point rather than from an annotation; see the class note. */
        static void register(FMLJavaModLoadingContext context) {
            // Forge 56's EventBus 7: every event carries its own bus, and a mod-bus event hands out one
            // per mod's bus group. Forge 64 (26.1) made the reload event a global one.
            //? if >=26.1 {
            /*RegisterClientReloadListenersEvent.BUS.addListener(Events::onRegisterReloadListeners);
            GameShuttingDownEvent.BUS.addListener(Events::onGameShuttingDown);
            *///?} elif >=1.21.6 {
            /*RegisterClientReloadListenersEvent.getBus(context.getModBusGroup())
                    .addListener(Events::onRegisterReloadListeners);
            GameShuttingDownEvent.BUS.addListener(Events::onGameShuttingDown);
            *///?} elif >=1.17 {
            context.getModEventBus().addListener(Events::onRegisterReloadListeners);
            //?} elif >=1.14 {
            /*// Forge 28-31 have no reload-listener event; the client's manager exists by mod construction.
            ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                    .registerReloadListener(Events::reload);
            *///?} else {
            /*// 1.13 reloads synchronously, and has no preparation stage to wait on.
            ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                    .registerReloadListener((ResourceManagerReloadListener) manager -> LifecycleModern.reload());
            *///?}
            // Below 1.19 Forge has no shutdown event; process exit frees the context there.
            //? if >=1.19 <1.21.6 {
            MinecraftForge.EVENT_BUS.addListener(Events::onGameShuttingDown);
            //?}
            // The frame end, once a frame after the GUI too: RenderTickEvent at END, a Post of its own from
            // Forge 49 (1.20.4), on its own bus from EventBus 7.
            //? if >=1.21.6 {
            /*TickEvent.RenderTickEvent.Post.BUS.addListener(Events::onFrameEnd);
            *///?} else {
            MinecraftForge.EVENT_BUS.addListener(Events::onFrameEnd);
            //?}
            // The world events, once a client tick: ClientTickEvent at END, with the same Post and bus as above.
            //? if >=1.21.6 {
            /*TickEvent.ClientTickEvent.Post.BUS.addListener(Events::onClientTick);
            *///?} else {
            MinecraftForge.EVENT_BUS.addListener(Events::onClientTick);
            //?}
            // The client connection. Forge 25-27 have no ClientPlayerNetworkEvent: onClientTick polls it there.
            //? if >=1.21.6 {
            /*ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(Events::onLoggedIn);
            ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(Events::onLoggedOut);
            *///?} elif >=1.14.4 {
            MinecraftForge.EVENT_BUS.addListener(Events::onLoggedIn);
            MinecraftForge.EVENT_BUS.addListener(Events::onLoggedOut);
            //?}
            // And as entities join and leave the client level: Forge has no leave event before 1.16.5.
            //? if >=1.21.6 {
            /*EntityJoinLevelEvent.BUS.addListener(Events::onEntityJoin);
            EntityLeaveLevelEvent.BUS.addListener(Events::onEntityLeave);
            *///?} elif >=1.16.5 {
            MinecraftForge.EVENT_BUS.addListener(Events::onEntityJoin);
            MinecraftForge.EVENT_BUS.addListener(Events::onEntityLeave);
            //?} else {
            /*MinecraftForge.EVENT_BUS.addListener(Events::onEntityJoin);
            *///?}
            // The camera hooks a shake and an FOV kick are added at (HostCameraModern).
            //? if >=1.21.6 {
            /*ViewportEvent.ComputeCameraAngles.BUS.addListener(Events::onCameraAngles);
            ViewportEvent.ComputeFov.BUS.addListener(Events::onFov);
            *///?} else {
            MinecraftForge.EVENT_BUS.addListener(Events::onCameraAngles);
            MinecraftForge.EVENT_BUS.addListener(Events::onFov);
            //?}
            // Forge 26.1.1 to 26.2 post the camera-angle event after renderLevel has taken the view: their angles
            // come from the CameraHook node mixin instead.
            HostCameraModern.declare(CgHostCamera.ROTATION | CgHostCamera.ROLL | CgHostCamera.FOV);
            // ExplosionHook and LevelEventHook, the node mixins that report them.
            //? if >=1.21.3 {
            /*CgWorldEvents.declare(CgWorldEvents.EXPLOSION | CgWorldEvents.BLOCK_BROKEN);
            *///?}
            // Forge 53 (1.21.3) removed the render-stage event; from there the passes are a mixin's.
            // @see com.crystalgraphics.mc.modern.forge.mixin.OpaquePassHook
            //? if >=1.21.3 {
            /*// (the mixins)
            *///?} elif >=1.19 {
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            //?} elif >=1.18 {
            /*// The stage event arrived in Forge 40 (1.18.2); 1.18 and 1.18.1 have only the end of the level.
            if (hasStageEvent()) {
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            } else {
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelLast);
            }
            *///?} else {
            /*MinecraftForge.EVENT_BUS.addListener(Events::onRenderWorldLast);
            *///?}
        }

        //? if >=1.18 <1.19 {
        /*private static boolean hasStageEvent() {
            try {
                Class.forName("net.minecraftforge.client.event.RenderLevelStageEvent", false,
                        Events.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        // Both passes at the end of the level: after translucent terrain, so the opaque pass is late.
        private static void onRenderLevelLast(RenderLevelLastEvent event) {
            LifecycleModern.opaquePass(event.getPartialTick());
            LifecycleModern.transparentPass();
        }
        *///?} elif <1.18 {
        /*// Forge 37 has no render stages at all: both passes at the end of the level.
        private static void onRenderWorldLast(RenderWorldLastEvent event) {
            LifecycleModern.opaquePass(event.getPartialTicks());
            LifecycleModern.transparentPass();
        }
        *///?}

        //? if >=1.17 {
        private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(Events::reload);
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
        *///?} elif >=1.14 {
        private static CompletableFuture<Void> reload(PreparationBarrier stage, ResourceManager manager,
                                                      ProfilerFiller prepare, ProfilerFiller apply,
                                                      Executor background, Executor game) {
            return stage.wait(null).thenRunAsync(LifecycleModern::reload, game);
        }
        //?}

        // AFTER_BLOCK_ENTITIES fires after block entities, before renderChunkLayer(translucent); it
        // arrived in Forge 44 (1.19.3). Before that the last stage ahead of translucent terrain is
        // AFTER_CUTOUT_BLOCKS, which is also ahead of entities. The transparent pass runs after clouds and
        // weather, so hazes bend them: AFTER_LEVEL from Forge 46 (1.20), after Fabulous composites them too;
        // before it AFTER_WEATHER, ahead of that composite.
        //? if >=1.21.3 {
        /*// (the mixins)
        *///?} elif >=1.20 {
        private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
            LifecycleModern.transparentPass();
        }
        //?} elif >=1.19.3 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
            LifecycleModern.transparentPass();
        }
        *///?} elif >=1.18 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
            LifecycleModern.transparentPass();
        }
        *///?}

        //? if >=1.20.4 {
        /*private static void onFrameEnd(TickEvent.RenderTickEvent.Post event) {
            LifecycleModern.frameEnd();
        }
        *///?} else {
        private static void onFrameEnd(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) LifecycleModern.frameEnd();
        }
        //?}

        // Client levels only: in single player the integrated server's levels post these too.
        //? if >=1.19 {
        private static void onEntityJoin(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide()) LifecycleModern.entityJoined(event.getEntity());
        }

        private static void onEntityLeave(EntityLeaveLevelEvent event) {
            if (event.getLevel().isClientSide()) LifecycleModern.entityLeft(event.getEntity());
        }
        //?} elif >=1.16.5 {
        /*private static void onEntityJoin(EntityJoinWorldEvent event) {
            if (event.getWorld().isClientSide()) LifecycleModern.entityJoined(event.getEntity());
        }

        private static void onEntityLeave(EntityLeaveWorldEvent event) {
            if (event.getWorld().isClientSide()) LifecycleModern.entityLeft(event.getEntity());
        }
        *///?} else {
        /*private static void onEntityJoin(EntityJoinWorldEvent event) {
            if (event.getWorld().isClientSide()) LifecycleModern.entityJoined(event.getEntity());
        }
        *///?}

        //? if >=1.19 {
        private static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            NetworkModern.clientConnected();
        }

        private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            NetworkModern.clientDisconnected();
        }
        //?} elif >=1.14.4 {
        /*private static void onLoggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
            NetworkModern.clientConnected();
        }

        private static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            NetworkModern.clientDisconnected();
        }
        *///?}

        //? if >=1.20.4 {
        /*private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
            LifecycleModern.clientTick();
        }
        *///?} elif >=1.14.4 {
        private static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) LifecycleModern.clientTick();
        }
        //?} else {
        /*private static boolean connected;

        // getConnection() is the player's, so it appears where a logged-in event would fire.
        private static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            boolean now = Minecraft.getInstance().getConnection() != null;
            if (now != connected) {
                connected = now;
                if (now) NetworkModern.clientConnected();
                else NetworkModern.clientDisconnected();
            }
            LifecycleModern.clientTick();
        }
        *///?}

        //? if >=1.19 {
        private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        //?} else {
        /*private static void onCameraAngles(EntityViewRenderEvent.CameraSetup event) {
        *///?}
            event.setYaw(HostCameraModern.yaw(event.getYaw()));
            event.setPitch(HostCameraModern.pitch(event.getPitch()));
            event.setRoll(HostCameraModern.roll(event.getRoll()));
        }

        // The FOV is a double to 1.21.1 and a float after; HostCameraModern.fov takes either.
        //? if >=1.19 {
        private static void onFov(ViewportEvent.ComputeFov event) {
        //?} elif >=1.18 {
        /*private static void onFov(EntityViewRenderEvent.FieldOfView event) {
        *///?} else {
        /*private static void onFov(EntityViewRenderEvent.FOVModifier event) {
        *///?}
            event.setFOV(HostCameraModern.fov(event.getFOV()));
        }

        //? if >=1.19 {
        private static void onGameShuttingDown(GameShuttingDownEvent event) {
            LifecycleModern.shutdown();
        }
        //?}
    }

    // -- Server events ----------------------------------------------------------

    /**
     * The connection lifecycle's server half. Both sides: a dedicated server opens connections, and single player's
     * integrated server is a server.
     */
    public static final class ServerEvents {

        private ServerEvents() {}

        static void register() {
            //? if >=1.21.6 {
            /*ServerAboutToStartEvent.BUS.addListener(event -> NetworkModern.serverStarting(event.getServer()));
            ServerStoppingEvent.BUS.addListener(event -> NetworkModern.serverStopping());
            TickEvent.ServerTickEvent.Post.BUS.addListener(event -> NetworkModern.serverTick());
            PlayerEvent.PlayerLoggedInEvent.BUS.addListener(ServerEvents::onPlayerJoin);
            PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(ServerEvents::onPlayerLeave);
            *///?} else {
            MinecraftForge.EVENT_BUS.addListener(ServerEvents::onServerStarting);
            MinecraftForge.EVENT_BUS.addListener(ServerEvents::onServerStopping);
            MinecraftForge.EVENT_BUS.addListener(ServerEvents::onServerTick);
            MinecraftForge.EVENT_BUS.addListener(ServerEvents::onPlayerJoin);
            MinecraftForge.EVENT_BUS.addListener(ServerEvents::onPlayerLeave);
            //?}
        }

        //? if >=1.18 {
        private static void onServerStarting(ServerAboutToStartEvent event) {
            NetworkModern.serverStarting(event.getServer());
        }

        private static void onServerStopping(ServerStoppingEvent event) {
            NetworkModern.serverStopping();
        }
        //?} else {
        /*private static void onServerStarting(FMLServerAboutToStartEvent event) {
            NetworkModern.serverStarting(event.getServer());
        }

        private static void onServerStopping(FMLServerStoppingEvent event) {
            NetworkModern.serverStopping();
        }
        *///?}

        //? if <1.21.6 {
        private static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) NetworkModern.serverTick();
        }
        //?}

        // Forge 41 (1.19) renamed getPlayer to getEntity.
        //? if >=1.19 {
        private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer) NetworkModern.playerJoined((ServerPlayer) event.getEntity());
        }

        private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer) NetworkModern.playerLeft((ServerPlayer) event.getEntity());
        }
        //?} else {
        /*private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getPlayer() instanceof ServerPlayer) NetworkModern.playerJoined((ServerPlayer) event.getPlayer());
        }

        private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getPlayer() instanceof ServerPlayer) NetworkModern.playerLeft((ServerPlayer) event.getPlayer());
        }
        *///?}
    }

    // -- Network ----------------------------------------------------------------

    /**
     * The Forge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. A peer without the
     * channel is accepted; what it may be sent is the protocol's business.
     */
    public static final class Network implements CgNetworkChannel {

        //? if >=1.20.2 {
        /*// Forge 48+ rewrote networking and no payload split is measured there, so a frame stays under
        // vanilla's 32767-byte serverbound cap.
        private static final int MAX_FRAME_BYTES = 32_000;

        private static final SimpleChannel CHANNEL = ChannelBuilder
                .named(ResourceIds.of(CrystalGraphics.MODID, "wire"))
                .networkProtocolVersion(1)
                .optional()
                .simpleChannel();
        *///?} else {
        private static final String VERSION = "1";

        /**
         * Forge splits a payload across partials above ~1 MB. Staying under it keeps one frame one packet,
         * which is what the multiplexer above assumes when it sizes its chunks.
         */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(ResourceIds.of(CrystalGraphics.MODID, "wire"))
                .networkProtocolVersion(() -> VERSION)
                .clientAcceptedVersions(Network::accepts)
                .serverAcceptedVersions(Network::accepts)
                .simpleChannel();

        // An absent peer is accepted. Forge 32 (1.16.5) added acceptMissingOr; before it the markers are compared.
        private static boolean accepts(String remote) {
            //? if >=1.16.5 {
            return NetworkRegistry.acceptMissingOr(VERSION).test(remote);
            //?} else {
            /*return VERSION.equals(remote) || NetworkRegistry.ABSENT.equals(remote)
                    || NetworkRegistry.ACCEPTVANILLA.equals(remote);
            *///?}
        }
        //?}

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        /** Called once from the mod entry point, before anything can send. */
        static Network register() {
            //? if >=1.20.2 {
            /*// consumerMainThread: handlers run on the game thread. getSender() is null on the client.
            CHANNEL.messageBuilder(byte[].class, 0)
                    .encoder((frame, buf) -> buf.writeByteArray(frame))
                    .decoder(buf -> buf.readByteArray())
                    .consumerMainThread((frame, ctx) -> INSTANCE.inbound.accept(ctx.getSender(), frame))
                    .add();
            *///?} else {
            CHANNEL.registerMessage(0, byte[].class,
                    (frame, buf) -> buf.writeByteArray(frame),
                    FriendlyByteBuf::readByteArray,
                    Network::receive);
            //?}
            //? if >=1.20.6 {
            /*CHANNEL.build();
            *///?}
            return INSTANCE;
        }

        //? if <1.20.2 {
        private static void receive(byte[] frame, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            // enqueueWork: the handler runs on the network thread; inbound is the game thread's.
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();   // null on the client
                INSTANCE.inbound.accept(sender, frame);
            });
            ctx.setPacketHandled(true);
        }
        //?}

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            //? if >=1.20.2 {
            /*CHANNEL.send(frame, PacketDistributor.SERVER.noArg());
            *///?} else {
            CHANNEL.sendToServer(frame);
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer)) return;
            //? if >=1.20.2 {
            /*CHANNEL.send(frame, PacketDistributor.PLAYER.with((ServerPlayer) player));
            *///?} else {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), frame);
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
