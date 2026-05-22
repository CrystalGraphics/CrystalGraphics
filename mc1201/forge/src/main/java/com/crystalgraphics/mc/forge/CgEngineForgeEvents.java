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
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
            Minecraft mc = Minecraft.getInstance();
            CgGraphicsLifecycle.onRenderFrame(
                    event.getPartialTick(),
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight());
        }

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            CgPlatform.lifecycle().onContextDestroy();
        }
    }
}
