package io.github.somehussar.crystalgraphics.api;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A matrix stack that mirrors the 1.20.1 Minecraft {@code PoseStack} API
 * while synchronizing with the OpenGL 1.x fixed-function {@code GL_MODELVIEW}
 * matrix stack.
 *
 * <p>Each entry on the stack holds a 4×4 pose (model-view) matrix and a 3×3
 * normal matrix.  All transform methods ({@link #translate}, {@link #scale},
 * {@link #mulPose}, {@link #rotateAround}, {@link #mulPoseMatrix}) modify
 * the <em>top</em> entry in place — exactly as the 1.20.1 implementation
 * does.</p>
 *
 * <h3>Fixed-Function GL Integration</h3>
 * <p>This PoseStack manages the <b>MODELVIEW</b> matrix.  On every
 * {@link #pushPose()} call it pushes the GL matrix stack and loads the
 * current JOML pose matrix into GL via {@code glLoadMatrix}.  On every
 * {@link #popPose()} it pops the GL stack.  Callers must ensure that
 * {@code GL_MODELVIEW} is the active matrix mode before using this class
 * (which is the default during MC 1.7.10 world rendering).</p>
 *
 * <h3>Why MODELVIEW and not PROJECTION?</h3>
 * <ul>
 *   <li>The 1.20.1 PoseStack is used exclusively for model-view transforms
 *       (entity positioning, block rendering, GUI elements).  Projection is
 *       handled separately.</li>
 *   <li>The API surface (translate, scale, quaternion rotation, rotateAround)
 *       maps directly to model-view operations.  Projection setup uses
 *       {@code perspective}/{@code ortho} — operations this class does not
 *       expose.</li>
 *   <li>The GL {@code GL_MODELVIEW} stack is guaranteed ≥32 deep; the
 *       {@code GL_PROJECTION} stack is only guaranteed 2 deep.  PoseStack
 *       with nested push/pop requires the deeper stack.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used on the render thread.</p>
 *
 * @see Pose
 */
public class PoseStack {

    /**
     * Thread-local FloatBuffer for uploading 4×4 matrices to GL.
     * Allocated once per thread, reused across all {@link #syncToGL()} calls.
     */
    private static final ThreadLocal<FloatBuffer> MATRIX_BUFFER = ThreadLocal.withInitial(() -> BufferUtils.createFloatBuffer(16));

    private final Deque<Pose> poseStack;

    /**
     * Creates a new PoseStack with a single identity entry.
     */
    public PoseStack() {
        this.poseStack = new ArrayDeque<Pose>();
        this.poseStack.add(new Pose(new Matrix4f(), new Matrix3f()));
    }

    /**
     * Translates the top pose matrix by the given amounts (double precision,
     * cast to float internally).
     *
     * @param x translation along the X axis
     * @param y translation along the Y axis
     * @param z translation along the Z axis
     */
    public void translate(double x, double y, double z) {
        translate((float) x, (float) y, (float) z);
    }

    /**
     * Translates the top pose matrix by the given amounts.
     *
     * <p>Only the 4×4 pose matrix is affected; the 3×3 normal matrix is
     * unchanged (translation does not affect normals).</p>
     *
     * @param x translation along the X axis
     * @param y translation along the Y axis
     * @param z translation along the Z axis
     */
    public void translate(float x, float y, float z) {
        Pose pose = this.poseStack.getLast();
        pose.pose.translate(x, y, z);
    }

    /**
     * Scales the top pose matrix by the given factors.
     *
     * <p>The normal matrix is updated to maintain correct lighting:
     * <ul>
     *   <li>Uniform positive scale: normals are unchanged.</li>
     *   <li>Uniform negative scale: normals are negated (flipped winding).</li>
     *   <li>Non-uniform scale: normals are scaled by the inverse cube root
     *       of the reciprocal scale product, preserving correct normal
     *       direction under non-uniform transforms.</li>
     * </ul></p>
     *
     * @param x scale factor along the X axis
     * @param y scale factor along the Y axis
     * @param z scale factor along the Z axis
     */
    public void scale(float x, float y, float z) {
        Pose pose = this.poseStack.getLast();
        pose.pose.scale(x, y, z);

        // Normal matrix handling — matches 1.20.1 exactly
        if (x == y && y == z) {
            // Uniform scale
            if (x > 0.0f) {
                // Positive uniform: normals unaffected
                return;
            }
            // Negative uniform: flip normals
            pose.normal.scale(-1.0f);
        } else {
            // Non-uniform scale: compute adjusted normal scale factors
            float f = 1.0f / x;
            float f1 = 1.0f / y;
            float f2 = 1.0f / z;
            float f3 = fastInvCubeRoot(f * f1 * f2);
            pose.normal.scale(f3 * f, f3 * f1, f3 * f2);
        }
    }

    /**
     * Post-multiplies the top pose and normal matrices by the rotation
     * represented by the given quaternion.
     *
     * @param quaternion the rotation to apply
     */
    public void mulPose(Quaternionf quaternion) {
        Pose pose = this.poseStack.getLast();
        pose.pose.rotate(quaternion);
        pose.normal.rotate(quaternion);
    }

    /**
     * Rotates the top pose matrix around a point, and applies the
     * corresponding rotation to the normal matrix.
     *
     * <p>This is equivalent to translating to {@code (x, y, z)}, rotating
     * by the quaternion, and translating back — but done in a single
     * efficient operation on the pose matrix.</p>
     *
     * @param quaternion the rotation to apply
     * @param x          X coordinate of the rotation center
     * @param y          Y coordinate of the rotation center
     * @param z          Z coordinate of the rotation center
     */
    public void rotateAround(Quaternionf quaternion, float x, float y, float z) {
        Pose pose = this.poseStack.getLast();
        pose.pose.rotateAround(quaternion, x, y, z);
        pose.normal.rotate(quaternion);
    }

    /**
     * Pushes a copy of the current top entry onto the stack, then
     * synchronizes the JOML matrix to the GL fixed-function MODELVIEW stack.
     *
     * <p>This calls {@code GL11.glPushMatrix()} to preserve the current GL
     * state, then loads the JOML pose matrix into GL via
     * {@code GL11.glLoadMatrix()}.  The GL matrix mode must be
     * {@code GL_MODELVIEW} when this is called.</p>
     */
    public void pushPose() {
        Pose current = this.poseStack.getLast();
        this.poseStack.addLast(new Pose(
            new Matrix4f(current.pose),
            new Matrix3f(current.normal)
        ));

        // Sync to fixed-function GL
        GL11.glPushMatrix();
        syncToGL();
    }

    /**
     * Pops the top entry from the stack and restores the previous GL
     * MODELVIEW matrix.
     *
     * <p>Calls {@code GL11.glPopMatrix()} to restore the GL state that was
     * saved by the matching {@link #pushPose()} call.</p>
     *
     * @throws java.util.NoSuchElementException if the stack would become empty
     */
    public void popPose() {
        this.poseStack.removeLast();
        GL11.glPopMatrix();
    }

    /**
     * Returns the current (topmost) entry on the stack.
     *
     * @return the top {@link Pose}, never {@code null}
     * @throws java.util.NoSuchElementException if the stack is empty
     */
    public Pose last() {
        return this.poseStack.getLast();
    }

    /**
     * Returns whether the stack is in its initial state (only the base
     * entry remains).
     *
     * <p>This matches the 1.20.1 semantics where {@code clear()} returns
     * {@code true} when the stack has been fully unwound to its starting
     * state.</p>
     *
     * @return {@code true} if the stack contains exactly one entry
     */
    public boolean clear() {
        return this.poseStack.size() == 1;
    }

    /**
     * Resets the top entry to identity matrices (both pose and normal).
     */
    public void setIdentity() {
        Pose pose = this.poseStack.getLast();
        pose.pose.identity();
        pose.normal.identity();
    }

    /**
     * Post-multiplies the top pose matrix by the given 4×4 matrix.
     *
     * <p>Only the pose matrix is affected; the normal matrix is unchanged.
     * If the given matrix includes a non-orthogonal component, the caller
     * is responsible for updating the normal matrix separately.</p>
     *
     * @param matrix the matrix to multiply with
     */
    public void mulPoseMatrix(Matrix4f matrix) {
        this.poseStack.getLast().pose.mul(matrix);
    }

    // ---- GL synchronization ----

    /**
     * Loads the current top pose matrix into the active GL matrix (expected
     * to be {@code GL_MODELVIEW}).
     *
     * <p>Uses a thread-local {@link FloatBuffer} to avoid per-call allocation.
     * The matrix is written in column-major order as required by OpenGL.</p>
     */
    private void syncToGL() {
        FloatBuffer buf = MATRIX_BUFFER.get();
        buf.clear();
        this.poseStack.getLast().pose.get(buf);
        buf.flip();
        GL11.glLoadMatrix(buf);
    }

    // ---- Utility ----

    /**
     * Computes the fast inverse cube root of a value.
     *
     * <p>Equivalent to {@code Mth.fastInvCubeRoot()} in 1.20.1 Minecraft.
     * Uses the classic "fast inverse square root" approach adapted for cube
     * roots, providing a good approximation with a single Newton-Raphson
     * refinement step.</p>
     *
     * @param value the input value
     * @return an approximation of {@code 1 / cbrt(value)}
     */
    private static float fastInvCubeRoot(float value) {
        int i = Float.floatToIntBits(value);
        i = 0x54A2FA8C - i / 3;
        float f = Float.intBitsToFloat(i);
        f = 0.6666667F * f + 1.0F / (3.0F * f * f * value);
        f = 0.6666667F * f + 1.0F / (3.0F * f * f * value);
        return f;
    }

    // ---- Inner class ----

    /**
     * A single entry on the {@link PoseStack}, holding a 4×4 pose
     * (model-view) matrix and a 3×3 normal matrix.
     *
     * <p>The normal matrix should be the inverse-transpose of the upper-left
     * 3×3 of the pose matrix.  The PoseStack transform methods maintain this
     * invariant automatically.</p>
     *
     * <p>Matches the 1.20.1 {@code PoseStack.Pose} inner class exactly.</p>
     */
    public static final class Pose {

        final Matrix4f pose;
        final Matrix3f normal;

        Pose(Matrix4f pose, Matrix3f normal) {
            this.pose = pose;
            this.normal = normal;
        }

        /**
         * Returns the 4×4 model-view matrix.
         *
         * @return the pose matrix (mutable reference — modifications affect
         *         this entry directly)
         */
        public Matrix4f pose() {
            return this.pose;
        }

        /**
         * Returns the 3×3 normal matrix.
         *
         * @return the normal matrix (mutable reference — modifications affect
         *         this entry directly)
         */
        public Matrix3f normal() {
            return this.normal;
        }
    }
}
