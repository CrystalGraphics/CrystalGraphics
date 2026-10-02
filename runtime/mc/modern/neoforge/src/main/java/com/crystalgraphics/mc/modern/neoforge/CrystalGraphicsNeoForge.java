package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
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
/*import net.neoforged.neoforge.client.event.RenderFrameEvent;
*///?} else {
import net.neoforged.neoforge.event.TickEvent;
//?}
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
//? if >=1.21.4 {
/*import com.crystalgraphics.mc.modern.platform.ResourceIds;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
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
 * Everything NeoForge — the mod entry point and its {@link Events} subscriptions.
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

            // A SEPARATE CLASS, not a branch here: naming a client-only event type in a method of
            // Events would resolve it when a dedicated server links this class.
            if (FmlSide.isClient(FMLLoader.class)) ModBus.register(modBus);
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
}
