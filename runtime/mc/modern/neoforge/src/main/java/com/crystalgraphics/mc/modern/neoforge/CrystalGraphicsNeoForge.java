package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
import com.mojang.logging.LogUtils;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

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
            NeoForge.EVENT_BUS.addListener(Events::onGameShuttingDown);

            // A SEPARATE CLASS, not a branch here: naming a client-only event type in a method of
            // Events would resolve it when a dedicated server links this class.
            if (FMLEnvironment.dist.isClient()) ModBus.register(modBus);
        }

        // -- MOD bus ----------------------------------------------------------------

        /** Client-only, and a class of its own so a dedicated server never links one of these types. */
        public static final class ModBus {
            private ModBus() {}

            static void register(IEventBus modBus) {
                modBus.addListener(ModBus::onRegisterReloadListeners);
            }

            private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
                event.registerReloadListener(
                        (stage, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
                                stage.wait(null).thenRunAsync(LifecycleModern::reload, gameExecutor));
            }
        }

        // -- NEOFORGE bus -----------------------------------------------------------

        private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1140 (MC 1.20.4),
            // after block entities, before renderSectionLayer(translucent).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            // 1.21 hands a DeltaTracker; `true` is the pause-aware residual 1.20's float already was.
            //? if >=1.21 {
            /*LifecycleModern.opaquePass(event.getPartialTick().getGameTimeDeltaPartialTick(true));
            *///?} else {
            LifecycleModern.opaquePass(event.getPartialTick());
            //?}
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1215/1230 (MC 1.20.4),
            // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            LifecycleModern.transparentPass();
        }

        private static void onGameShuttingDown(GameShuttingDownEvent event) {
            LifecycleModern.shutdown();
        }
    }
}
