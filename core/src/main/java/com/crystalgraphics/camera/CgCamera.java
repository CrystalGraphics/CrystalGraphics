package com.crystalgraphics.camera;

import com.crystalgraphics.render.draw.CgPassConstants;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

/**
 * Where something is seen from: view, projection, the camera's absolute position, resolution and time. The host's
 * camera at a stage, a stage frame's camera, a preview's or a scene's own: one type. Given to a raster pass with
 * {@code CgRasterPass.camera}, to a canvas with {@code CgCanvas.camera}; the depth convention and the target's
 * encoding are the renderer's.
 *
 * <pre>{@code
 * // inside a stage: the one it hands out, never a new one
 * CgCamera camera = frame.camera();                       // the world's at a world stage, the screen's at a GUI stage
 * rec.raster("mymod.glow").color(frame.target(), CgLoad.load()).camera(camera);
 * camera.project(x, y, z, width, height, at);             // where an absolute point lands
 *
 * // outside any stage: a preview, a thumbnail -- kept in a field, moved each frame
 * CgCamera preview = CgCamera.perspective(60f, w / (float) h, 0.05f, 100f).lookAt(eye, target).time(seconds);
 * CgCamera screen = CgCamera.screen(w, h);                 // pixels, y down, as a UI draws
 *
 * // a projection of your own, as the engine takes them
 * CgCamera.perspectiveMatrix(projection, 70f, w / (float) h, 0.05f, Float.POSITIVE_INFINITY);
 * camera.set(x, y, z, view, projection);
 * }</pre>
 *
 * <ul>
 *   <li>Its view maps a point relative to its {@linkplain #originX origin} into view space. A camera made here has its
 *       origin at 0, an absolute view; a host's camera ({@link #set(double, double, double, Matrix4fc, Matrix4fc)}) has
 *       it at the camera, as Minecraft draws its world, so a far coordinate keeps its precision: the subtraction
 *       happens in doubles, before anything is a float.</li>
 *   <li>Its position is where the view sees from unless {@link #position} or {@code set} placed it.</li>
 *   <li>A pass copies it at {@code camera(...)}: moving it afterwards moves no recorded pass. Keep one and change it
 *       per frame; nothing allocates.</li>
 *   <li>Its resolution is its pass's target's unless {@link #resolution} names one.</li>
 *   <li>Projections are the engine's, Unreal's reversed Z: near at depth 1, far at 0, clip depth 0 to 1. Build them
 *       with {@link #perspectiveMatrix} and {@link #orthographicMatrix}; JOML's {@code perspective} and
 *       {@code setOrtho} build GL's, which draws everything behind everything. A projection a host hands over goes
 *       through {@link #engineFrom}. The renderer converts to a target drawn in a host's standard depth.</li>
 * </ul>
 */
public final class CgCamera {

    /**
     * What an engine view adds to its camera's pass constants: a stage's environment (sun, fog), the last frame's view
     * of it, the scene's encoding. A stage frame's camera has one; a camera of your own has none.
     */
    @FunctionalInterface
    public interface ViewState {
        void write(CgPassConstants constants);
    }

    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Vector4f scratch = new Vector4f();
    private final Vector3f eye = new Vector3f();
    /** The point its view is relative to, and its position where placed. */
    private double originX, originY, originZ, x, y, z;
    private boolean positioned;
    private float width, height;
    private double time;
    @Nullable
    private ViewState state;

    /** At the origin, identity view and projection: clip space as given. */
    public CgCamera() {
    }

    /** A camera whose pass constants {@code state} completes: an engine view's. */
    public CgCamera(ViewState state) {
        this.state = state;
    }

    /** A perspective camera: {@code fovY} degrees, {@code aspect} width over height, {@code near} and {@code far} distances. */
    public static CgCamera perspective(float fovY, float aspect, float near, float far) {
        return new CgCamera().perspectiveLens(fovY, aspect, near, far);
    }

    /** An orthographic camera over the view-space box given. */
    public static CgCamera orthographic(float left, float right, float bottom, float top, float near, float far) {
        CgCamera camera = new CgCamera();
        orthographicMatrix(camera.projection, left, right, bottom, top, near, far);
        return camera;
    }

