package io.github.somehussar.crystalgraphics.mc.integration;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebuffer;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgRuntimeAttachments;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgTextureFormatSpec;
import io.github.somehussar.crystalgraphics.gl.framebuffer.CgFormatProbe;
import io.github.somehussar.crystalgraphics.gl.framebuffer.CgFramebufferFactory;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

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
 *   <li>Format probe — verifies {@link CgFormatProbe} works and caches results</li>
 *   <li>Spec-based multi-attachment FBO creation — verifies MRT framebuffers</li>
 *   <li>Packed depth-stencil path — verifies depth/stencil attachment</li>
 *   <li>Runtime attachments — verifies dynamic attachment/detachment</li>
 * </ol>
 *
 * <h3>Dev Environment Only</h3>
 * <p>This mod is intended for development and CI testing only.  It is not
 * included in production builds and should never be shipped to end users.</p>
 *
 * @see GLStateMirror
 * @see CgFormatProbe
 * @see CgFramebufferFactory
 */
@Mod(
    modid = "crystalgraphics_test",
    name = "CrystalGraphics Integration Test",
    version = "1.0",
    dependencies = "required-after:crystalgraphics"
)
public class CrystalGraphicsIntegrationTest {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsIntegrationTest");

    /** RGBA8 format: GL_RGBA8 / GL_RGBA / GL_UNSIGNED_BYTE. */
    private static final CgTextureFormatSpec RGBA8 =
            new CgTextureFormatSpec(0x8058, 0x1908, 0x1401);

    /** {@code GL_DEPTH24_STENCIL8} packed depth-stencil format. */
    private static final int GL_DEPTH24_STENCIL8 = 0x88F0;

    /** {@code GL_TEXTURE_2D} target constant. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** Guard: only run once per session. */
    private boolean testRan = false;

