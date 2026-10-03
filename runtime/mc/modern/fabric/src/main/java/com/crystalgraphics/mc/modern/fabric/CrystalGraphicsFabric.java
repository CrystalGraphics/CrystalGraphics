package com.crystalgraphics.mc.modern.fabric;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.modern.net.NetworkModern;
import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
*///?} elif >=1.21.9 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
*///?} elif >=1.16 {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
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
public final class CrystalGraphicsFabric implements VariantEntry {

    /** @param context null — Fabric hands an entry point nothing. */
    @Override
    public void start(Object context) {
        Events.register();
        // What the node mixins apply and report: CameraHook from 1.15 (1.14.4 turns its view from elsewhere, so the hook
        // changes nothing seen) with roll from 1.21.11, FovHook on every version, ExplosionHook on every version,
        // LevelEventHook from 1.15.
        //? if >=1.21.11 {
        /*HostCameraModern.declare(CgHostCamera.ROTATION | CgHostCamera.ROLL);
        *///?} elif >=1.15 {
        HostCameraModern.declare(CgHostCamera.ROTATION);
        //?}
        HostCameraModern.declare(CgHostCamera.FOV);
        CgWorldEvents.declare(CgWorldEvents.EXPLOSION);
        //? if >=1.15 {
        CgWorldEvents.declare(CgWorldEvents.BLOCK_BROKEN);
        //?}
    }

    // -- Events -----------------------------------------------------------------

    /** Fabric's engine event registrations. */
    static final class Events {

        private Events() {}

        static void register() {
            registerReload();
            registerRenderFrame();
            registerShutdown();
            // The world events, once a client tick and as entities join and leave the client level.
            ClientTickEvents.END_CLIENT_TICK.register(client -> LifecycleModern.clientTick());
            ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> LifecycleModern.entityJoined(entity));
            ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> LifecycleModern.entityLeft(entity));
            // The client connection; its tick is LifecycleModern.clientTick's.
            CrystalGraphicsFabricCommon.Network.registerClientReceiver();
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> NetworkModern.clientConnected());
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NetworkModern.clientDisconnected());
        }

        // -- Asset reload -----------------------------------------------------------

        private static void registerReload() {
            ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                    new SimpleSynchronousResourceReloadListener() {
                        @Override public ResourceLocation getFabricId() {
                            return ResourceIds.of(MODID, "asset_reload");
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
            // 1.21 hands a DeltaTracker; `true` is the pause-aware residual 1.20's float already was.
            // Fabric API for 1.21.9 rebuilt these events around the new renderer: BEFORE_TRANSLUCENT is the
            // opaque point, END_MAIN the transparent one, and the context no longer carries the tick.
            // 26.1 renamed them LevelRenderEvents, and the opaque point BEFORE_TRANSLUCENT_TERRAIN.
            //? if >=26.1 {
            /*LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> LifecycleModern.opaquePass(
                    Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true)));
            LevelRenderEvents.END_MAIN.register(context -> LifecycleModern.transparentPass());
            *///?} elif >=1.21.9 {
            /*WorldRenderEvents.BEFORE_TRANSLUCENT.register(context -> LifecycleModern.opaquePass(
                    Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true)));
            WorldRenderEvents.END_MAIN.register(context -> LifecycleModern.transparentPass());
            *///?} elif >=1.21 {
            /*WorldRenderEvents.AFTER_ENTITIES.register(context ->
                    LifecycleModern.opaquePass(context.tickCounter().getGameTimeDeltaPartialTick(true)));
            WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> LifecycleModern.transparentPass());
            *///?} elif >=1.16 {
            WorldRenderEvents.AFTER_ENTITIES.register(context -> LifecycleModern.opaquePass(context.tickDelta()));
            WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> LifecycleModern.transparentPass());
            //?} else {
            /*// Fabric API for 1.15 has no WorldRenderEvents: a node mixin runs both passes.
            // @see com.crystalgraphics.mc.modern.fabric.mixin.WorldPassHook
            *///?}
        }

        // -- Shutdown ---------------------------------------------------------------

        private static void registerShutdown() {
            // CLIENT_STOPPING fires from Minecraft.stop(), and rendering has NOT finished by then.
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LifecycleModern.shutdown());
        }
    }
}
