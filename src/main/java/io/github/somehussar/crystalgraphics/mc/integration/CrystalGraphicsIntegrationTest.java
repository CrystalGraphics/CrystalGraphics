package io.github.somehussar.crystalgraphics.mc.integration;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * Integration test mod that verifies the CrystalGraphics GL redirect layer
 * is correctly observing and tracking OpenGL state changes.
 *
 * <p>This mod runs in the {@code runClient} development environment alongside
 * the main CrystalGraphics library.  On the first render tick after
 * initialization, it performs a one-shot test that:</p>
 * <ol>
 *   <li>Records the current tracked framebuffer ID from {@link GLStateMirror}</li>
 *   <li>Simulates an "external mod" by directly binding FBO ID 0 using the
 *       best available framebuffer API for the current context (Core GL30,
 *       ARB, or EXT)</li>
 *   <li>Reads the updated framebuffer ID from {@link GLStateMirror}</li>
 *   <li>Logs whether the state mirror correctly observed the bind</li>
 * </ol>
 *
 * <p>The test is guarded to run only once (not every tick) and is wrapped
 * in a catch-all exception handler to prevent crashes.  Success is
 * indicated by log output containing
 * {@code "CrystalGraphicsIntegrationTest: observed GL redirect test complete. FBO mirror updated correctly=true"}.</p>
 *
 * <h3>Dev Environment Only</h3>
 * <p>This mod is intended for development and CI testing only.  It is not
 * included in production builds and should never be shipped to end users.</p>
 *
 * @see GLStateMirror
 */
@Mod(
    modid = "crystalgraphics_test",
    name = "CrystalGraphics Integration Test",
    version = "1.0",
    dependencies = "required-after:crystalgraphics"
)
public class CrystalGraphicsIntegrationTest {

    /**
     * Logger for integration test output.  All messages are prefixed with
     * {@code "CrystalGraphicsIntegrationTest:"} to aid log filtering.
     */
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsIntegrationTest");

    /**
     * Flag to ensure the integration test runs only once per game session.
     * Set to {@code true} after the first test execution.
     */
    private boolean testRan = false;

    /**
     * FML post-initialization event handler.  Registers this mod on the
     * FML event bus to receive render tick events.
     *
     * @param event the post-initialization event (unused)
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("CrystalGraphicsIntegrationTest: Registered. Will verify GL redirect on first render tick.");
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * Render tick event handler.  Executes the GL redirect layer integration
     * test on the first {@link TickEvent.Phase#END} render tick.
     *
     * <p>The test sequence:</p>
     * <ol>
     *   <li>Records the current draw FBO ID from {@link GLStateMirror#getDrawFboId()}</li>
     *   <li>Directly binds FBO ID 0 using the best available API for the
     *       current context, simulating an external mod making a GL call</li>
     *   <li>Reads the updated draw FBO ID from {@link GLStateMirror}</li>
     *   <li>Checks if the mirror was updated correctly (expected: ID = 0)</li>
     *   <li>Logs the test result with before/after values</li>
     * </ol>
     *
     * <p>All GL operations are wrapped in a catch-all exception handler to
     * prevent crashes.  Exceptions are logged but do not halt execution.</p>
     *
     * @param event the render tick event
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        // Guard: only run once and only at END phase
        if (testRan || event.phase != TickEvent.Phase.END) {
            return;
        }

        testRan = true;

        try {
            // Record baseline
            int mirrorFboBefore = GLStateMirror.getDrawFboId();

            // 0x8D40 = GL_FRAMEBUFFER
            ContextCapabilities caps = GLContext.getCapabilities();
            String api;
            if (caps.OpenGL30) {
                api = "GL30";
                GL30.glBindFramebuffer(0x8D40, 0);
            } else if (caps.GL_ARB_framebuffer_object) {
                api = "ARB";
                ARBFramebufferObject.glBindFramebuffer(0x8D40, 0);
            } else if (caps.GL_EXT_framebuffer_object) {
                api = "EXT";
                EXTFramebufferObject.glBindFramebufferEXT(0x8D40, 0);
            } else {
                api = "OpenGlHelper";
                OpenGlHelper.func_153171_g(0x8D40, 0);
            }

            // Check if mirror was updated
            int mirrorFboAfter = GLStateMirror.getDrawFboId();
            boolean mirrorUpdated = (mirrorFboAfter == 0);

            LOGGER.info(
                "CrystalGraphicsIntegrationTest: observed GL redirect test complete. " +
                "FBO mirror updated correctly={} (api={}, before={}, after={})",
                mirrorUpdated,
                api,
                mirrorFboBefore,
                mirrorFboAfter
            );
        } catch (Throwable t) {
            LOGGER.error("CrystalGraphicsIntegrationTest: test threw exception: " + t, t);
        }
    }
}
