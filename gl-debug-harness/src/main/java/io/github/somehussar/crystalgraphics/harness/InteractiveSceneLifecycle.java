package io.github.somehussar.crystalgraphics.harness;

/**
 * Extended lifecycle contract for interactive scenes with continuous render loops.
 *
 * <p>This sub-interface of {@link HarnessSceneLifecycle} adds the interactive
 * render-loop control hooks that the {@link InteractiveSceneRunner} needs:
 * running state, 3D camera mode, and shutdown control.</p>
 *
 * <h3>Lifecycle for interactive scenes</h3>
 * <pre>
 *   init(ctx)
 *   while (isRunning() &amp;&amp; !Display.isCloseRequested()) {
 *       // runtime: input → camera → scheduler → clear → floor
 *       render(ctx, frameInfo)
 *       // runtime: state reset → pause → HUD → capture → swap
 *   }
 *   dispose()
 * </pre>
 *
 * <h3>Key difference from managed scenes</h3>
 * <p>Managed scenes call {@code render()} exactly once. Interactive scenes
 * call it in a loop until {@link #isRunning()} returns false or the window
 * is closed. The runtime separation is preserved — interactive scenes are
 * never forced through the managed execution path.</p>
 *
 * @see HarnessSceneLifecycle
 * @see InteractiveSceneRunner
 */
public interface InteractiveSceneLifecycle extends HarnessSceneLifecycle {

    /**
     * Whether the scene's render loop should continue running.
     *
     * <p>The runtime checks this each frame. Return {@code false} to signal
     * that the scene is done and should transition to dispose.</p>
     *
     * @return true if the render loop should continue
     */
    boolean isRunning();

    /**
     * Whether this scene uses the 3D camera system.
     *
     * <p>If true, the runtime enables camera movement, floor rendering,
     * and HUD display. If false, the runtime skips these subsystems.</p>
     *
     * @return true if the 3D camera should be active
     */
    boolean uses3DCamera();

    /**
     * Whether this scene requests program shutdown when it finishes.
     *
     * <p>Default is {@code true}. Override to return {@code false} if the
     * harness should continue after this scene completes.</p>
     *
     * @return true if the program should exit after this scene
     */
    boolean shouldShutdownOnComplete();
}
