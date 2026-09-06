package com.crystalgraphics.mc.platform;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;

import net.minecraft.client.Minecraft;

/**
 * <b>The one class an mc1201 loader talks to</b> — everything the engine does per frame, per reload
 * and at shutdown, written once for Forge, NeoForge and Fabric.
 *
 * <p>A loader subscribes its own events and forwards; it holds no engine logic of its own. That is the
 * whole point: the three used to carry the same four bodies, so a fix landed in one and the other two
 * kept the bug — silently, since each loader is only ever run on its own.</p>
 *
 * <pre>{@code
 * // Forge / NeoForge
 * if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)
 *     Lifecycle1201.opaquePass(event.getPartialTick());
 *
 * // Fabric
 * WorldRenderEvents.AFTER_ENTITIES.register(ctx -> Lifecycle1201.opaquePass(ctx.tickDelta()));
 * }</pre>
 *
 * <p>What stays in a loader is the part that genuinely differs: which event to subscribe to, and which
 * stage of it counts. Everything after that is here.</p>
 */
public final class Lifecycle1201 {

    private Lifecycle1201() {
    }

    /**
     * Minecraft has drawn its opaque world; run the engine's opaque passes.
     *
     * <p>Forge and NeoForge call this from {@code RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES},
     * Fabric from {@code WorldRenderEvents.AFTER_ENTITIES} — the same moment, after block entities and
     * before the translucent chunk layer.</p>
     *
     * @param partialTick the loader's frame interpolation factor
     */
    public static void opaquePass(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        // THE MAIN TARGET, RE-BOUND. Fabulous graphics leaves one of its OIT targets bound, and the
        // engine's passes would draw into whichever that was.
        mc.getMainRenderTarget().bindWrite(false);
        CgGraphicsLifecycle.onOpaquePass(
                partialTick,
                mc.getWindow().getWidth(),
                mc.getWindow().getHeight(),
                mc.getMainRenderTarget().frameBufferId);
    }

    /**
     * Minecraft has drawn its translucent world; run the engine's transparent pass and end the frame.
     *
     * <p>Forge and NeoForge call this from {@code Stage.AFTER_PARTICLES}, Fabric from
     * {@code WorldRenderEvents.AFTER_TRANSLUCENT} — after translucent terrain, tripwire and particles,
     * with and without Fabulous.</p>
     *
     * <p>Engine geometry lands in the main FBO, outside Iris's GBuffer chain; {@code CgIrisCompat} is
     * the detection API if that ever needs handling.</p>
     */
    public static void transparentPass() {
        Minecraft mc = Minecraft.getInstance();
        mc.getMainRenderTarget().bindWrite(false);
        CgGraphicsLifecycle.onTransparentPass();
        FrameHooks1201.endFrame();
    }

    /** A resource reload landed — drop every cache built from assets. */
    public static void reload() {
        CgPlatform.reload().onReload();
    }

    /**
     * The game is closing.
     *
     * <p>Stops the engine and frees nothing: Minecraft keeps dispatching render stages after its
     * shutdown signal. @see CgGraphicsLifecycle#shutdown</p>
     */
    public static void shutdown() {
        CgGraphicsLifecycle.shutdown();
    }
}
