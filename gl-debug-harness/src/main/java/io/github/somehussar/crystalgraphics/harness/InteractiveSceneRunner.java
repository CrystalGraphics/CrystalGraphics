package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.camera.FloorRenderer;
import io.github.somehussar.crystalgraphics.harness.camera.HUDRenderer;
import io.github.somehussar.crystalgraphics.harness.camera.PauseScreenRenderer;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.WorldConfig;
import io.github.somehussar.crystalgraphics.harness.debug.HarnessDebugTools;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;

import org.joml.Matrix4f;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.util.logging.Logger;

/**
 * Drives the render loop for {@link InteractiveHarnessScene} implementations.
 *
 * <p>This runner manages the Camera3D, FloorRenderer, TaskScheduler, and
 * frame timing. It wraps the scene's lifecycle methods (init → renderFrame → cleanup)
 * and provides the debug tools for LLM-driven validation.</p>
 *
 * <p>Supports a pause mode toggled by ESCAPE or T keys. When paused:
 * the mouse cursor is unlocked, camera updates are disabled, and a
 * semi-transparent overlay is rendered at the bottom of the screen.
 * Pressing ESCAPE or T again resumes normal operation.</p>
 *
 * <p>For non-interactive scenes ({@link HarnessScene}), this class is not used;
 * the existing single-shot execution path in FontDebugHarnessMain handles them.</p>
 */
public final class InteractiveSceneRunner {

    private static final Logger LOGGER = Logger.getLogger(InteractiveSceneRunner.class.getName());

    private static final int TARGET_FPS = 60;
    private static final float FOV_DEGREES = 60.0f;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;

    private final InteractiveHarnessScene scene;
    private final HarnessContext ctx;

    private Camera3D camera;
    private FloorRenderer floorRenderer;
    private HUDRenderer hudRenderer;
    private PauseScreenRenderer pauseRenderer;
    private TaskScheduler scheduler;
    private HarnessDebugTools debugTools;

    private boolean paused = false;

    /**
     * Post-render callback that fires after each frame is fully rendered
     * (including floor, HUD, pause overlay) but BEFORE Display.update().
     * Used by test scenes that need to capture screenshots of the current
     * frame's rendered content rather than the previous frame.
     */
    private Runnable postRenderCallback = null;

    public InteractiveSceneRunner(InteractiveHarnessScene scene, HarnessContext ctx) {
        this.scene = scene;
        this.ctx = ctx;
    }

    /**
     * Runs the full interactive scene lifecycle: init → render loop → cleanup.
     *
     * <p>Creates Camera3D, TaskScheduler, and renderers, populates the context
     * with references, then enters the render loop. The loop runs until the
     * scene signals completion via {@link InteractiveHarnessScene#isRunning()}
     * returning false, or the Display close is requested.</p>
     */
    public void run() {
        camera = new Camera3D();
        floorRenderer = new FloorRenderer();
        hudRenderer = new HUDRenderer();
        pauseRenderer = new PauseScreenRenderer();
        scheduler = new TaskScheduler();

        // Populate context with shared subsystem references so scenes
        // can access them via ctx.getCamera3D(), ctx.getRunner(), etc.
        ctx.setCamera3D(camera);
        ctx.setTaskScheduler(scheduler);
        ctx.setRunner(this);

        int currentWidth = ctx.getScreenWidth();
        int currentHeight = ctx.getScreenHeight();

        debugTools = new HarnessDebugTools(camera, ctx.getOutputDir(), ctx.getOutputName(),
                currentWidth, currentHeight);

        GL11.glViewport(0, 0, currentWidth, currentHeight);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        floorRenderer.init();
        hudRenderer.init();
        pauseRenderer.init();
        scene.init(ctx);

        LOGGER.info("[InteractiveSceneRunner] Entering render loop for: "
                + scene.getClass().getSimpleName());

        long lastTimeNanos = System.nanoTime();
        double elapsedTime = 0.0;
        long frameNumber = 0;

        while (!Display.isCloseRequested() && scene.isRunning()) {
            long nowNanos = System.nanoTime();
            float deltaTime = (nowNanos - lastTimeNanos) / 1_000_000_000.0f;
            lastTimeNanos = nowNanos;
            elapsedTime += deltaTime;
            frameNumber++;

            // Handle window resize events — update context and notify renderers
            if (Display.wasResized()) {
                currentWidth = Display.getWidth();
                currentHeight = Display.getHeight();
                ctx.setScreenDimensions(currentWidth, currentHeight);
                GL11.glViewport(0, 0, currentWidth, currentHeight);
                hudRenderer.onDisplayResize(currentWidth, currentHeight);
                pauseRenderer.onDisplayResize(currentWidth, currentHeight);
                floorRenderer.onDisplayResize(currentWidth, currentHeight);
            }

            // Check for pause toggle BEFORE camera input processing.
            // Uses Keyboard event queue to detect key-down events (not held state),
            // preventing rapid toggling from a single key press.
            pollPauseToggle();

            if (scene.uses3DCamera() && !paused) {
                camera.update(deltaTime);
            }

            // Fire any scheduled tasks that are due (even when paused)
            scheduler.tick(elapsedTime);

            // Ensure GL state is clean before rendering.
            // The text renderer (CgTextRenderer) may leave GL state dirty
            // between frames — explicitly reset critical state here.
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);

            // Clear framebuffer with sky color from WorldConfig
            WorldConfig worldCfg = WorldConfig.get();
            GL11.glClearColor(worldCfg.getSkyR(), worldCfg.getSkyG(), worldCfg.getSkyB(), 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Render floor plane BEFORE scene content so text composites over the
            // floor rather than over the sky clear color. This prevents the sky-blue
            // background from showing through transparent areas of text quads.
            if (scene.uses3DCamera()) {
                renderFloor();
            }

            // Render scene content
            scene.renderFrame(deltaTime, elapsedTime, frameNumber);

            // Reset GL state after scene rendering to ensure overlays render correctly.
            // Scene's text renderer may leave a shader program bound, depth test
            // disabled, or blend mode changed — all of which corrupt HUD/pause overlay.
            GL20ResetHelper.resetAfterScene();

            // Render pause overlay if paused (after scene, before HUD)
            if (paused) {
                pauseRenderer.render(ctx);
            }

            // Render HUD overlay (after scene and pause, before swap)
            if (scene.uses3DCamera()) {
                hudRenderer.render(ctx);
            }

            // Fire post-render callback (screenshot capture point)
            if (postRenderCallback != null) {
                Runnable cb = postRenderCallback;
                postRenderCallback = null;
                cb.run();
            }

            Display.update();
            Display.sync(TARGET_FPS);
        }

        LOGGER.info("[InteractiveSceneRunner] Render loop exited after " + frameNumber + " frames");

        // Ensure cursor is released on exit
        Mouse.setGrabbed(false);

        scene.cleanup();
        floorRenderer.delete();
        hudRenderer.delete();
        pauseRenderer.delete();

        LOGGER.info("[InteractiveSceneRunner] Cleanup complete. shouldShutdown="
                + scene.shouldShutdownOnComplete());
    }