    /** Running counters for the final summary. */
    private int passCount = 0;
    private int failCount = 0;

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("CrystalGraphicsIntegrationTest: Registered. "
                + "Will run self-checks on first render tick.");
        // Disable tests until manually enabled, to avoid accidentally running in production
        // FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (testRan || event.phase != TickEvent.Phase.END) {
            return;
        }
        testRan = true;

        System.out.println("[CrystalGraphics] Starting integration self-checks...");

        final CgCapabilities caps = CgCapabilities.detect();
        final CgCapabilities.Backend backend = caps.preferredFboBackend();
        final boolean isExtOnly = (backend == CgCapabilities.Backend.EXT_FBO);

        System.out.println("[CrystalGraphics] Detected backend: " + backend
                + " (coreFbo=" + caps.isCoreFbo()
                + ", arbFbo=" + caps.isArbFbo()
                + ", extFbo=" + caps.isExtFbo()
                + ", maxColorAttachments=" + caps.getMaxColorAttachments()
                + ", maxDrawBuffers=" + caps.getMaxDrawBuffers()
                + ", packedDepthStencil=" + caps.hasPackedDepthStencil() + ")");

        // ── Test 1: GL Redirect Layer ─────────────────────────────────
        runTest("GL redirect layer", new Runnable() {
            @Override
            public void run() {
                testGlRedirectLayer();
            }
        });

        // ── Test 2: Format Probe ──────────────────────────────────────
        runTest("Format probe", new Runnable() {
            @Override
            public void run() {
                testFormatProbe(caps);
            }
        });

        // ── Test 3: Spec-based multi-attachment FBO ───────────────────
        if (isExtOnly) {
            runTest("Multi-attachment FBO (EXT expects UnsupportedOperationException)",
                    new Runnable() {
                @Override
                public void run() {
                    testMultiAttachmentFboExt(caps);
                }
            });
        } else {
            runTest("Multi-attachment FBO", new Runnable() {
                @Override
                public void run() {
                    testMultiAttachmentFbo(caps);
                }
            });
        }

        // ── Test 4: Packed depth-stencil path ─────────────────────────
        runTest("Packed depth-stencil", new Runnable() {
            @Override
            public void run() {
                testPackedDepthStencil(caps);
            }
        });

        // ── Test 5: Runtime attachments ───────────────────────────────
        if (!isExtOnly) {
            runTest("Runtime attachments", new Runnable() {
                @Override
                public void run() {
                    testRuntimeAttachments(caps);
                }
            });
        } else {
            System.out.println(
                    "[CrystalGraphics]   SKIP: Runtime attachments (EXT-only backend)");
        }

        // ── Summary and exit ──────────────────────────────────────────
        boolean allPassed = (failCount == 0);
        String result = allPassed ? "PASS" : "FAIL";

        System.out.println("========================================");
        System.out.println("CRYSTALGRAPHICS INTEGRATION TEST: " + result);
        System.out.println("  " + passCount + " passed, " + failCount + " failed");
        System.out.println("========================================");

        LOGGER.info("Integration test complete: {} ({} passed, {} failed)",
                result, passCount, failCount);

        // Shut down the client so runClient doesn't hang
        Minecraft.getMinecraft().shutdown();
    }

    // ── Test implementations ──────────────────────────────────────────

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
        boolean mirrorUpdated = (mirrorFboAfter == 0);

        if (!mirrorUpdated) {
            throw new AssertionError(
                    "GLStateMirror did not track FBO bind (api=" + api
                    + ", before=" + mirrorFboBefore
                    + ", after=" + mirrorFboAfter + ")");
        }
        System.out.println("[CrystalGraphics]     Redirect layer verified via " + api
                + " (before=" + mirrorFboBefore + ", after=" + mirrorFboAfter + ")");
    }

    private void testFormatProbe(CgCapabilities caps) {
        // Clear cache to ensure a fresh probe
        CgFormatProbe.clearCache();

        boolean result1 = CgFormatProbe.isSupported(RGBA8, caps);
        boolean result2 = CgFormatProbe.isSupported(RGBA8, caps);

        if (result1 != result2) {
            throw new AssertionError(
                    "Format probe returned inconsistent results: first="
                    + result1 + ", second=" + result2);
        }

        // RGBA8 should be universally supported
        if (!result1) {
            throw new AssertionError(
                    "RGBA8 format reported as unsupported — "
                    + "this should work on all hardware");
        }

        System.out.println("[CrystalGraphics]     RGBA8 supported=" + result1
                + ", cache consistent=" + (result1 == result2));
    }

    private void testMultiAttachmentFbo(CgCapabilities caps) {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
                .baseDimensions(256, 256)
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .scale(1.0f)
                        .format(RGBA8)
                        .build())
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .scale(0.5f)
                        .format(RGBA8)
                        .build())
                .depthStencil(CgDepthStencilSpec.packedDepthStencil(
                        GL_DEPTH24_STENCIL8))
                .build();

        CgFramebuffer fbo = CgFramebufferFactory.create(caps, spec);
        try {
            int texId0 = fbo.getColorTextureId(0);
            int texId1 = fbo.getColorTextureId(1);

            if (texId0 <= 0) {
                throw new AssertionError(
                        "Color attachment 0 texture ID is not positive: " + texId0);
            }
            if (texId1 <= 0) {
                throw new AssertionError(
                        "Color attachment 1 texture ID is not positive: " + texId1);
            }

            System.out.println("[CrystalGraphics]     Attachment 0: texId=" + texId0
                    + ", expected size=256x256 (base*1.0)");
            System.out.println("[CrystalGraphics]     Attachment 1: texId=" + texId1
                    + ", expected size=128x128 (base*0.5)");
            System.out.println("[CrystalGraphics]     FBO id=" + fbo.getId()
                    + ", width=" + fbo.getWidth()
                    + ", height=" + fbo.getHeight());
        } finally {
            fbo.delete();
        }
    }

    private void testMultiAttachmentFboExt(CgCapabilities caps) {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
                .baseDimensions(256, 256)
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .format(RGBA8)
                        .build())
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .scale(0.5f)
                        .format(RGBA8)
                        .build())
                .depthStencil(CgDepthStencilSpec.packedDepthStencil(
                        GL_DEPTH24_STENCIL8))
                .build();

        try {
            CgFramebuffer fbo = CgFramebufferFactory.create(caps, spec);
            fbo.delete();
            throw new AssertionError(
                    "Expected UnsupportedOperationException for MRT on EXT backend");
        } catch (UnsupportedOperationException e) {
            System.out.println("[CrystalGraphics]     EXT correctly rejected MRT: "
                    + e.getMessage());
        }
    }

    private void testPackedDepthStencil(CgCapabilities caps) {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
                .baseDimensions(64, 64)
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .format(RGBA8)
                        .build())
                .depthStencil(CgDepthStencilSpec.packedDepthStencil(
                        GL_DEPTH24_STENCIL8))
                .build();

        CgFramebuffer fbo = CgFramebufferFactory.create(caps, spec);
        try {
            CgDepthStencilSpec dsSpec = spec.getDepthStencil();
            System.out.println("[CrystalGraphics]     Packed depth-stencil OK"
                    + " (isPacked=" + dsSpec.isPacked()
                    + ", hasDepth=" + dsSpec.hasDepth()
                    + ", hasStencil=" + dsSpec.hasStencil()
                    + ", format=0x"
                    + Integer.toHexString(dsSpec.getPackedFormat())
                    + ", fboId=" + fbo.getId() + ")");
        } finally {
            fbo.delete();
        }
    }

    private void testRuntimeAttachments(CgCapabilities caps) {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
                .baseDimensions(64, 64)
                .addColorAttachment(CgColorAttachmentSpec.builder()
                        .format(RGBA8)
                        .build())
                .depthStencil(CgDepthStencilSpec.none())
                .build();

        CgFramebuffer fbo = CgFramebufferFactory.create(caps, spec);
        int dummyTex = 0;
        try {
            CgRuntimeAttachments rt = fbo.getRuntimeAttachments();

            // Generate and allocate a dummy texture
            dummyTex = GL11.glGenTextures();
            GL11.glBindTexture(GL_TEXTURE_2D, dummyTex);
            GL11.glTexParameteri(GL_TEXTURE_2D, 0x2801, 0x2600); // MIN=NEAREST
            GL11.glTexParameteri(GL_TEXTURE_2D, 0x2800, 0x2600); // MAG=NEAREST
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, 0x8058, 64, 64, 0,
                    0x1908, 0x1401, (ByteBuffer) null);
            GL11.glBindTexture(GL_TEXTURE_2D, 0);

            // Slot 1 is first runtime slot (spec has 1 at slot 0)
            int runtimeSlot = 1;
            rt.attachExternal(runtimeSlot, dummyTex);

            if (!rt.hasAttachment(runtimeSlot)) {
                throw new AssertionError("hasAttachment(" + runtimeSlot
                        + ") returned false after attach");
            }
            int retrievedId = rt.getTextureId(runtimeSlot);
            if (retrievedId != dummyTex) {
                throw new AssertionError("getTextureId(" + runtimeSlot
                        + ") returned " + retrievedId
                        + ", expected " + dummyTex);
            }

            rt.detach(runtimeSlot);

            if (rt.hasAttachment(runtimeSlot)) {
                throw new AssertionError("hasAttachment(" + runtimeSlot
                        + ") returned true after detach");
            }

            System.out.println("[CrystalGraphics]     Runtime attach/detach at slot "
                    + runtimeSlot + " OK (dummyTexId=" + dummyTex + ")");
        } finally {
            if (dummyTex != 0) {
                GL11.glDeleteTextures(dummyTex);
            }
            fbo.delete();
        }
    }

    // ── Test harness ──────────────────────────────────────────────────

    private void runTest(String name, Runnable test) {
        try {
            test.run();
            passCount++;
            System.out.println("[CrystalGraphics]   PASS: " + name);
        } catch (Throwable t) {
            failCount++;
            System.out.println("[CrystalGraphics]   FAIL: " + name
                    + " -- " + t.getMessage());
            LOGGER.error("Test '{}' failed", name, t);
        }
    }
}
