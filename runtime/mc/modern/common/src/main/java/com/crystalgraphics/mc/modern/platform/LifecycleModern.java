package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
//? if >=26.1 {
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL30;
*///?}
//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
*///?}

/**
 * <b>The one class a modern loader talks to</b> — everything the engine does per frame, per reload
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
        if (!canRender()) return;
        Minecraft mc = Minecraft.getInstance();
        // The target and depth convention below are ours to set, so the bracket opens before them.
        CgGL.fromHost();
        try {
            // THE MAIN TARGET, RE-BOUND. Fabulous graphics leaves one of its OIT targets bound, and the
            // engine's passes would draw into whichever that was.
            int mainFbo = bindMainTarget(mc);
            worldDepth(true);
            try {
                CgGraphicsLifecycle.onOpaquePass(
                        partialTick,
                        Windows.of(mc).getWidth(),
                        Windows.of(mc).getHeight(),
                        mainFbo);
            } finally {
                worldDepth(false);
            }
        } finally {
            CgGL.toHost();
        }
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
        if (!canRender()) return;
        CgGL.fromHost();
        try {
            bindMainTarget(Minecraft.getInstance());
            worldDepth(true);
            try {
                CgGraphicsLifecycle.onTransparentPass();
            } finally {
                worldDepth(false);
            }
        } finally {
            CgGL.toHost();
        }
        HostStateVerifier.verify("transparent");
    }

    /**
     * Draws into Minecraft's world depth as that world does: 26.2 renders reversed-Z, nearer greater and
     * cleared to 0, on OpenGL as on Vulkan. Earlier versions are standard, and this does nothing there.
     * @see CgGL#setDepthReversed
     */
    private static void worldDepth(boolean inWorld) {
        //? if >=26.2 {
        /*CgGL.setDepthReversed(inWorld, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        *///?}
    }

    /**
     * The host has drawn its whole frame, GUI included; end ours. Once per host frame, a world frame
     * and a title-screen frame alike — the resize check, then {@link CgGraphicsLifecycle#tickFrame()},
     * whose {@code onFrame} listeners are the last point to draw over the host's picture.
     *
     * <pre>{@code
     * // Forge: TickEvent.RenderTickEvent at END (Post from 1.20.4); NeoForge: RenderFrameEvent.Post;
     * // Fabric: a node mixin at GameRenderer.render TAIL
     * LifecycleModern.frameEnd();
     * }</pre>
     */
    public static void frameEnd() {
        if (!canRender()) return;
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
        //? if >=26.1 {
        /*RenderTarget main = mainTarget(mc);
        // Meant to stay bound, for our passes and for Minecraft's next draw: handed over, not restored.
        try (CgGlScope ignored = CgGlState.handOver(CgGlSlot.FBO, CgGlSlot.VIEWPORT)) {
            int fbo = mainFbo(main);
            CgGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            CgGL.glViewport(0, 0, main.width, main.height);
            return fbo;
        }
        *///?} elif >=1.21.5 {
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

    // 26.2 moved it from Minecraft to the game renderer.
    //? if >=26.2 {
    /*private static RenderTarget mainTarget(Minecraft mc) {
        return mc.gameRenderer.mainRenderTarget();
    }
    *///?} elif >=26.1 {
    /*private static RenderTarget mainTarget(Minecraft mc) {
        return mc.getMainRenderTarget();
    }
    *///?}

    // Whether the main target's depth carries stencil: vanilla's never does, NeoForge's does when a mod asks.
    //? if >=26.2 {
    /*private static boolean hasStencil(RenderTarget main) {
        return main.getDepthTexture() != null && main.getDepthTexture().getFormat().hasStencilAspect();
    }
    *///?} elif >=26.1 {
    /*private static boolean hasStencil(RenderTarget main) {
        // NeoForge's DEPTH24_STENCIL8 and DEPTH32_STENCIL8; vanilla 26.1 has no stencil format to name.
        return main.getDepthTexture() != null && main.getDepthTexture().getFormat().name().contains("STENCIL");
    }
    *///?}

    // The main target's framebuffer. Under Vulkan (26.2) its textures are Minecraft's images, imported under
    // names of the tracked backend's own; the framebuffer over them is built the same way.
    //? if >=26.2 {
    /*private static int mainFbo(RenderTarget main) {
        if (!GraphicsApi.vulkan()) return mainFbo(glId(main.getColorTexture()), glId(main.getDepthTexture()), hasStencil(main));
        Blaze3dVulkanHost host = Blaze3dVulkanHost.current();
        host.matchSurface(main.width, main.height);
        return mainFbo(host.importTexture(main.getColorTexture()),
                main.getDepthTexture() == null ? 0 : host.importTexture(main.getDepthTexture()), hasStencil(main));
    }
    *///?} elif >=26.1 {
    /*private static int mainFbo(RenderTarget main) {
        return mainFbo(glId(main.getColorTexture()), glId(main.getDepthTexture()), hasStencil(main));
    }
    *///?}

    //? if >=26.1 {
    /*private static int mainFbo = -1;
    private static int mainColor;
    private static int mainDepth;

    // OUR framebuffer over the main target's two textures: from 26.1 Minecraft's own is kept on a
    // package-private device (GlDevice). Attached as Minecraft's FrameBufferCache attaches them -- depth
    // alone, or depth and stencil when the texture has both (NeoForge's stencilled target), which is what
    // the depth snapshot reads its format and blit mask from -- keyed on the two texture ids, and rebuilt
    // when a resize replaces either. Through CgGL, so the shadow sees it.
    private static int mainFbo(int color, int depth, boolean stencil) {
        if (mainFbo != -1 && mainColor == color && mainDepth == depth) return mainFbo;
        deleteMainFbo();
        mainFbo = CgGL.glGenFramebuffers();
        CgGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        CgGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        CgGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        mainColor = color;
        mainDepth = depth;
        CgGraphicsLifecycle.addListener(MAIN_FBO_OWNER);
        return mainFbo;
    }

    private static int glId(GpuTexture texture) {
        return texture == null ? 0 : ((GlTexture) texture).glId();
    }

    private static void deleteMainFbo() {
        if (mainFbo != -1) CgGL.glDeleteFramebuffers(mainFbo);
        mainFbo = -1;
    }

    // Idempotent to register; releases the framebuffer while the context is still whole.
    private static final CgLifecycleListener MAIN_FBO_OWNER = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            deleteMainFbo();
        }
    };
    *///?}

    private static Boolean canRender;

    /**
     * Whether this session can render through {@code CgGL}: Minecraft's GL context, or from 26.2 its Vulkan
     * device, which then hosts ours. Decided once, on the render thread. A {@code false} stands the engine down
     * for the session ({@link CgGraphicsLifecycle#standDown}) and says why.
     *
     * <pre>{@code
     * if (!LifecycleModern.canRender()) return;   // before painting outside a world pass
     * }</pre>
     */
    public static boolean canRender() {
        // After the game's shutdown signal nothing of ours may record: under Vulkan the device is closing.
        if (CgGraphicsLifecycle.isContextDestroyed()) return false;
        if (canRender == null) {
            String refused = refusal();
            canRender = refused == null;
            if (!canRender) CgGraphicsLifecycle.standDown(refused);
        }
        return canRender;
    }

    // Why this session cannot render, or null. Under Vulkan the hosted device is built here, so a failure to
    // host stands the engine down with its cause rather than failing in the middle of a frame.
    private static String refusal() {
        if (GraphicsApi.vulkan()) {
            try {
                CgPlatform.gl();
                return null;
            } catch (RuntimeException | LinkageError failed) {
                LogManager.getLogger("CrystalGraphics").error("[cg] cannot host on Minecraft's Vulkan device", failed);
                return "Minecraft's Vulkan device could not host CrystalGraphics (" + failed + ")";
            }
        }
        return GLFW.glfwGetCurrentContext() != 0L ? null : "no GL context on the render thread";
    }

    /** A resource reload landed — drop every cache built from assets. */
    public static void reload() {
        CgPlatform.reload().onReload();
    }

    /**
     * The game is closing.
     *
     * <p>Stops the engine and frees nothing: Minecraft keeps dispatching render stages after its
     * shutdown signal. Under Vulkan our device then closes from Blaze3D's destroy queue, before Minecraft's
     * own device goes. @see CgGraphicsLifecycle#shutdown</p>
     */
    public static void shutdown() {
        CgGraphicsLifecycle.shutdown();
        //? if >=26.2 {
        /*if (GraphicsApi.vulkan()) Blaze3dVulkanHost.shutdown();
        *///?}
    }
}
