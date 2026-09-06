package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.mc.platform.Lifecycle1201;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Fabric's engine event registrations — <b>registration only</b>.
 *
 * <p>Which callback to hook. Everything the engine then does is {@link Lifecycle1201}'s, shared with
 * Forge and NeoForge.</p>
 */
final class CgEngineFabricEvents {
    private CgEngineFabricEvents() {}

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
                        Lifecycle1201.reload();
                    }
                });
    }

    // -- Render pipeline --------------------------------------------------------

    private static void registerRenderFrame() {
        // AFTER_ENTITIES and AFTER_TRANSLUCENT are Fabric's names for the two moments Forge calls
        // AFTER_BLOCK_ENTITIES and AFTER_PARTICLES.
        WorldRenderEvents.AFTER_ENTITIES.register(context -> Lifecycle1201.opaquePass(context.tickDelta()));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> Lifecycle1201.transparentPass());
    }

    // -- Shutdown ---------------------------------------------------------------

    private static void registerShutdown() {
        // CLIENT_STOPPING fires from Minecraft.stop(), and rendering has NOT finished by then.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Lifecycle1201.shutdown());
    }
}
