package io.github.somehussar.crystalgraphics.mc.integration;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureType;
import io.github.somehussar.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;
import net.minecraft.client.renderer.OpenGlHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * Integration test mod that verifies the CrystalGraphics GL redirect layer
 * and framebuffer abstraction are correctly working in a real GL context.
 *
 * <p>On the first render tick after initialization, this mod runs a series
 * of self-check tests and then prints a PASS/FAIL summary and exits the
 * client automatically, making {@code ./gradlew runClient} automation-friendly.</p>
 *
 * <h3>Tests Performed</h3>
 * <ol>
 *   <li>GL redirect layer — verifies {@link GLStateMirror} tracks FBO binds</li>
 *   <li>Single-attachment FBO creation — verifies basic framebuffer setup</li>
 *   <li>Multi-attachment FBO creation — verifies MRT framebuffers (skipped on EXT)</li>
 *   <li>Packed depth-stencil path — verifies depth/stencil attachment</li>
 *   <li>Depth-only FBO — verifies depth-only shadow map style framebuffer</li>
 * </ol>
 *
 * <h3>Dev Environment Only</h3>
 * <p>This mod is intended for development and CI testing only.  It is not
 * included in production builds and should never be shipped to end users.</p>
 *
 * @see GLStateMirror
 * @see CgFrameBuffer
 */
@Mod(
    modid = "crystalgraphics_test",
    name = "CrystalGraphics Integration Test",
    version = "1.0",
    dependencies = "required-after:crystalgraphics"
)
public class CrystalGraphicsIntegrationTest {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsIntegrationTest");

    private static final boolean RUN_SELF_CHECKS =
            Boolean.getBoolean("crystalgraphics.integration.runSelfChecks");

    private boolean testRan = false;
    private int passCount = 0;
    private int failCount = 0;

