package com.crystalgraphics.mc.forge;

import com.crystalgraphics.mc.platform.Lifecycle1201;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Forge's engine event subscriptions — <b>registration only</b>.
 *
 * <p>Which event, and which stage of it. Everything the engine then does is
 * {@link Lifecycle1201}'s, shared with NeoForge and Fabric.</p>
 */
public final class CgEngineForgeEvents {
    private CgEngineForgeEvents() {}

    // -- MOD bus ----------------------------------------------------------------

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (stage, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
                            stage.wait(null).thenRunAsync(Lifecycle1201::reload, gameExecutor));
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
            Lifecycle1201.opaquePass(event.getPartialTick());
        }

        @SubscribeEvent
        public static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1379/1394,
            // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            Lifecycle1201.transparentPass();
        }

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            Lifecycle1201.shutdown();
        }
    }
}
