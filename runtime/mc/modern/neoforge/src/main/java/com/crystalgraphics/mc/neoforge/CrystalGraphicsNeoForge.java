package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
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
@Mod(MODID)
public final class CrystalGraphicsNeoForge {

    public CrystalGraphicsNeoForge() {
        // WHICH VARIANT, in the log rather than the crash report: NeoForge 20.4 exposes no crash
        // callable — CrashReportExtender is its own — so unlike Forge and 1.7.10 there is nothing to
        // register with, and `latest.log` is the file a report is attached with anyway. @see CrashVariant
        LogUtils.getLogger().info("[cg] {}: {}", CrashVariant.LABEL,
                CrashVariant.report(CrystalGraphicsNeoForge.class));
        CgPlatform.register(PlatformServiceModern.getInstance());
        Events.register();
    }

    // -- Events -----------------------------------------------------------------

    /** NeoForge's engine event subscriptions. */
    public static final class Events {

        private Events() {}

        /** Called once from the mod constructor. */
        static void register() {
            NeoForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
            NeoForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            NeoForge.EVENT_BUS.addListener(Events::onGameShuttingDown);
        }

        // -- MOD bus ----------------------------------------------------------------

        @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
        public static final class ModBus {
            private ModBus() {}

            @SubscribeEvent
            public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
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
            LifecycleModern.opaquePass(event.getPartialTick());
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
