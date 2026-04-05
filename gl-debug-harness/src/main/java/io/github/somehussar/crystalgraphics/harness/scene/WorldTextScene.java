package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.text.CgFontRegistry;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.gl.text.CgTextRenderer;
import io.github.somehussar.crystalgraphics.gl.text.CgWorldTextRenderContext;
import io.github.somehussar.crystalgraphics.harness.HarnessScene;
import io.github.somehussar.crystalgraphics.harness.InteractiveHarnessScene;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneRunner;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.config.HarnessConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import io.github.somehussar.crystalgraphics.text.CgTextLayout;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * Harness scene demonstrating 3D world-space text rendering.
 *
 * <p>Renders text using the always-MSDF world-text path ({@link CgTextRenderer#drawWorld})
 * with a perspective projection and a model-view transform that positions text at
 * a distance from the camera. This verifies that:</p>
 * <ul>
 *   <li>World text uses MSDF-only rendering</li>
 *   <li>Depth testing is enabled</li>
 *   <li>Layout metrics remain in logical space regardless of camera distance</li>
 *   <li>Projected-size estimation drives quality/LOD tier selection</li>
 * </ul>
 *
 * <p>Implements both {@link HarnessScene} (single-shot capture mode) and
 * {@link InteractiveHarnessScene} (3D camera render loop mode). When run
 * interactively, text is rendered in world space and the camera can be
 * moved freely to inspect it from any angle.</p>
 */
public class WorldTextScene implements HarnessScene, InteractiveHarnessScene {

    private static final Logger LOGGER = Logger.getLogger(WorldTextScene.class.getName());

    private static final float FOV_DEGREES = 60.0f;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;

    // ── Interactive mode state ──
    private boolean running = true;
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;

    // GL + rendering resources (created in init, destroyed in cleanup)
    private CgCapabilities caps;
    private CgFont font;
    private CgFontRegistry registry;
    private CgTextRenderer renderer;
    private CgTextLayout worldLayout;
    private CgTextLayout refLayout;
    private CgWorldTextRenderContext worldContext;
    private int fboWidth;
    private int fboHeight;
    private int fontSizePx;
    private long frameCounter = 0;

    // ── HarnessScene (single-shot mode) ──

    @Override
    public void run(HarnessContext ctx) {
        TextSceneConfig config = TextSceneConfig.create(HarnessConfig.getGlobalCliArgs());
        runSingleShot(ctx, ctx.getOutputDir(), config);
    }

    /**
     * Single-shot rendering path: renders one frame to FBO and captures to PNG.
     * This preserves the original WorldTextScene behavior exactly.
     */
    void runSingleShot(HarnessContext ctx, String outputDir, TextSceneConfig config) {
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int fboWidth = config.getWidth();
        int fboHeight = config.getHeight();

        LOGGER.info("[Harness] World text scene: font=" + fontPath);
        LOGGER.info("[Harness] World text scene: size=" + fontSizePx + "px, text=\"" + text + "\"");

        CgCapabilities caps = CgCapabilities.detect();
        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "World text scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        CgFont font = CgFont.load(fontPath, CgFontStyle.REGULAR, fontSizePx);
        CgFontRegistry registry = new CgFontRegistry();
        CgTextRenderer renderer = CgTextRenderer.create(caps, registry);

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        CgTextLayout layout = layoutBuilder.layout(
                text + " [world-3D, " + fontSizePx + "px, always MSDF]",
                font, fboWidth, 0);

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
            throw new RuntimeException("World text scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Perspective projection: 60° FOV, aspect from FBO, near=0.1, far=1000
        float aspect = (float) fboWidth / (float) fboHeight;
        Matrix4f perspProjection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), aspect, 0.1f, 1000.0f);

        CgWorldTextRenderContext worldContext = CgWorldTextRenderContext.create(
                perspProjection, fboWidth, fboHeight);

        // Model-view: position text at z=-200 (moderate viewing distance)
        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.translate(0.0f, 0.0f, -200.0f);

        // Update projected-size hint for quality/LOD tier selection
        worldContext.updateProjectedSize(modelView, perspProjection, fontSizePx);

        long frame = 1;

        // Multi-frame to allow MSDF generation budget
        int framesNeeded = (text.length() / 4) + 5;
        for (long f = 1; f <= framesNeeded; f++) {
            registry.tickFrame(frame + f);
            renderer.drawWorld(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame + f,
                    worldContext, poseStack);
        }

        // Also render a 2D reference for comparison
        CgTextRenderContext orthoContext = CgTextRenderContext.orthographic(fboWidth, fboHeight);
        PoseStack orthoPose = new PoseStack();
        CgTextLayout refLayout = layoutBuilder.layout(
                "2D reference [" + fontSizePx + "px, ortho]", font, fboWidth, 0);
        renderer.draw(refLayout, font, 20.0f, (float)(fboHeight - 40), 0xAAFFAAFF,
                frame + framesNeeded + 1, orthoContext, orthoPose);

        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                fboWidth, fboHeight, outputDir, "world-text-scene.png");

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        renderer.delete();
        registry.releaseAll();
        font.dispose();
        GL30.glDeleteFramebuffers(fbo);
        GL30.glDeleteRenderbuffers(depthRbo);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] World text scene complete.");
    }

    // ── InteractiveHarnessScene (3D camera mode) ──

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        Camera3D camera = ctx.getCamera3D();
        TaskScheduler scheduler = ctx.getTaskScheduler();

        TextSceneConfig config = TextSceneConfig.create(HarnessConfig.getGlobalCliArgs());
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        fontSizePx = config.getFontSizePx();
        String text = config.getText();
        fboWidth = config.getWidth();
        fboHeight = config.getHeight();

        LOGGER.info("[Harness] World text scene (interactive): font=" + fontPath);
        LOGGER.info("[Harness] World text scene (interactive): size=" + fontSizePx + "px");

        caps = CgCapabilities.detect();
        if (!caps.isCoreFbo() || !caps.isCoreShaders()
                || !caps.isVaoSupported() || !caps.isMapBufferRangeSupported()) {
            throw new IllegalStateException(
                    "World text scene requires modern GL: core FBO, core shaders, VAO, glMapBufferRange");
        }

        font = CgFont.load(fontPath, CgFontStyle.REGULAR, fontSizePx);
        registry = new CgFontRegistry();
        renderer = CgTextRenderer.create(caps, registry);

        CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
        worldLayout = layoutBuilder.layout(
                text + " [world-3D, " + fontSizePx + "px, always MSDF]",
                font, (float) fboWidth, 0);
        refLayout = layoutBuilder.layout(
                "2D reference [" + fontSizePx + "px, ortho]",
                font, (float) fboWidth, 0);

        // Position camera at feet level looking slightly down at the floor
        camera.moveCamera(0.0f, 1.5f, 10.0f);
        camera.setPitch(-15.0f);

        float aspect = (float) fboWidth / (float) fboHeight;
        Matrix4f perspProjection = new Matrix4f().perspective(
                (float) Math.toRadians(FOV_DEGREES), aspect, NEAR_PLANE, FAR_PLANE);
        worldContext = CgWorldTextRenderContext.create(perspProjection, fboWidth, fboHeight);

        // Schedule automated screenshots for validation if runner is available
        if (ctx.getRunner() != null) {
            scheduleValidationScreenshots();
        }

        LOGGER.info("[Harness] World text scene (interactive) initialized.");
    }

    /**
     * Schedules three automated screenshots for visual validation:
     * <ol>
     *   <li><b>world-text-normal.png</b> — Front view: floor, text, HUD visible</li>
     *   <li><b>world-text-paused.png</b> — Same view with pause overlay at bottom</li>
     *   <li><b>world-text-topdown.png</b> — Camera looking straight down at floor + text</li>
     * </ol>
     *
     * <p>Uses post-render callbacks via the {@link InteractiveSceneRunner} to capture
     * screenshots AFTER the full frame (scene + floor + HUD + pause) is rendered.</p>
     */
    private void scheduleValidationScreenshots() {
        final Camera3D camera = ctx.getCamera3D();
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final InteractiveSceneRunner runner = (InteractiveSceneRunner) ctx.getRunner();
        final String outputDir = ctx.getOutputDir();
        final String outputName = ctx.getOutputName();

        // Screenshot 1: Normal front view at t=1.0s (allow MSDF generation time)
        scheduler.schedule(1.0, "world-text-normal-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 1.5f, 10.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-15.0f);
                LOGGER.info("[WorldTextScene] Camera positioned for normal screenshot");
            }
        });
        scheduler.schedule(1.2, "world-text-normal-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-normal.png";
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[WorldTextScene] Captured " + filename);
                    }
                });
            }
        });

        // Screenshot 2: Paused state at t=1.5s
        scheduler.schedule(1.5, "world-text-paused-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 1.5f, 10.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-15.0f);
                runner.setPaused(true);
                LOGGER.info("[WorldTextScene] Camera positioned + paused for paused screenshot");
            }
        });
        scheduler.schedule(1.7, "world-text-paused-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-paused.png";
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[WorldTextScene] Captured " + filename);
                        runner.setPaused(false);
                    }
                });
            }
        });

        // Screenshot 3: Top-down view at t=2.0s
        scheduler.schedule(2.0, "world-text-topdown-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 15.0f, 0.1f);
                camera.setYaw(0.0f);
                camera.setPitch(-89.0f);
                LOGGER.info("[WorldTextScene] Camera positioned for topdown screenshot");
            }
        });
        scheduler.schedule(2.2, "world-text-topdown-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-topdown.png";
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[WorldTextScene] Captured " + filename);
                    }
                });
            }
        });

        // Shutdown at t=2.5s
        scheduler.schedule(2.5, "world-text-shutdown", new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[WorldTextScene] All screenshots captured. Stopping.");
//                running = false;
            }
        });
    }

    @Override
    public void renderFrame(float deltaTime, double elapsedTime, long frameNumber) {
        frameCounter = frameNumber;

        // Build view matrix from camera
        Matrix4f viewMatrix = ctx.getCamera3D().getViewMatrix();

        int screenWidth = ctx.getScreenWidth();
        int screenHeight = ctx.getScreenHeight();

        float aspect = (float) screenWidth / (float) screenHeight;
        Matrix4f perspProjection = new Matrix4f().perspective(
                (float) Math.toRadians(FOV_DEGREES), aspect, NEAR_PLANE, FAR_PLANE);

        // Create model-view for world text positioned above the floor.
        // The text layout uses screen-space Y-down coordinates, but world space
        // uses Y-up. We apply three transforms:
        //   1. Camera view matrix (world → view space)
        //   2. Translate to text origin above the floor (Y=0.5 to stay above Y=0)
        //   3. Scale with Y-flip: positive scale on X/Z, negative on Y to flip
        //      text right-side-up, and scale down from pixel units to world units
        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        float worldScale = 0.01f;
        float textWorldWidth = worldLayout.getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.5f, -5f);
        modelView.scale(worldScale, -worldScale, worldScale);

        worldContext = CgWorldTextRenderContext.create(perspProjection, screenWidth, screenHeight);
        worldContext.updateProjectedSize(modelView, perspProjection, fontSizePx);

        registry.tickFrame(frameNumber);
        // Negative Y scale flips triangle winding order; switch front face to
        // clockwise so drawWorld()'s back-face culling doesn't discard visible faces.
        GL11.glFrontFace(GL11.GL_CW);
        renderer.drawWorld(worldLayout, font, 0.0f, 0.0f, 0xFFFFFFFF, frameNumber,
                worldContext, poseStack);
        GL11.glFrontFace(GL11.GL_CCW);

        // ── Aggressive GL state cleanup after world text rendering ──
        // drawWorld() internally saves/restores state via CgStateBoundary, but in the
        // standalone harness (no coremod), the GLStateMirror is in UNKNOWN state which
        // can cause incomplete restoration. Explicitly reset ALL GL state that the text
        // renderer's internal pipeline touches to guarantee floor, HUD, and pause overlay
        // render correctly in subsequent passes.

        // Unbind shader program — text renderer binds MSDF/bitmap shaders
        GL20.glUseProgram(0);

        // Unbind VAO — text renderer's CgGlyphVbo binds its own VAO
        GL30.glBindVertexArray(0);

        // Unbind VBO and IBO — CgGlyphVbo.uploadAndBind() binds both
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Unbind textures on unit 0 — drawBatches() binds atlas textures here
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Restore depth state — FloorRenderer requires depth test ON + depth writes ON
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // Disable blend — FloorRenderer expects blend OFF; HUD/pause manage their own
        GL11.glDisable(GL11.GL_BLEND);

        // Disable cull face — drawWorld() enables GL_CULL_FACE for single-sided text
        GL11.glDisable(GL11.GL_CULL_FACE);

        // Ensure we're rendering to the default framebuffer (backbuffer)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void cleanup() {
        if (renderer != null) {
            renderer.delete();
        }
        if (registry != null) {
            registry.releaseAll();
        }
        if (font != null) {
            font.dispose();
        }
        LOGGER.info("[Harness] World text scene (interactive) cleaned up.");
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return shutdownOnComplete;
    }

    /**
     * Configures whether the program should shut down when this scene completes.
     *
     * @param shutdown true to exit on completion (default), false to continue
     */
    public void setShutdownOnComplete(boolean shutdown) {
        this.shutdownOnComplete = shutdown;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Signals this scene to stop its render loop.
     */
    public void requestStop() {
        running = false;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }
}