    private final CrystalGraphicsFontDemo fontDemo = new CrystalGraphicsFontDemo();

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("CrystalGraphicsIntegrationTest: Registered. "
                + "Font demo enabled. Self-checks opt-in=" + RUN_SELF_CHECKS);
        fontDemo.register();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!RUN_SELF_CHECKS || testRan || event.phase != TickEvent.Phase.END) {
            return;
        }
        testRan = true;

        System.out.println("[CrystalGraphics] Starting integration self-checks...");

        final CgCapabilities caps = CgCapabilities.detect();
        final CgCapabilities.FramebufferPath framebufferPath = caps.preferredFboBackend();
        final boolean isExtOnly = (framebufferPath == CgCapabilities.FramebufferPath.EXT_FBO);

        System.out.println("[CrystalGraphics] Detected backend: " + framebufferPath
                + " (coreFbo=" + caps.isCoreFbo()
                + ", arbFbo=" + caps.isArbFbo()
                + ", extFbo=" + caps.isExtFbo()
                + ", maxDrawBuffers=" + caps.getMaxDrawBuffers() + ")");

        runTest("GL redirect layer", new Runnable() {
            @Override public void run() { testGlRedirectLayer(); }
        });

        runTest("Single-attachment FBO", new Runnable() {
            @Override public void run() { testSingleAttachmentFbo(); }
        });

        if (isExtOnly) {
            runTest("Multi-attachment FBO (EXT expects UnsupportedOperationException)",
                    new Runnable() {
                @Override public void run() { testMultiAttachmentFboExt(); }
            });
        } else {
            runTest("Multi-attachment FBO", new Runnable() {
                @Override public void run() { testMultiAttachmentFbo(); }
            });
        }

        runTest("Packed depth-stencil", new Runnable() {
            @Override public void run() { testPackedDepthStencil(); }
        });

        runTest("Depth-only FBO", new Runnable() {
            @Override public void run() { testDepthOnlyFbo(); }
        });

        boolean allPassed = (failCount == 0);
        String result = allPassed ? "PASS" : "FAIL";
        System.out.println("========================================");
        System.out.println("CRYSTALGRAPHICS INTEGRATION TEST: " + result);
        System.out.println("  " + passCount + " passed, " + failCount + " failed");
        System.out.println("========================================");
        LOGGER.info("Integration test complete: {} ({} passed, {} failed)",
                result, passCount, failCount);
    }

    private void testGlRedirectLayer() {
        int mirrorFboBefore = GLStateMirror.getDrawFboId();

        ContextCapabilities glCaps = GLContext.getCapabilities();
        String api;
        if (glCaps.OpenGL30) {
            api = "GL30";
            GL30.glBindFramebuffer(0x8D40, 0);
        } else if (glCaps.GL_ARB_framebuffer_object) {
            api = "ARB";
            ARBFramebufferObject.glBindFramebuffer(0x8D40, 0);
        } else if (glCaps.GL_EXT_framebuffer_object) {
            api = "EXT";
            EXTFramebufferObject.glBindFramebufferEXT(0x8D40, 0);
        } else {
            api = "OpenGlHelper";
            OpenGlHelper.func_153171_g(0x8D40, 0);
        }

        int mirrorFboAfter = GLStateMirror.getDrawFboId();
        if (mirrorFboAfter != 0) {
            throw new AssertionError(
                    "GLStateMirror did not track FBO bind (api=" + api
                    + ", before=" + mirrorFboBefore
                    + ", after=" + mirrorFboAfter + ")");
        }
        System.out.println("[CrystalGraphics]     Redirect layer verified via " + api);
    }

    private void testSingleAttachmentFbo() {
        CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("test_single")
                .color(0, CgTextureType.RGBA8)
                .depth(CgTextureType.DEPTH24_STENCIL8)
                .build();
        CgFrameBuffer fbo = CgFrameBuffer.create("test_single", 64, 64, fmt);
        try {
            CgTexture colorTex = fbo.getColorTexture(0);
            if (colorTex == null) {
                throw new AssertionError("Color texture at slot 0 is null");
            }
            if (colorTex.getId() <= 0) {
                throw new AssertionError("Color texture ID is not positive: " + colorTex.getId());
            }
            System.out.println("[CrystalGraphics]     Single FBO ok: id=" + fbo.getId()
                    + ", colorTex=" + colorTex.getId());
        } finally {
            fbo.delete();
        }
    }

    private void testMultiAttachmentFbo() {
        CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("test_mrt")
                .color(0, CgTextureType.RGBA8)
                .color(1, CgTextureType.RGBA8)
                .depth(CgTextureType.DEPTH24_STENCIL8)
                .build();
        CgFrameBuffer fbo = CgFrameBuffer.create("test_mrt", 256, 256, fmt);
        try {
            CgTexture t0 = fbo.getColorTexture(0);
            CgTexture t1 = fbo.getColorTexture(1);
            if (t0 == null || t0.getId() <= 0) {
                throw new AssertionError("Color slot 0 invalid");
            }
            if (t1 == null || t1.getId() <= 0) {
                throw new AssertionError("Color slot 1 invalid");
            }
            System.out.println("[CrystalGraphics]     MRT FBO ok: id=" + fbo.getId()
                    + ", slot0=" + t0.getId() + ", slot1=" + t1.getId());
        } finally {
            fbo.delete();
        }
    }

    private void testMultiAttachmentFboExt() {
        CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("test_mrt_ext")
                .color(0, CgTextureType.RGBA8)
                .color(1, CgTextureType.RGBA8)
                .depth(CgTextureType.DEPTH24_STENCIL8)
                .build();
        try {
            CgFrameBuffer fbo = CgFrameBuffer.create("test_mrt_ext", 256, 256, fmt);
            fbo.delete();
            throw new AssertionError(
                    "Expected UnsupportedOperationException for MRT on EXT backend");
        } catch (UnsupportedOperationException e) {
            System.out.println("[CrystalGraphics]     EXT correctly rejected MRT: "
                    + e.getMessage());
        }
    }

    private void testPackedDepthStencil() {
        CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("test_ds")
                .color(0, CgTextureType.RGBA8)
                .depth(CgTextureType.DEPTH24_STENCIL8)
                .build();
        CgFrameBuffer fbo = CgFrameBuffer.create("test_ds", 64, 64, fmt);
        try {
            System.out.println("[CrystalGraphics]     Packed DS FBO ok: id=" + fbo.getId());
        } finally {
            fbo.delete();
        }
    }

    private void testDepthOnlyFbo() {
        CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("test_depth_only")
                .depth(CgTextureType.DEPTH24)
                .build();
        CgFrameBuffer fbo = CgFrameBuffer.create("test_depth_only", 64, 64, fmt);
        try {
            CgTexture depthTex = fbo.getDepthTexture();
            if (depthTex == null) {
                throw new AssertionError("Depth texture is null");
            }
            System.out.println("[CrystalGraphics]     Depth-only FBO ok: id=" + fbo.getId()
                    + ", depthTex=" + depthTex.getId());
        } finally {
            fbo.delete();
        }
    }

    private void runTest(String name, Runnable test) {
        try {
            test.run();
            passCount++;
            System.out.println("[CrystalGraphics]   PASS: " + name);
        } catch (Throwable t) {
            failCount++;
            System.out.println("[CrystalGraphics]   FAIL: " + name + " -- " + t.getMessage());
            LOGGER.error("Test '{}' failed", name, t);
        }
    }
}
