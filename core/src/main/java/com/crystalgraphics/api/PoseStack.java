package com.crystalgraphics.api;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A matrix stack with Minecraft 1.20.1's {@code PoseStack} API, held entirely on the CPU: a draw reads
 * the top {@link Pose} and bakes it into its own vertex or instance data.
 *
 * <pre>{@code
 * PoseStack poses = new PoseStack();
 * poses.pushPose();
 * poses.translate(x, y, 0);
 * poses.scale(2f, 2f, 1f);
 * draw(poses.last().pose());
 * poses.popPose();
 * }</pre>
 *
 * <p>Transforms modify the <em>top</em> entry in place. Every {@link #pushPose()} needs its
 * {@link #popPose()}; popping the base entry throws. Nothing here touches GL's fixed-function matrices.</p>
 *
 * <p>Not thread-safe; render thread only.</p>
 *
 * @see Pose
 */
public class PoseStack {

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

    /** Pushes a copy of the current top entry onto the stack. */
    public void pushPose() {
        Pose current = this.poseStack.getLast();
        this.poseStack.addLast(new Pose(
            new Matrix4f(current.pose),
            new Matrix3f(current.normal)
        ));
    }

    /**
     * Pops the top entry from the stack.
     *
     * @throws java.util.NoSuchElementException if the stack would become empty
     */
    public void popPose() {
        this.poseStack.removeLast();
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
