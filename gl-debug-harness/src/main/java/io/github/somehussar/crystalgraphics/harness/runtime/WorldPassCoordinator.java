package io.github.somehussar.crystalgraphics.harness.runtime;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.camera.FloorRenderer;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.config.WorldSettings;
import io.github.somehussar.crystalgraphics.harness.util.HarnessProjectionUtil;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.logging.Logger;

/**
 * Coordinates world-base rendering passes (floor plane, sky clear) as a
 * single owned unit rather than ad-hoc branches in the runner.
 *
 * <p>The world pass is the first rendering stage in each interactive frame.
 * It clears the framebuffer with the sky color and renders the floor plane
 * (if the scene uses a 3D camera). Previously, floor rendering was a
 * one-off private method in {@code InteractiveSceneRunner} — this
 * coordinator elevates it to an explicit world-base contributor with
 * proper lifecycle ownership.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li><b>Sky clear</b> — clears color and depth buffers with the resolved
 *       sky color from {@link WorldSettings}</li>
 *   <li><b>Floor rendering</b> — renders the ground plane at Y=0 using the
 *       camera's view matrix and perspective projection</li>
 *   <li><b>Resize</b> — propagates resize events to the floor renderer</li>
 *   <li><b>Lifecycle</b> — owns init and delete of the floor renderer</li>
 * </ul>
 *
 * <h3>Frame ordering</h3>
 * <p>The world pass executes after the world pass GL state setup
 * ({@link io.github.somehussar.crystalgraphics.harness.util.RenderPassState#beginWorldPass()})
 * and before the scene pass. The floor must be drawn before scene content
 * so that 3D text and objects composite over the floor plane.</p>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * @see io.github.somehussar.crystalgraphics.harness.util.RenderPassState#beginWorldPass()
 */
public final class WorldPassCoordinator {

    private static final Logger LOGGER = Logger.getLogger(WorldPassCoordinator.class.getName());

    private final FloorRenderer floorRenderer;
    private final WorldSettings worldSettings;

    /**
     * Creates a new world pass coordinator.
     *
     * @param floorRenderer the floor plane renderer (ownership transferred to this coordinator)
     * @param worldSettings the immutable world settings for sky/floor colors
     */
    public WorldPassCoordinator(FloorRenderer floorRenderer, WorldSettings worldSettings) {
        if (floorRenderer == null) {
            throw new IllegalArgumentException("floorRenderer must not be null");
        }
        if (worldSettings == null) {
            throw new IllegalArgumentException("worldSettings must not be null");
        }
        this.floorRenderer = floorRenderer;
        this.worldSettings = worldSettings;
    }

    /**
     * Initializes the floor renderer's GL resources.
     *
     * <p>Must be called once with a valid GL context before any rendering.
     * Passes the resolved {@link WorldSettings} to the floor renderer
     * for vertex color/geometry setup.</p>
     */
    public void init() {
        floorRenderer.init(worldSettings);
        LOGGER.info("[WorldPassCoordinator] Floor renderer initialized.");
    }

    /**
     * Executes the world pass: clears the framebuffer and renders the floor.
     *
     * <p>This method performs two steps in order:</p>
     * <ol>
     *   <li>Clear the color and depth buffers with the sky color from
     *       the resolved world settings</li>
     *   <li>If the scene uses a 3D camera, render the floor plane using
     *       the current camera view and perspective projection</li>
     * </ol>
     *
     * <p>The caller must invoke
     * {@link io.github.somehussar.crystalgraphics.harness.util.RenderPassState#beginWorldPass()}
     * before this method to set the required GL state (depth ON, blend OFF).</p>
     *
     * @param ctx          the harness context (provides viewport dimensions)
     * @param camera       the 3D camera (provides view matrix for floor rendering)
     * @param uses3DCamera whether the active scene uses the 3D camera system;
     *                     if false, only the sky clear is performed
     */
    public void executeWorldPass(HarnessContext ctx, Camera3D camera, boolean uses3DCamera) {
        // Clear framebuffer with sky color from resolved world settings
        GL11.glClearColor(worldSettings.getSkyR(), worldSettings.getSkyG(),
                worldSettings.getSkyB(), 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Render floor plane before scene content so 3D objects composite
        // over the floor rather than over the raw sky clear color.
        if (uses3DCamera) {
            renderFloor(ctx, camera);
        }
    }

    /**
     * Renders the floor plane using the current camera view and perspective projection.
     *
     * <p>Computes the MVP matrix from the perspective projection (based on current
     * viewport aspect ratio) and camera view matrix, then passes the column-major
     * float[16] to the floor renderer.</p>
     *
     * @param ctx    the harness context (provides viewport dimensions for aspect ratio)
     * @param camera the 3D camera (provides the view matrix)
     */
    private void renderFloor(HarnessContext ctx, Camera3D camera) {
        ViewportState vp = ctx.getViewport();
        Matrix4f projection = HarnessProjectionUtil.perspective(
                vp.getWidth(), vp.getHeight());

        Matrix4f viewMatrix = camera.getViewMatrix();

        // MVP = projection * view (no model transform for floor — it sits at world origin)
        Matrix4f mvp = new Matrix4f();
        projection.mul(viewMatrix, mvp);

        float[] mvpArray = new float[16];
        mvp.get(mvpArray);

        floorRenderer.render(mvpArray);
    }

    /**
     * Propagates a display resize event to the floor renderer.
     *
     * <p>This is the single resize notification path for world-base
     * contributors. The floor renderer's current implementation is a
     * no-op (MVP is computed fresh each frame), but routing through the
     * coordinator ensures future contributors are handled uniformly.</p>
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onResize(int newWidth, int newHeight) {
        floorRenderer.onDisplayResize(newWidth, newHeight);
    }

    /**
     * Releases all GL resources held by the floor renderer.
     *
     * <p>Called during runner shutdown. After this call, no world pass
     * methods should be invoked.</p>
     */
    public void delete() {
        floorRenderer.delete();
        LOGGER.info("[WorldPassCoordinator] Floor renderer deleted.");
    }

    /**
     * Returns the floor renderer owned by this coordinator.
     *
     * <p><b>Internal use only</b> — exposed for the resize handler during
     * migration. External code should interact with the coordinator.</p>
     *
     * @return the floor renderer
     */
    FloorRenderer getFloorRenderer() {
        return floorRenderer;
    }

    @Override
    public String toString() {
        return "WorldPassCoordinator[worldSettings=" + worldSettings + "]";
    }
}
