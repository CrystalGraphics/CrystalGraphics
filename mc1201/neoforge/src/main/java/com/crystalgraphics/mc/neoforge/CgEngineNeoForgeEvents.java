package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.mc.platform.Lifecycle1201;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * NeoForge's engine event subscriptions — <b>registration only</b>.
 *
 * <p>Which event, and which stage of it. Everything the engine then does is
 * {@link Lifecycle1201}'s, shared with Forge and Fabric.</p>
 */
public final class CgEngineNeoForgeEvents {
    private CgEngineNeoForgeEvents() {}

    /** Called once from {@link CrystalGraphics1201NeoForge} constructor. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onRenderLevelOpaque);
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onRenderLevelTransparent);
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onGameShuttingDown);
    }

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

    // -- NEOFORGE bus -----------------------------------------------------------

    private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
        // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1140 (MC 1.20.4),
        // after block entities, before renderSectionLayer(translucent).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Lifecycle1201.opaquePass(event.getPartialTick());
    }

    private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
        // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1215/1230 (MC 1.20.4),
        // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Lifecycle1201.transparentPass();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        Lifecycle1201.shutdown();
    }
}
