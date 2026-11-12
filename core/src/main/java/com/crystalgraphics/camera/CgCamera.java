package com.crystalgraphics.camera;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

/**
 * What a raster pass is seen through: view, projection, the camera's position, resolution and time. Given to a pass
 * with {@code CgRasterPass.camera}; the depth convention, the target's encoding and the environment (sun, fog) are the
 * renderer's, from the target the pass draws into.
 *
 * <pre>{@code
 * CgCamera camera = CgCamera.perspective(60f, w / (float) h, 0.05f, 100f).lookAt(eye, target).time(seconds);
 * rec.raster("preview").color(picture, CgLoad.CLEAR).camera(camera);
 *
 * CgCamera screen = CgCamera.screen(w, h);   // pixels, y down, as a UI draws
 * }</pre>
 *
 * <ul>
 *   <li>A value as given: a pass copies it at {@code camera(...)}, so moving it afterwards moves no recorded pass.
 *       Keep one and change it per frame; nothing allocates.</li>
 *   <li>Its position is where the view sees from unless {@link #position} sets it.</li>
 *   <li>Its resolution is its pass's target's unless {@link #resolution} sets it: a current target's is the one open
 *       when the pass is recorded.</li>
 *   <li>Projections take GL's clip convention (depth -1 to 1, far at 1); the renderer maps it to the target's.</li>
 * </ul>
 */
public final class CgCamera {

    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private float x, y, z;
    private boolean positioned;
    private float width, height;
    private double time;

    /** At the origin, identity view and projection: clip space as given. */
    public CgCamera() {
    }

    /** A perspective camera: {@code fovY} degrees, {@code aspect} width over height, {@code near} and {@code far} distances. */
    public static CgCamera perspective(float fovY, float aspect, float near, float far) {
        return new CgCamera().perspectiveLens(fovY, aspect, near, far);
    }

    /** An orthographic camera over the view-space box given. */
    public static CgCamera orthographic(float left, float right, float bottom, float top, float near, float far) {
        CgCamera camera = new CgCamera();
        camera.projection.setOrtho(left, right, bottom, top, near, far);
        return camera;
    }

    /** A camera in pixels of a {@code width} x {@code height} target, origin top-left, y down: what a UI draws through. */
    public static CgCamera screen(float width, float height) {
        return new CgCamera().screenLens(width, height);
    }

    /** Its projection made a perspective one. @see #perspective */
    public CgCamera perspectiveLens(float fovY, float aspect, float near, float far) {
        projection.setPerspective((float) Math.toRadians(fovY), aspect, near, far);
        return this;
    }

    /** Looks from {@code eye} at {@code target}, +y up. */
    public CgCamera lookAt(Vector3fc eye, Vector3fc target) {
        return lookAt(eye.x(), eye.y(), eye.z(), target.x(), target.y(), target.z());
    }

    /** Looks from {@code (ex, ey, ez)} at {@code (tx, ty, tz)}, +y up. */
    public CgCamera lookAt(float ex, float ey, float ez, float tx, float ty, float tz) {
        view.setLookAt(ex, ey, ez, tx, ty, tz, 0f, 1f, 0f);
        return this;
    }

    /** Its view, the world-to-view transform. */
    public CgCamera view(Matrix4fc view) {
        this.view.set(view);
        return this;
    }

    /** Its projection. */
    public CgCamera projection(Matrix4fc projection) {
        this.projection.set(projection);
        return this;
    }

    /** Its view, to change in place. */
    public Matrix4f view() {
        return view;
    }

    /** Its projection, to change in place. */
    public Matrix4f projection() {
        return projection;
    }

    /** The camera's world position, where it is not the view's eye: a camera-relative view's absolute place. */
    public CgCamera position(float x, float y, float z) {
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

    /** Its projection made {@link #screen}'s, for a target of {@code width} x {@code height}, its view the identity. */
    public CgCamera screenLens(float width, float height) {
        view.identity();
        projection.setOrtho(0f, width, height, 0f, -1f, 1f);
        return resolution(width, height);
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

    /** Whether {@link #position} placed it; else it is where its view sees from. */
    public boolean positioned() {
        return positioned;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float z() {
        return z;
    }
}