    /**
     * {@code dest} made the engine's perspective projection (Unreal's {@code FReversedZPerspectiveMatrix}): {@code fovY}
     * degrees, {@code aspect} width over height, {@code near} at depth 1, {@code far} at 0. A far of
     * {@link Float#POSITIVE_INFINITY} has no far plane.
     *
     * <pre>{@code
     * CgCamera.perspectiveMatrix(projection, 70f, 16f / 9f, 0.05f, 300f);
     * CgCamera.perspectiveMatrix(projection, 70f, 16f / 9f, 0.05f, Float.POSITIVE_INFINITY);
     * }</pre>
     */
    public static Matrix4f perspectiveMatrix(Matrix4f dest, float fovY, float aspect, float near, float far) {
        return dest.setPerspective((float) Math.toRadians(fovY), aspect, far, near, true);
    }

    /**
     * {@code dest} made the engine's orthographic projection over the view-space box given: {@code near} at depth 1,
     * {@code far} at 0.
     *
     * <pre>{@code
     * CgCamera.orthographicMatrix(projection, 0, w, h, 0, -1, 1);   // pixels, y down
     * }</pre>
     */
    public static Matrix4f orthographicMatrix(Matrix4f dest, float left, float right, float bottom, float top,
                                              float near, float far) {
        return dest.setOrtho(left, right, bottom, top, far, near, true);
    }

    /**
     * {@code projection}, a host's, made the engine's in place: as it is captured, from the host's convention, reversed
     * or not, its clip depth 0 to 1 or GL's -1 to 1. Clip depth is linear in z and w, so any projection converts, a
     * bobbing or tilted one too.
     *
     * <pre>{@code
     * CgCamera.engineFrom(camera.projection(), false, false);   // GL's: Minecraft before 26.1
     * CgCamera.engineFrom(camera.projection(), true, true);     // already the engine's: Minecraft 26.2, untouched
     * }</pre>
     */
    public static Matrix4f engineFrom(Matrix4f projection, boolean reversed, boolean zeroToOne) {
        if (reversed && zeroToOne) return projection;
        float sign = reversed ? 1f : -1f, half = zeroToOne ? 1f : 0.5f;
        return projection.m02((sign * projection.m02() + projection.m03()) * half)
                .m12((sign * projection.m12() + projection.m13()) * half)
                .m22((sign * projection.m22() + projection.m23()) * half)
                .m32((sign * projection.m32() + projection.m33()) * half);
    }

    /** A camera in pixels of a {@code width} x {@code height} target, origin top-left, y down: what a UI draws through. */
    public static CgCamera screen(float width, float height) {
        return new CgCamera().screenLens(width, height);
    }

    // ── Setting it ───────────────────────────────────────────────────────

    /** Its projection made a perspective one. @see #perspective */
    public CgCamera perspectiveLens(float fovY, float aspect, float near, float far) {
        perspectiveMatrix(projection, fovY, aspect, near, far);
        return this;
    }

    /** Its projection made {@link #screen}'s, for a target of {@code width} x {@code height}, its view the identity. */
    public CgCamera screenLens(float width, float height) {
        view.identity();
        orthographicMatrix(projection, 0f, width, height, 0f, -1f, 1f);
        originX = originY = originZ = 0;
        positioned = false;
        return resolution(width, height);
    }

    /** Looks from {@code eye} at {@code target}, +y up: an absolute view. */
    public CgCamera lookAt(Vector3fc eye, Vector3fc target) {
        return lookAt(eye.x(), eye.y(), eye.z(), target.x(), target.y(), target.z());
    }

    /** Looks from {@code (ex, ey, ez)} at {@code (tx, ty, tz)}, +y up: an absolute view. */
    public CgCamera lookAt(float ex, float ey, float ez, float tx, float ty, float tz) {
        view.setLookAt(ex, ey, ez, tx, ty, tz, 0f, 1f, 0f);
        originX = originY = originZ = 0;
        positioned = false;
        return this;
    }

    /** Its view: an absolute one unless {@link #set} made it relative to the camera. */
    public CgCamera view(Matrix4fc view) {
        this.view.set(view);
        return this;
    }

    /** Its projection. */
    public CgCamera projection(Matrix4fc projection) {
        this.projection.set(projection);
        return this;
    }

    /** The camera's absolute position, where it is not where the view sees from. */
    public CgCamera position(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        positioned = true;
        return this;
    }

