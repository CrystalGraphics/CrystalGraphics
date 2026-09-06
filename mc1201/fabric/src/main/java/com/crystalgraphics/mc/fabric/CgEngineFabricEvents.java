package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.mc.platform.FrameHooks1201;
import com.crystalgraphics.platform.CgPlatform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.MODID;

/**
 * Core engine event registrations for the Fabric loader.
 * Covers the three engine lifecycle concerns: asset reload, render pipeline, and shutdown.
 */
final class CgEngineFabricEvents {
    private CgEngineFabricEvents() {}

    static void register() {
        registerReload();
        registerRenderFrame();
        registerShutdown();
    }

    // ── Asset reload ───────────────────────────────────────────────────────────

    private static void registerReload() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override public ResourceLocation getFabricId() {
                        return new ResourceLocation(MODID, "asset_reload");
                    }
                    @Override public void onResourceManagerReload(ResourceManager manager) {
                        CgPlatform.reload().onReload();
                    }
                });
    }

    // ── Render pipeline ────────────────────────────────────────────────────────

    private static void registerRenderFrame() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().bindWrite(false);
            CgGraphicsLifecycle.onOpaquePass(
                    context.tickDelta(),
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(),
                    mc.getMainRenderTarget().frameBufferId);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().bindWrite(false);
            // Note: CG geometry renders into main FBO outside Iris's GBuffer chain.
            // See CgIrisCompat for detection API if Iris-specific behaviour is needed.
            CgGraphicsLifecycle.onTransparentPass();
            FrameHooks1201.endFrame();
        });
    }

    // ── Shutdown ───────────────────────────────────────────────────────────────

    private static void registerShutdown() {
        // CLIENT_STOPPING fires from Minecraft.stop(), and rendering has NOT finished by then.
        // STOP, DO NOT DISMANTLE. Minecraft dispatches render stages for a frame or two after
        // this fires, so tearing the engine down here deleted every registry and the next frame
        // threw out of a render event -- a crash on quitting. Nothing is freed because the process
        // is ending and the OS reclaims it anyway. @see CgGraphicsLifecycle#shutdown
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CgGraphicsLifecycle.shutdown());
    }
}
