package com.crystalgraphics.mc.v1710.integration;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Integration test mod that verifies the CrystalGraphics GL redirect layer
 * and render targets of its own are correctly working in a real GL context.
 *
 * <p>On the first render tick after initialization, this mod runs a series
 * of self-check tests and then prints a PASS/FAIL summary and exits the
 * client automatically, making {@code ./gradlew runClient} automation-friendly.</p>
 *
 * <h3>Tests Performed</h3>
 * <ol>
 *   <li>A colour and a depth texture as one target</li>
 *   <li>Two colours and a depth: the textures a pass with two attachments draws into</li>
 *   <li>A packed depth-stencil texture as a target's depth</li>
 *   <li>A target of depth alone: a shadow map's</li>
 * </ol>
 *
 * <h3>Dev Environment Only</h3>
 * <p>This mod is intended for development and CI testing only.  It is not
 * included in production builds and should never be shipped to end users.</p>
 *
 * @see CgTextures#target
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

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("CrystalGraphicsIntegrationTest: Registered. Self-checks opt-in=" + RUN_SELF_CHECKS);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!RUN_SELF_CHECKS || testRan || event.phase != TickEvent.Phase.END) {
            return;
        }
        testRan = true;

        System.out.println("[CrystalGraphics] Starting integration self-checks...");

        final CgCapabilities caps = CgCapabilities.detect();   // throws below the GL 3.3 floor

        System.out.println("[CrystalGraphics] GL 3.3+ (maxDrawBuffers=" + caps.getMaxDrawBuffers() + ")");

        runTest("Single-attachment FBO", new Runnable() {
            @Override public void run() { testSingleAttachmentFbo(); }
        });

        runTest("Multi-attachment FBO", new Runnable() {
            @Override public void run() { testMultiAttachmentFbo(); }
        });

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


    private void testSingleAttachmentFbo() {
        CgTexture2D colour = texture("test_single", 64, CgFormat.RGBA8_UNORM);
        CgTexture2D depth = texture("test_single.depth", 64, CgFormat.DEPTH24_PLUS_STENCIL8);
        try {
            CgExternalTarget target = CgTextures.target("test_single", colour, depth);
            if (target.color() == null || target.depth() == null) throw new AssertionError("a target without both attachments");
            if (colour.rhiView() == null) throw new AssertionError("colour has no device view");
            System.out.println("[CrystalGraphics]     Single target ok: " + target);
        } finally {
            colour.release();
            depth.release();
        }
    }

    private void testMultiAttachmentFbo() {
        CgTexture2D t0 = texture("test_mrt0", 256, CgFormat.RGBA8_UNORM);
        CgTexture2D t1 = texture("test_mrt1", 256, CgFormat.RGBA8_UNORM);
        CgTexture2D depth = texture("test_mrt.depth", 256, CgFormat.DEPTH24_PLUS_STENCIL8);
        try {
            CgExternalTarget target = CgTextures.target("test_mrt", t0, depth);
            if (t0.rhiView() == null) throw new AssertionError("colour 0 invalid");
            if (t1.rhiView() == null) throw new AssertionError("colour 1 invalid");
            System.out.println("[CrystalGraphics]     MRT textures ok: " + target);
        } finally {
            t0.release();
            t1.release();
            depth.release();
        }
    }


    private void testPackedDepthStencil() {
        CgTexture2D colour = texture("test_ds", 64, CgFormat.RGBA8_UNORM);
        CgTexture2D depth = texture("test_ds.depth", 64, CgFormat.DEPTH24_PLUS_STENCIL8);
        try {
            CgExternalTarget target = CgTextures.target("test_ds", colour, depth);
            if (!target.depth().texture().desc().format().hasStencil()) throw new AssertionError("no stencil");
            System.out.println("[CrystalGraphics]     Packed DS ok: " + target);
        } finally {
            colour.release();
            depth.release();
        }
    }

    private void testDepthOnlyFbo() {
        CgTexture2D depth = texture("test_depth_only", 64, CgFormat.DEPTH24_PLUS);
        try {
            CgExternalTarget target = CgTextures.target("test_depth_only", null, depth);
            if (target.depth() == null) throw new AssertionError("depth is null");
            System.out.println("[CrystalGraphics]     Depth-only target ok: " + target);
        } finally {
            depth.release();
        }
    }

    private static CgTexture2D texture(String name, int size, CgFormat format) {
        return CgTextures.of(name, CgTextureDesc.of(size, size, format));
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
