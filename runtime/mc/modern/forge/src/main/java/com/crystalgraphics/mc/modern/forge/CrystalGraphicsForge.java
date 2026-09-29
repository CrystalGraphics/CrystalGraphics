package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraftforge.api.distmarker.Dist;
//? if >=1.14 {
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.ResourceManager;
//?} else {
/*import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
*///?}
//? if >=1.17 {
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
*///?}
//? if >=1.19 {
import net.minecraftforge.event.GameShuttingDownEvent;
//?}
//? if <1.21.6 {
import net.minecraftforge.common.MinecraftForge;
//?}
//? if >=1.17 {
import net.minecraftforge.fml.CrashReportCallables;
//?} else {
/*import net.minecraftforge.fml.CrashReportExtender;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.common.ICrashCallable;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
*///?}
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if >=1.18 <1.21.3 {
import net.minecraftforge.client.event.RenderLevelStageEvent;
//?}
//? if >=1.18 <1.19 {
/*import net.minecraftforge.client.event.RenderLevelLastEvent;
*///?} elif <1.18 {
/*import net.minecraftforge.client.event.RenderWorldLastEvent;
*///?}
//? if >=1.14 <1.21.2 {
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
        //? if >=1.17 {
        CrashReportCallables.registerCrashCallable(CrashVariant.LABEL,
                () -> CrashVariant.report(CrystalGraphicsForge.class));
        //?} else {
        /*// Forge 29-31 keep callables in a plain list that ForgeMod iterates while mods construct on
        // parallel workers, so registering here races it: register on the main thread after setup.
        ((FMLJavaModLoadingContext) context).getModEventBus().addListener((FMLCommonSetupEvent event) ->
                DeferredWorkQueue.runLater(() -> CrashReportExtender.registerCrashCallable(new ICrashCallable() {
                    @Override
                    public String getLabel() {
                        return CrashVariant.LABEL;
                    }

                    @Override
                    public String call() {
                        return CrashVariant.report(CrystalGraphicsForge.class);
                    }
                })));
        *///?}
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
            *///?} elif >=1.17 {
            context.getModEventBus().addListener(Events::onRegisterReloadListeners);
            //?} elif >=1.14 {
            /*// Forge 28-31 have no reload-listener event; the client's manager exists by mod construction.
            ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                    .registerReloadListener(Events::reload);
            *///?} else {
            /*// 1.13 reloads synchronously, and has no preparation stage to wait on.
            ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                    .registerReloadListener((ResourceManagerReloadListener) manager -> LifecycleModern.reload());
            *///?}
            // Below 1.19 Forge has no shutdown event; process exit frees the context there.
            //? if >=1.19 <1.21.6 {
            MinecraftForge.EVENT_BUS.addListener(Events::onGameShuttingDown);
            //?}
            // Forge 53 (1.21.3) removed the render-stage event; from there the passes are a mixin's.
            // @see com.crystalgraphics.mc.modern.forge.mixin.OpaquePassHook
            //? if >=1.21.3 {
            /*// (the mixins)
            *///?} elif >=1.19 {
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            //?} elif >=1.18 {
            /*// The stage event arrived in Forge 40 (1.18.2); 1.18 and 1.18.1 have only the end of the level.
            if (hasStageEvent()) {
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelOpaque);
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelTransparent);
            } else {
                MinecraftForge.EVENT_BUS.addListener(Events::onRenderLevelLast);
            }
            *///?} else {
            /*MinecraftForge.EVENT_BUS.addListener(Events::onRenderWorldLast);
            *///?}
        }

        //? if >=1.18 <1.19 {
        /*private static boolean hasStageEvent() {
            try {
                Class.forName("net.minecraftforge.client.event.RenderLevelStageEvent", false,
                        Events.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        // Both passes at the end of the level: after translucent terrain, so the opaque pass is late.
        private static void onRenderLevelLast(RenderLevelLastEvent event) {
            LifecycleModern.opaquePass(event.getPartialTick());
            LifecycleModern.transparentPass();
        }
        *///?} elif <1.18 {
        /*// Forge 37 has no render stages at all: both passes at the end of the level.
        private static void onRenderWorldLast(RenderWorldLastEvent event) {
            LifecycleModern.opaquePass(event.getPartialTicks());
            LifecycleModern.transparentPass();
        }
        *///?}

        //? if >=1.17 {
        private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(Events::reload);
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
        *///?} elif >=1.14 {
        private static CompletableFuture<Void> reload(PreparationBarrier stage, ResourceManager manager,
                                                      ProfilerFiller prepare, ProfilerFiller apply,
                                                      Executor background, Executor game) {
            return stage.wait(null).thenRunAsync(LifecycleModern::reload, game);
        }
        //?}

        // AFTER_BLOCK_ENTITIES fires after block entities, before renderChunkLayer(translucent); it
        // arrived in Forge 44 (1.19.3). Before that the last stage ahead of translucent terrain is
        // AFTER_CUTOUT_BLOCKS, which is also ahead of entities. AFTER_PARTICLES follows translucent
        // terrain, tripwire and particles, Fabulous or not.
        //? if >=1.21.3 {
        /*// (the mixins)
        *///?} elif >=1.19.3 {
        private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            LifecycleModern.transparentPass();
        }
        //?} elif >=1.18 {
        /*private static void onRenderLevelOpaque(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
            LifecycleModern.opaquePass(event.getPartialTick());
        }

        private static void onRenderLevelTransparent(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            LifecycleModern.transparentPass();
        }
        *///?}

        //? if >=1.19 {
        private static void onGameShuttingDown(GameShuttingDownEvent event) {
            LifecycleModern.shutdown();
        }
        //?}
    }
}
