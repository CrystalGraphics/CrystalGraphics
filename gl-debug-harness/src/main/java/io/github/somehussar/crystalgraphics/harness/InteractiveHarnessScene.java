package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Extended scene interface for interactive 3D scenes with a persistent render loop.
 *
 * <p>Unlike {@link HarnessScene} which runs a single-shot render pass,
 * interactive scenes run a continuous render loop with camera input,
 * task scheduling, and optional program shutdown control.</p>
 *
 * <p>Scenes opt into 3D camera support by implementing this interface.
 * The harness main loop detects this and drives the render loop accordingly.</p>
 *
 * <p><b>Lifecycle</b>: {@code init()} → repeated {@code renderFrame()} → {@code cleanup()}</p>
 */
public interface InteractiveHarnessScene {

    /**
     * Initializes scene GL resources. Called once before the render loop begins.
     *
     * <p>All configuration (outputDir, outputName, camera, scheduler, runner)
     * is available via the context object. Use {@code ctx.getCamera3D()},
     * {@code ctx.getTaskScheduler()}, {@code ctx.getRunner()}, etc.</p>
     *
     * @param ctx the harness context containing all configuration and shared resources
     */
    void init(HarnessContext ctx);

    /**
     * Renders a single frame. Called once per iteration of the render loop.
     *
     * @param deltaTime  time since last frame in seconds
     * @param elapsedTime total time since scene start in seconds
     * @param frameNumber the current frame number (1-based)
     */
    void renderFrame(float deltaTime, double elapsedTime, long frameNumber);

    /**
     * Releases all GL resources held by this scene. Called once after the
     * render loop exits.
     */
    void cleanup();

    /**
     * Whether this scene requests program shutdown when it finishes or
     * when the window is closed.
     *
     * <p>Default is {@code true}. Override to return {@code false} if the
     * harness should continue running after this scene completes (e.g.,
     * when chaining scenes).</p>
     *
     * @return true if the program should exit after this scene
     */
    boolean shouldShutdownOnComplete();

    /**
     * Whether the scene's render loop should continue running.
     *
     * <p>The harness checks this each frame. Return {@code false} to signal
     * that the scene is done and should transition to cleanup.</p>
     *
     * @return true if the render loop should continue
     */
    boolean isRunning();

    /**
     * Whether this scene uses the 3D camera system.
     *
     * <p>Default is {@code true} for InteractiveHarnessScene implementations.
     * If false, the harness skips camera update and floor rendering.</p>
     *
     * @return true if the 3D camera should be active
     */
    boolean uses3DCamera();
}
