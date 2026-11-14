package com.crystalgraphics.animation;

import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A posed skeletal mesh's bounds, from its bones: each bone's reach (the farthest bind-pose vertex weighted most to it)
 * found once a mesh, then a frame's box the posed bones' spheres of that reach. Unreal's physics-asset bounds
 * ({@code USkinnedMeshComponent::CalcBounds} over the posed bodies) with the mesh's own skin as the bodies, so a pose
 * reaching past the bind pose is never culled and no pad is guessed.
 *
 * <pre>{@code
 * CgAABB box = new CgAABB();                                       // reused
 * CgSkinBounds.posed(anim.mesh(), anim.skinning(), box);           // in the mesh's space, this frame
 * }</pre>
 *
 * <ul>
 *   <li>A mesh whose CPU copy is gone reaches as far as its bind-pose bounds from every bone: safe, and loose.</li>
 *   <li>A bone owning no vertex adds nothing; a mesh with none is its bind-pose bounds.</li>
 *   <li>Allocates only the first time it meets a mesh.</li>
 * </ul>
 */
public final class CgSkinBounds {

    /** Per mesh: each bone's reach, -1 for none, and its origin in the bind pose (x, y, z a bone). */
    private static final Map<CgSkeletalMesh, Reach> REACH = Collections.synchronizedMap(new WeakHashMap<>());

    private CgSkinBounds() {
    }

    /** {@code mesh} posed by {@code skinning} (one matrix a bone, pose times inverse bind) into {@code out}, in the mesh's space. */
    public static CgAABB posed(CgSkeletalMesh mesh, Matrix4fc[] skinning, CgAABB out) {
        Reach r = reach(mesh);
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int b = 0; b < skinning.length; b++) {
            float reach = r.reach[b];
            if (reach < 0f) continue;
            Matrix4fc m = skinning[b];
            float bx = r.origins[b * 3], by = r.origins[b * 3 + 1], bz = r.origins[b * 3 + 2];
            // The bone's posed origin: its skinning matrix takes the bind origin there
            double x = m.m00() * bx + m.m10() * by + m.m20() * bz + m.m30();
            double y = m.m01() * bx + m.m11() * by + m.m21() * bz + m.m31();
            double z = m.m02() * bx + m.m12() * by + m.m22() * bz + m.m32();
            double s = reach * largestScale(m);
            minX = Math.min(minX, x - s);
            minY = Math.min(minY, y - s);
            minZ = Math.min(minZ, z - s);
            maxX = Math.max(maxX, x + s);
            maxY = Math.max(maxY, y + s);
            maxZ = Math.max(maxZ, z + s);
        }
        if (minX > maxX) return mesh.mesh().bounds(out);
        return out.set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static float largestScale(Matrix4fc m) {
        float sx = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float sy = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float sz = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        return (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
    }

    private static Reach reach(CgSkeletalMesh mesh) {
        Reach r = REACH.get(mesh);
        if (r == null) {
            r = new Reach(mesh);
            REACH.put(mesh, r);
        }
        return r;
    }

    private static final class Reach {
        final float[] reach, origins;

        Reach(CgSkeletalMesh mesh) {
            int n = mesh.skeleton().boneCount();
            reach = new float[n];
            origins = new float[n * 3];
            Matrix4f bind = new Matrix4f();
            for (int b = 0; b < n; b++) {
                mesh.inverseBind(b, bind).invert();
                origins[b * 3] = bind.m30();
                origins[b * 3 + 1] = bind.m31();
                origins[b * 3 + 2] = bind.m32();
            }
            CgCpuSkin.Source s;
            try {
                s = CgCpuSkin.source(mesh.mesh());
            } catch (RuntimeException noCpuCopy) {
                // No vertices to read: every bone reaches the bind pose's whole box
                CgAABB box = mesh.mesh().bounds(new CgAABB());
                float all = (float) Math.sqrt(box.halfX() * box.halfX() + box.halfY() * box.halfY() + box.halfZ() * box.halfZ()) * 2f;
                Arrays.fill(reach, all);
                return;
            }
            Arrays.fill(reach, -1f);
            int vertices = s.positions.length / 3;
            for (int v = 0; v < vertices; v++) {
                int most = -1;
                float heaviest = 0f;
                for (int k = 0; k < 4; k++) {
                    float w = s.weights[v * 4 + k];
                    if (w > heaviest) {
                        heaviest = w;
                        most = s.bones[v * 4 + k];
                    }
                }
                if (most < 0 || most >= n) continue;
                float dx = s.positions[v * 3] - origins[most * 3];
                float dy = s.positions[v * 3 + 1] - origins[most * 3 + 1];
                float dz = s.positions[v * 3 + 2] - origins[most * 3 + 2];
                reach[most] = Math.max(reach[most], (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
    }
}