    /** The resolution its draws read, in pixels, where it is not the target's. */
    public CgCamera resolution(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Seconds: the time its draws animate by. */
    public CgCamera time(double seconds) {
        this.time = seconds;
        return this;
    }

    /**
     * A host's camera, copied in: at the absolute {@code (x, y, z)}, its view mapping a point relative to there. What a
     * host fills its stages' cameras with, once per level render; copies, allocates nothing.
     *
     * <pre>{@code
     * CgRenderStage.WORLD_OPAQUE.host().camera().set(camera.x, camera.y, camera.z, viewRotation, projection);
     * }</pre>
     */
    public CgCamera set(double x, double y, double z, Matrix4fc view, Matrix4fc projection) {
        this.view.set(view);
        this.projection.set(projection);
        return relativeTo(x, y, z);
    }

    /** As {@link #set(double, double, double, Matrix4fc, Matrix4fc)}, from column-major buffers, read where they stand. */
    public CgCamera set(double x, double y, double z, FloatBuffer view, FloatBuffer projection) {
        this.view.set(view);
        this.projection.set(projection);
        return relativeTo(x, y, z);
    }

    /** Another camera's view, projection, origin, position, resolution and time, copied in; its view state kept. */
    public CgCamera set(CgCamera other) {
        view.set(other.view);
        projection.set(other.projection);
        originX = other.originX;
        originY = other.originY;
        originZ = other.originZ;
        x = other.x;
        y = other.y;
        z = other.z;
        positioned = other.positioned;
        width = other.width;
        height = other.height;
        time = other.time;
        return this;
    }

    private CgCamera relativeTo(double x, double y, double z) {
        originX = this.x = x;
        originY = this.y = y;
        originZ = this.z = z;
        positioned = true;
        return this;
    }

    // ── Reading it ───────────────────────────────────────────────────────

    /** Its view, to read or change in place: maps a point relative to its origin into view space. */
    public Matrix4f view() {
        return view;
    }

    /** Its projection, to read or change in place. */
    public Matrix4f projection() {
        return projection;
    }

    /** The camera's absolute position: placed, or where its view sees from. */
    public double x() {
        return positioned ? x : originX + eye().x;
    }

    public double y() {
        return positioned ? y : originY + eye().y;
    }

    public double z() {
        return positioned ? z : originZ + eye().z;
    }

    /** The absolute point its view is relative to: 0 for an absolute view, the camera for a host's. */
    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    /** The resolution it names; 0 where it takes its target's. */
    public float width() {
        return width;
    }

    /** @see #width() */
    public float height() {
        return height;
    }

    /** Its time in seconds. */
    public double time() {
        return time;
    }

    /** Whether it is an orthographic one, a screen's or a GUI's. */
    public boolean isOrthographic() {
        return projection.m23() == 0f;
    }

    /** What its engine view adds to its pass constants; null for a camera of your own. */
    @Nullable
    public ViewState viewState() {
        return state;
    }

    /**
     * Where the absolute point ({@code x}, {@code y}, {@code z}) is seen on a {@code width} by {@code height}
     * rectangle, origin top left, y down, into {@code out}; false, and {@code out} untouched, behind the camera.
     * Unreal's {@code FSceneView::ProjectWorldToScreen}. A point off the sides still answers, outside the rectangle.
     *
     * <pre>{@code
     * // a damage number over its target, at a GUI stage, in its canvas's pixels
     * if (CgRenderStage.WORLD_OPAQUE.host().camera().project(x, y, z, frame.host().width(), frame.host().height(), at)) {
     *     frame.canvas().quad(DIGIT).at(at.x - 16, at.y - 16).size(32, 32).submit();
     * }
     * }</pre>
     *
     * Render thread: it reuses one scratch vector.
     */
    public boolean project(double x, double y, double z, float width, float height, Vector2f out) {
        Vector4f p = scratch.set((float) (x - originX), (float) (y - originY), (float) (z - originZ), 1f).mul(view)
                .mul(projection);
        if (p.w <= 0f) return false;
        out.set((p.x / p.w * 0.5f + 0.5f) * width, (0.5f - p.y / p.w * 0.5f) * height);
        return true;
    }

    /** Where its view sees from, relative to its origin. */
    private Vector3f eye() {
        return view.originAffine(eye);
    }
}
