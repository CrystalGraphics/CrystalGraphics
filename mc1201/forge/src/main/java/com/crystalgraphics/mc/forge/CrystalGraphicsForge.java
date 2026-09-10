package com.crystalgraphics.mc.forge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * Everything Forge — the mod entry point and its {@link Events} subscriptions.
 *
 * <p>Registration only: which event, and which stage of it. What the engine then does is
 * {@code LifecycleModern}'s, shared with NeoForge and Fabric.</p>
 */
@Mod(MODID)
public final class CrystalGraphicsForge {
    
    public CrystalGraphicsForge() {
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgraphics.mc.forge.common.* is the only
        // thing that says which one ran. @see CrashVariant
        CrashReportCallables.registerCrashCallable(CrashVariant.LABEL,
                () -> CrashVariant.report(CrystalGraphicsForge.class));
        CgPlatform.register(PlatformServiceModern.getInstance());
    }

    // -- Events -----------------------------------------------------------------

    /** Forge's engine event subscriptions. */
    public static final class Events {

        private Events() {}

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

        // -- FORGE bus --------------------------------------------------------------

        @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
        public static final class ForgeBus {
            private ForgeBus() {}

            @SubscribeEvent
            public static void onRenderLevelOpaque(RenderLevelStageEvent event) {
                // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1311,
                // after block entities, before renderChunkLayer(translucent).
                if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
                LifecycleModern.opaquePass(event.getPartialTick());
            }

            @SubscribeEvent
            public static void onRenderLevelTransparent(RenderLevelStageEvent event) {
                // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1379/1394,
                // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
                if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
                LifecycleModern.transparentPass();
            }

            @SubscribeEvent
            public static void onGameShuttingDown(GameShuttingDownEvent event) {
                LifecycleModern.shutdown();
            }
        }
    }
}
