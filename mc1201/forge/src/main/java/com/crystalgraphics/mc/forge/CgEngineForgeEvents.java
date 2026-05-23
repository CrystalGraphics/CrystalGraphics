package com.crystalgraphics.mc.forge;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Core engine event subscriptions for the Forge loader.
 * Covers the three engine lifecycle concerns: asset reload, render pipeline, and shutdown.
 */
public final class CgEngineForgeEvents {
    private CgEngineForgeEvents() {}

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

    // ── FORGE bus ──────────────────────────────────────────────────────────────

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1311,
            // after block entities, before renderChunkLayer(translucent).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().bindWrite(false);
            CgGraphicsLifecycle.onOpaquePass(
                    event.getPartialTick(),
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(),
                    mc.getMainRenderTarget().frameBufferId);
        }

        @SubscribeEvent
        public static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1379/1394,
            // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().bindWrite(false);
            // Note: CG geometry renders into main FBO outside Iris's GBuffer chain.
            // See CgIrisCompat for detection API if Iris-specific behaviour is needed.
            CgGraphicsLifecycle.onTransparentPass();
        }

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            CgPlatform.lifecycle().onContextDestroy();
        }
    }
}
