package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
//? if <1.21.6 {
import net.minecraftforge.common.MinecraftForge;
//?}
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if <1.21.3 {
import net.minecraftforge.client.event.RenderLevelStageEvent;
//?}
//? if <1.21.2 {
import net.minecraft.util.profiling.ProfilerFiller;
//?}

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Everything Forge — the mod entry point and its {@link Events} subscriptions.
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
        CrashReportCallables.registerCrashCallable(CrashVariant.LABEL,
                () -> CrashVariant.report(CrystalGraphicsForge.class));
        CgPlatform.register(PlatformServiceModern.getInstance());

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
            // per mod's bus group.
            //? if >=1.21.6 {
            /*RegisterClientReloadListenersEvent.getBus(context.getModBusGroup())
                    .addListener(Events::onRegisterReloadListeners);
            GameShuttingDownEvent.BUS.addListener(Events::onGameShuttingDown);
            *///?} else {
            context.getModEventBus().addListener(Events::onRegisterReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(Events::onGameShuttingDown);
            //?}
            // Forge 53 (1.21.3) removed the render-stage event; from there the passes are a mixin's.
            // @see com.crystalgraphics.mc.modern.forge.mixin.OpaquePassHook
            //? if <1.21.3 {
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            //?}
        }

        private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(Events::reload);
        }

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

        //? if <1.21.3 {
        private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            // Validated: AFTER_BLOCK_ENTITIES fires at LevelRenderer.java line ~1311,
            // after block entities, before renderChunkLayer(translucent).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            // Validated: AFTER_PARTICLES fires at LevelRenderer.java line ~1379/1394,
            // after translucent terrain + tripwire + particles (both Fabulous and non-Fabulous).
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            LifecycleModern.transparentPass();
        }
        //?}

        private static void onGameShuttingDown(GameShuttingDownEvent event) {
            LifecycleModern.shutdown();
        }
    }
}
