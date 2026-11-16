package com.crystalgraphics.mc.model;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Minecraft's humanoid model (players, zombies, skeletons, every biped) as a skeleton the engine poses: its six parts
 * where the game pivots them, a hips bone they hang from, and a hand and a foot at the end of each limb. A retargeted
 * clip plays on it like on any skeleton; the game draws the entity with each part set from the pose.
 *
 * <pre>{@code
 * CgRig zombie = CgVanillaHumanoid.rig();                                   // the default rig, found by its names
 * CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(goku, gokuRig, CgVanillaHumanoid.SKELETON, zombie),
 *         goku, CgVanillaHumanoid.SKELETON);
 * CgVanillaPoses.play(entityId).play(CgRetargetBake.cached(p, punch), false);   // the entity's model takes it
 * }</pre>
 *
 * <ul>
 *   <li>Metres, y up, facing -z, its feet at the origin: 1.5 m to the neck, as the game's model is 24 pixels.</li>
 *   <li>{@link #parts} writes a pose as the game's part fields: a pivot in the model's pixels (x and y flipped, 24
 *       pixels above the feet) and three angles turned Z, then Y, then X, as every era's part turns.</li>
 * </ul>
 */
public final class CgVanillaHumanoid {

    /** The parts in the order {@link #parts} writes them. */
    public static final String[] PARTS = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
    public static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;
    /** Floats a part takes in {@link #parts}: x, y, z in pixels, then the x, y and z angles in radians. */
    public static final int STRIDE = 6;

    private static final float PIXEL = 1f / 16f, NECK = 24f * PIXEL;

    /** The skeleton; its rest is the game's model at rest, arms and legs straight down. */
    public static final CgSkeleton SKELETON = skeleton();
    /** {@link #SKELETON} as a mesh with no vertices: what an animation instance plays on; the game draws the model. */
    public static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
    }), SKELETON).build();

    private static final int[] BONES = new int[PARTS.length];
    private static final Matrix4f M = new Matrix4f();
    private static final Vector3f T = new Vector3f(), E = new Vector3f();
    private static final Quaternionf Q = new Quaternionf();

    static {
        for (int i = 0; i < PARTS.length; i++) BONES[i] = SKELETON.indexOf(PARTS[i]);
    }

    private CgVanillaHumanoid() {
    }

    private static CgSkeleton skeleton() {
        // the game's pivots, in pixels from its model's origin at the neck: head and body (0, 0), arms (±5, 2), legs
        // (±1.9, 12); its right is -x there, +x here
        CgSkeleton.Builder b = CgSkeleton.builder();
        b.bone("root", null, new Vector3f());
        b.bone("hips", "root", new Vector3f(0f, 12f * PIXEL, 0f));
        b.bone("body", "hips", new Vector3f(0f, 12f * PIXEL, 0f));
        b.bone("head", "hips", new Vector3f(0f, 12f * PIXEL, 0f));
        for (String side : new String[] {"right", "left"}) {
            float x = side.equals("right") ? 1f : -1f;
            b.bone(side + "_arm", "hips", new Vector3f(5f * PIXEL * x, 10f * PIXEL, 0f));
            b.bone(side + "_hand", side + "_arm", new Vector3f(0f, -10f * PIXEL, 0f));
            b.bone(side + "_leg", "hips", new Vector3f(1.9f * PIXEL * x, 0f, 0f));
            b.bone(side + "_foot", side + "_leg", new Vector3f(0f, -12f * PIXEL, 0f));
        }
        return b.build();
    }

    /** The default rig of {@link #SKELETON}, read through {@link CgRigAdapters#VANILLA}. */
    public static CgRig rig() {
        return CgRigs.vanillaHumanoid(SKELETON, CgRigAdapters.VANILLA);
    }

    /**
     * {@code pose}, of {@link #SKELETON}, as the game's part fields: {@link #STRIDE} floats a part in {@link #PARTS}'
     * order into {@code out}. Resolves the pose; allocates nothing. One thread at a time.
     */
    public static void parts(CgAnimPose pose, float[] out) {
        pose.resolve();
        for (int i = 0; i < PARTS.length; i++) {
            Matrix4f m = pose.component(BONES[i], M);
            m.getTranslation(T);
            m.getNormalizedRotation(Q);
            // the game's model frame is ours scaled by (-1, -1, 1): a turn about (x, y, z) is one about (-x, -y, z)
            zyx(-Q.x, -Q.y, Q.z, Q.w, E);
            int o = i * STRIDE;
            out[o] = -T.x / PIXEL;
            out[o + 1] = (NECK - T.y) / PIXEL;
            out[o + 2] = T.z / PIXEL;
            out[o + 3] = E.x;
            out[o + 4] = E.y;
            out[o + 5] = E.z;
        }
    }

    /** The angles (x, y, z) whose turn Rz * Ry * Rx is the unit quaternion (x, y, z, w). */
    private static void zyx(float x, float y, float z, float w, Vector3f out) {
        float sinY = Math.max(-1f, Math.min(1f, -2f * (x * z - w * y)));
        out.set((float) Math.atan2(2f * (y * z + w * x), 1f - 2f * (x * x + y * y)), (float) Math.asin(sinY),
                (float) Math.atan2(2f * (x * y + w * z), 1f - 2f * (y * y + z * z)));
    }
}
