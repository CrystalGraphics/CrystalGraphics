package com.crystalgraphics.render.stage;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;

/**
 * Where a host's frame is seen from: its camera's absolute position, and the view and projection the host draws its
 * own world with. {@link #view} maps a point <em>relative to that position</em> into view space, as every Minecraft
 * draws its world, so a far coordinate keeps its precision: the subtraction happens in doubles, before anything is a
 * float.
 *
 * <pre>{@code
 * // a renderer: the absolute point (wx, wy, wz) in the host's view space
 * CgHostView view = frame.host().view();
 * Vector4f p = scratch.set((float) (wx - view.x()), (float) (wy - view.y()), (float) (wz - view.z()), 1f)
 *         .mul(view.view());
 *
 * // a host, once per level render, into the view its stage's frame owns: copies, allocates nothing
 * CgRenderStage.WORLD_OPAQUE.host().view().set(camera.x, camera.y, camera.z, viewRotation, projection);
 * }</pre>
 *
 * <ul>
 *   <li>One instance per {@link CgHostFrame}, refilled every frame: read it during a stage, copy what you keep.</li>
 *   <li>The matrices are the host's own: view bobbing, FOV effects and its depth convention are already in them.</li>
 *   <li>{@link #view} is a rotation from Minecraft 1.14; 1.13 and older fold the eye height and a third-person
 *       offset into it, paired with a position at the camera entity's feet.</li>
 * </ul>
 */
public final class CgHostView {

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f scratch = new Vector4f();
    private double x;
    private double y;
    private double z;

    /** The camera's absolute position: what the host subtracts from a world point before {@link #view}. */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** A point relative to the position, to view space. */
    public Matrix4fc view() {
        return view;
    }

    /** View space to clip space. */
    public Matrix4fc projection() {
        return projection;
    }

    /**
     * Where the absolute point ({@code x}, {@code y}, {@code z}) is seen on a {@code width} by {@code height}
     * rectangle, origin top left, y down, into {@code out}; false, and {@code out} untouched, behind the camera.
     * Unreal's {@code FSceneView::ProjectWorldToScreen}. A point off the sides still answers, outside the rectangle.
     *
     * <pre>{@code
     * // a damage number over its target, at a GUI stage, in its canvas's pixels
     * if (CgRenderStage.WORLD_OPAQUE.host().view().project(x, y, z, frame.host().width(), frame.host().height(), at)) {
     *     frame.canvas().quad(DIGIT).at(at.x - 16, at.y - 16).size(32, 32).submit();
     * }
     * }</pre>
     *
     * Render thread: it reuses one scratch vector.
     */
    public boolean project(double x, double y, double z, float width, float height, Vector2f out) {
        Vector4f p = scratch.set((float) (x - this.x), (float) (y - this.y), (float) (z - this.z), 1f).mul(view)
                .mul(projection);
        if (p.w <= 0f) return false;
        out.set((p.x / p.w * 0.5f + 0.5f) * width, (0.5f - p.y / p.w * 0.5f) * height);
        return true;
    }

    /** Host side: this frame's camera, copied in. */
    public CgHostView set(double x, double y, double z, Matrix4fc view, Matrix4fc projection) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.view.set(view);
        this.projection.set(projection);
        return this;
    }

    /** Host side: another view's camera, copied in — the transparent stage takes the opaque one's. */
    public CgHostView set(CgHostView other) {
        return set(other.x, other.y, other.z, other.view, other.projection);
    }

    /**
     * Host side: as {@link #set(double, double, double, Matrix4fc, Matrix4fc)}, from column-major buffers — GL's
     * readback, Minecraft's own — read at their positions, which are left where they are.
     */
    public CgHostView set(double x, double y, double z, FloatBuffer view, FloatBuffer projection) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.view.set(view);
        this.projection.set(projection);
        return this;
    }
}
