package com.crystalgraphics.api.vertex;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * GC-friendly matrix transform helpers for vertex emission.
 *
 * <p>All methods use thread-local scratch vectors to avoid per-call heap allocation.
 * Safe for hot paths (text emission, draw-list recording, world-space transforms).</p>
 *
 * <p>Replaces the removed allocating default methods on {@link CgVertexConsumer}.</p>
 */
public final class CgVertexTransformUtil {

    private static final ThreadLocal<Vector4f> SCRATCH_VEC4 = ThreadLocal.withInitial(() -> new Vector4f());

    private static final ThreadLocal<Vector3f> SCRATCH_VEC3 = ThreadLocal.withInitial(() -> new Vector3f());

    private CgVertexTransformUtil() {}

    /**
     * Transforms a 2D position by the given matrix and writes it to the consumer.
     * Uses a thread-local scratch vector — zero allocations.
     */
    public static CgVertexConsumer vertex(CgVertexConsumer consumer, Matrix4f matrix, float x, float y) {
        Vector4f v = transformPosition(matrix, x, y);
        return consumer.vertex(v.x(), v.y());
    }

    /**
     * Transforms a 3D position by the given matrix and writes it to the consumer.
     * Uses a thread-local scratch vector — zero allocations.
     */
    public static CgVertexConsumer vertex(CgVertexConsumer consumer, Matrix4f matrix, float x, float y, float z) {
        Vector4f v = transformPosition(matrix, x, y, z);
        return consumer.vertex(v.x(), v.y(), v.z());
    }

    /**
     * Transforms a normal by the given matrix and writes it to the consumer.
     * Uses a thread-local scratch vector — zero allocations.
     */
    public static CgVertexConsumer normal(CgVertexConsumer consumer, Matrix3f matrix, float x, float y, float z) {
        Vector3f v = transformPosition(matrix, x, y, z);
        return consumer.normal(v.x(), v.y(), v.z());
    }

    public static Vector3f transformPosition(Matrix3f matrix, float x, float y, float z) {
        Vector3f v = SCRATCH_VEC3.get();
        v.set(x, y, z);
        matrix.transform(v);
        return v;
    }

    public static Vector4f transformPosition(Matrix4f matrix, float x, float y) {
        Vector4f v = SCRATCH_VEC4.get();
        v.set(x, y, 0, 1.0f);
        matrix.transform(v);
        return v;
    }
    public static Vector4f transformPosition(Matrix4f matrix, float x, float y, float z) {
        Vector4f v = SCRATCH_VEC4.get();
        v.set(x, y, z, 1.0f);
        matrix.transform(v);
        return v;
    }
}