    /**
     * Polls the LWJGL keyboard event queue for ESCAPE and T key-down events
     * to toggle pause state. Uses the event queue rather than
     * {@code Keyboard.isKeyDown()} to ensure a single press produces exactly
     * one toggle, regardless of how many frames the key is held.
     *
     * <p>When toggling to paused: releases the mouse cursor.
     * When toggling to unpaused: grabs the mouse cursor and drains any
     * accumulated mouse delta to prevent a camera jump.</p>
     */
    private void pollPauseToggle() {
        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState()) {
                // Only act on key-down events, ignore key-up
                continue;
            }
            int key = Keyboard.getEventKey();
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_T) {
                paused = !paused;
                if (paused) {
                    Mouse.setGrabbed(false);
                    LOGGER.info("[InteractiveSceneRunner] PAUSED — cursor released");
                } else {
                    Mouse.setGrabbed(true);
                    // Drain accumulated mouse delta to prevent a camera jump on resume
                    Mouse.getDX();
                    Mouse.getDY();
                    LOGGER.info("[InteractiveSceneRunner] RESUMED — cursor locked");
                }
            }
        }
    }

    /**
     * Renders the floor plane using the current camera view and perspective projection.
     *
     * <p>Computes the MVP matrix from the perspective projection and camera view matrix,
     * then passes the column-major float[16] to the FloorRenderer.</p>
     */
    private void renderFloor() {
        float aspect = (float) ctx.getScreenWidth() / (float) ctx.getScreenHeight();
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(FOV_DEGREES), aspect, NEAR_PLANE, FAR_PLANE);

        Matrix4f viewMatrix = camera.getViewMatrix();

        // MVP = projection * view (no model transform for floor)
        Matrix4f mvp = new Matrix4f();
        projection.mul(viewMatrix, mvp);

        float[] mvpArray = new float[16];
        mvp.get(mvpArray);

        floorRenderer.render(mvpArray);
    }

    /**
     * Returns the debug tools for LLM-driven camera control and screenshot capture.
     */
    public HarnessDebugTools getDebugTools() {
        return debugTools;
    }

    /**
     * Returns the active camera.
     */
    public Camera3D getCamera() {
        return camera;
    }

    /**
     * Returns the current viewport width.
     */
    public int getCurrentWidth() {
        return ctx.getScreenWidth();
    }

    /**
     * Returns the current viewport height.
     */
    public int getCurrentHeight() {
        return ctx.getScreenHeight();
    }

    /**
     * Returns the task scheduler.
     */
    public TaskScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Returns the output name prefix for filenames.
     */
    public String getOutputName() {
        return ctx.getOutputName();
    }

    /**
     * Returns whether the scene wants the program to shut down after completion.
     */
    public boolean shouldShutdown() {
        return scene.shouldShutdownOnComplete();
    }

    /**
     * Returns whether the runner is currently in paused state.
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Programmatically sets the paused state. Used by test scenes to
     * trigger pause without keyboard input. Handles mouse grab/ungrab
     * and drains accumulated mouse delta to prevent camera jumps.
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            Mouse.setGrabbed(false);
        } else {
            Mouse.setGrabbed(true);
            Mouse.getDX();
            Mouse.getDY();
        }
    }

    /**
     * Schedules a one-shot callback to fire after the CURRENT frame finishes
     * rendering (floor, HUD, pause overlay all drawn) but BEFORE the buffer
     * swap. This is the correct point to capture screenshots that show the
     * current frame's content.
     */
    public void setPostRenderCallback(Runnable callback) {
        this.postRenderCallback = callback;
    }

    /**
     * Helper to reset GL state after scene rendering.
     * Ensures overlays (pause screen, HUD) render correctly regardless
     * of what GL state the scene's text renderer left behind.
     */
    private static final class GL20ResetHelper {
        static void resetAfterScene() {
            org.lwjgl.opengl.GL20.glUseProgram(0);
            org.lwjgl.opengl.GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
