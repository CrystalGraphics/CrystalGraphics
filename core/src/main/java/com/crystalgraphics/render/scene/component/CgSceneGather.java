package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.joml.FrustumIntersection;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One gather's view: its frustum, its forward, the view-projection a level is picked by, and what each primitive's
 * bounds take. Camera-relative throughout: absolute positions less the camera's origin, in double before the floats.
 */
final class CgSceneGather {

    private final Matrix4f viewProjection = new Matrix4f();
    /** JOML's test directly: the view counts what it tests and culls in bulk, not per box. */
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final CgAABB box = new CgAABB();
    private final Vector3f forward = new Vector3f();
    private float projectionScale;
    private double originX, originY, originZ, eyeX, eyeY, eyeZ;

    /** The primitive {@link #inView} last passed: its distance along the view's forward, from the eye. */
    float distance;
    /** Primitives tested and those the frustum left out, since {@link #begin}. */
    int tested, culled;

    void begin(CgCamera camera) {
        viewProjection.set(camera.projection()).mul(camera.view());
        frustum.set(viewProjection);
        camera.view().positiveZ(forward).negate();
        projectionScale = Math.abs(camera.projection().m11());
        originX = camera.originX();
        originY = camera.originY();
        originZ = camera.originZ();
        eyeX = camera.x();
        eyeY = camera.y();
        eyeZ = camera.z();
        tested = culled = 0;
    }

    /**
     * Whether {@code primitive}'s world box meets the frustum, setting {@link #distance} to its centre's; one with no
     * bounds is always in view, at its position.
     */
    boolean inView(CgPrimitiveComponent<?> primitive) {
        tested++;
        CgAABBc box = primitive.worldBounds();
        if (box == null) {
            distance = distance(primitive.x(), primitive.y(), primitive.z());
            return true;
        }
        if (!frustum.testAab((float) (box.minX() - originX), (float) (box.minY() - originY), (float) (box.minZ() - originZ),
                (float) (box.maxX() - originX), (float) (box.maxY() - originY), (float) (box.maxZ() - originZ))) {
            culled++;
            return false;
        }
        distance = distance((box.minX() + box.maxX()) * 0.5, (box.minY() + box.maxY()) * 0.5, (box.minZ() + box.maxZ()) * 0.5);
        return true;
    }

    /** How far ahead of the eye an absolute point is, along the view's forward; 0 behind it. */
    float distance(double x, double y, double z) {
        return Math.max(0f, (float) ((x - eyeX) * forward.x + (y - eyeY) * forward.y + (z - eyeZ) * forward.z));
    }

    /**
     * The fraction of the screen's height {@code primitive}'s bounds cover: its bounding sphere's diameter projected at
     * its centre's depth, as {@code CgMeshLods} levels are chosen. Above 1 with the eye inside it; unbounded, the most.
     */
    float screenHeight(CgPrimitiveComponent<?> primitive) {
        if (!primitive.localBounds(box)) return Float.MAX_VALUE;
        Matrix4d w = primitive.world();
        double cx = (box.minX() + box.maxX()) * 0.5, cy = (box.minY() + box.maxY()) * 0.5, cz = (box.minZ() + box.maxZ()) * 0.5;
        double hx = box.maxX() - cx, hy = box.maxY() - cy, hz = box.maxZ() - cz;
        double scale = Math.sqrt(Math.max(w.m00() * w.m00() + w.m01() * w.m01() + w.m02() * w.m02(),
                Math.max(w.m10() * w.m10() + w.m11() * w.m11() + w.m12() * w.m12(),
                        w.m20() * w.m20() + w.m21() * w.m21() + w.m22() * w.m22())));
        float r = (float) (Math.sqrt(hx * hx + hy * hy + hz * hz) * scale);
        float px = (float) (w.m00() * cx + w.m10() * cy + w.m20() * cz + w.m30() - originX);
        float py = (float) (w.m01() * cx + w.m11() * cy + w.m21() * cz + w.m31() - originY);
        float pz = (float) (w.m02() * cx + w.m12() * cy + w.m22() * cz + w.m32() - originZ);
        float clipW = viewProjection.m03() * px + viewProjection.m13() * py + viewProjection.m23() * pz + viewProjection.m33();
        if (clipW <= r) return Float.MAX_VALUE;
        return r * projectionScale / clipW;
    }
}
