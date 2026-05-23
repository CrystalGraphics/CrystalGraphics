package com.crystalgraphics.mc.neoforge;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Core engine event subscriptions for the NeoForge loader.
 * Covers the three engine lifecycle concerns: asset reload, render pipeline, and shutdown.
 */
public final class CgEngineNeoForgeEvents {
    private CgEngineNeoForgeEvents() {}

    /** Called once from {@link CrystalGraphics1201NeoForge} constructor. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onRenderLevelOpaque);
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onRenderLevelTransparent);
        NeoForge.EVENT_BUS.addListener(CgEngineNeoForgeEvents::onGameShuttingDown);
    }

    // ── MOD bus ────────────────────────────────────────────────────────────────

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (stage, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
                            stage.wait(null).thenRunAsync(() -> CgPlatform.reload().onReload(), gameExecutor));
        }
    }

    // ── NEOFORGE bus ───────────────────────────────────────────────────────────

    private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
        // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1140 (MC 1.20.4),
        // after block entities, before renderSectionLayer(translucent).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        mc.getMainRenderTarget().bindWrite(false);
        CgGraphicsLifecycle.onOpaquePass(
                event.getPartialTick(),
                mc.getWindow().getWidth(),
                mc.getWindow().getHeight(),
                mc.getMainRenderTarget().frameBufferId);
    }

    private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
        // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1215/1230 (MC 1.20.4),
        // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        mc.getMainRenderTarget().bindWrite(false);
        // Note: CG geometry renders into main FBO outside Iris's GBuffer chain.
        // See CgIrisCompat for detection API if Iris-specific behaviour is needed.
        CgGraphicsLifecycle.onTransparentPass();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        CgPlatform.lifecycle().onContextDestroy();
    }
}
