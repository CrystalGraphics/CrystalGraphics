package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * Managed single-shot world text scene for the {@code world-text-scene} mode.
 *
 * <p>Renders one frame of 3D world-space text to an offscreen FBO and captures
 * the result to a single PNG named from the configured output prefix. This is the non-interactive
 * counterpart to {@link InteractiveWorldTextScene}.</p>
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>{@code harness-output/world-text-scene/{outputName}.png}</li>
 * </ul>
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Create offscreen FBO with color texture + depth renderbuffer</li>
 *   <li>Delegate text rendering to {@link WorldTextRenderHelper#renderManagedFrame}</li>
 *   <li>Capture the FBO color attachment to PNG</li>
 *   <li>Tear down FBO and release all resources</li>
 * </ol>
 *
 * <p>All shared rendering logic (font loading, layout construction, GL cap
 * validation, MSDF rendering) is handled by {@link WorldTextRenderHelper}.
 * This class only owns the FBO lifecycle and screenshot capture.</p>
 *
 * @see WorldTextRenderHelper
 * @see InteractiveWorldTextScene
 */
public class ManagedWorldTextScene implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(ManagedWorldTextScene.class.getName());

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Typed config is resolved before execution in FontDebugHarnessMain
        // and set on the context — no scene-side CLI parsing needed.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();

        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int fboWidth = config.getWidth();
        int fboHeight = config.getHeight();
        String outputDir = ctx.getOutputDir();
        String outputFile = ctx.getOutputSettings().buildBaseFilename("png");

        LOGGER.info("[Harness] World text scene (managed): font=" + fontPath);
        LOGGER.info("[Harness] World text scene (managed): size=" + fontSizePx
                + "px, text=\"" + text + "\"");

        // Initialize the shared render helper (validates GL caps, loads font, builds layouts)
        WorldTextRenderHelper helper = new WorldTextRenderHelper(
                fontPath, fontSizePx, text, fboWidth, fboHeight);
        helper.init();

        // ── FBO setup: offscreen color texture + depth renderbuffer ──
        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();
        int depthRbo = GL30.glGenRenderbuffers();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                fboWidth, fboHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT,
                fboWidth, fboHeight);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRbo);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException(
                    "World text scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        // ── Render to FBO ──
        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        helper.renderManagedFrame(fboWidth, fboHeight);

        GL11.glFinish();

        // ── Screenshot capture ──
        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                fboWidth, fboHeight, outputDir, outputFile);

        // ── Teardown ──
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        helper.dispose();
        GL30.glDeleteFramebuffers(fbo);
        GL30.glDeleteRenderbuffers(depthRbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] World text scene (managed) complete.");
    }

    @Override
    public void dispose() {
    }
}
