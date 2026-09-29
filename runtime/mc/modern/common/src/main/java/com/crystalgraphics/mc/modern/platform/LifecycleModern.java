package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;

import net.minecraft.client.Minecraft;
//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL30;
*///?}

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
 *     LifecycleModern.opaquePass(event.getPartialTick());
 *
 * // Fabric
 * WorldRenderEvents.AFTER_ENTITIES.register(ctx -> LifecycleModern.opaquePass(ctx.tickDelta()));
 * }</pre>
 *
 * <p>What stays in a loader is the part that genuinely differs: which event to subscribe to, and which
 * stage of it counts. Everything after that is here.</p>
 */
public final class LifecycleModern {

    private LifecycleModern() {
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
        int mainFbo = bindMainTarget(mc);
        CgGraphicsLifecycle.onOpaquePass(
                partialTick,
                Windows.of(mc).getWidth(),
                Windows.of(mc).getHeight(),
                mainFbo);
        // Off unless -Dcrystalgraphics.host.verify=true. @see HostStateVerifier
        HostStateVerifier.verify("opaque");
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
        bindMainTarget(Minecraft.getInstance());
        CgGraphicsLifecycle.onTransparentPass();
        HostStateVerifier.verify("transparent");
        FrameHooks.endFrame();
    }

    /**
     * Binds Minecraft's main target for drawing and answers its GL framebuffer.
     *
     * <pre>{@code
     * int fbo = LifecycleModern.bindMainTarget(Minecraft.getInstance());
     * }</pre>
     *
     * <p>Call it before drawing outside a world pass from 1.21.5, where Minecraft binds a target only
     * inside its own render passes and leaves whichever the last one used. A target there has no
     * framebuffer of its own: its colour texture keeps one per depth attachment.</p>
     */
    public static int bindMainTarget(Minecraft mc) {
        //? if >=1.21.5 {
        /*RenderTarget main = mc.getMainRenderTarget();
        int fbo = ((GlTexture) main.getColorTexture())
                .getFbo(((GlDevice) RenderSystem.getDevice()).directStateAccess(), main.getDepthTexture());
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        // The viewport is the last pass's too -- the lightmap's 16x16, as often as not.
        GlStateManager._viewport(0, 0, main.width, main.height);
        return fbo;
        *///?} else {
        mc.getMainRenderTarget().bindWrite(false);
        return mc.getMainRenderTarget().frameBufferId;
        //?}
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
