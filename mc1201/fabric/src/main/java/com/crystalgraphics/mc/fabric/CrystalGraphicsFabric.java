package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * Everything Fabric that is <b>client only</b> — the client entry point and its {@link Events}
 * subscriptions, which are all render hooks.
 *
 * <p>The platform bundle is registered by {@link CrystalGraphicsFabricCommon}, which runs on both
 * sides and runs first: Fabric drains {@code main} entrypoints before {@code client} ones, and a
 * dedicated server runs no {@code client} entrypoint at all.</p>
 */
public final class CrystalGraphicsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Events.register();
    }

    // -- Events -----------------------------------------------------------------

    /** Fabric's engine event registrations. */
    static final class Events {

        private Events() {}

        static void register() {
            registerReload();
            registerRenderFrame();
            registerShutdown();
        }

        // -- Asset reload -----------------------------------------------------------

        private static void registerReload() {
            ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                    new SimpleSynchronousResourceReloadListener() {
                        @Override public ResourceLocation getFabricId() {
                            return new ResourceLocation(MODID, "asset_reload");
                        }
                        @Override public void onResourceManagerReload(ResourceManager manager) {
                            LifecycleModern.reload();
                        }
                    });
        }

        // -- Render pipeline --------------------------------------------------------

        private static void registerRenderFrame() {
            // AFTER_ENTITIES and AFTER_TRANSLUCENT are Fabric's names for the two moments Forge calls
            // AFTER_BLOCK_ENTITIES and AFTER_PARTICLES.
            WorldRenderEvents.AFTER_ENTITIES.register(context -> LifecycleModern.opaquePass(context.tickDelta()));
            WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> LifecycleModern.transparentPass());
        }

        // -- Shutdown ---------------------------------------------------------------

        private static void registerShutdown() {
            // CLIENT_STOPPING fires from Minecraft.stop(), and rendering has NOT finished by then.
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LifecycleModern.shutdown());
        }
    }
}
