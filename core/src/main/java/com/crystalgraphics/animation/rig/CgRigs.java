package com.crystalgraphics.animation.rig;

import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The default rigs, found from a skeleton's bone names, so a model of a common shape needs no rig file.
 *
 * <pre>{@code
 * CgRig goku = CgRigs.detect(gokuMesh.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);     // the standard humanoid names
 * CgRig player = CgRigs.detect(playerMesh.skeleton(), CgRigAdapters.GECKOLIB);        // GeckoLib's humanoid
 * if (player == null) { ... }   // neither shape: give it a rig of its own (CgRig.builder or rigs/)
 * }</pre>
 *
 * <ul>
 *   <li>The standard humanoid: {@code pelvis}, {@code spine_01..}, {@code neck_01..}, {@code head}, {@code clavicle_l},
 *       {@code upperarm_l} to {@code hand_l}, {@code thigh_l} to {@code foot_l}, the fingers' {@code thumb_01_l} to
 *       {@code _03_l}; {@code _r} the same. Its limbs bend in its clips, so its chains name no bend.</li>
 *   <li>GeckoLib's humanoid: {@code model} (its pelvis), {@code body}, {@code head}, {@code left_arm} to
 *       {@code left_hand_locator}, {@code left_leg} to {@code left_foot_locator}. Its clips hold its limbs straight, so
 *       its knees are given a bend toward its front and its elbows toward its back; its arms and head hang from its
 *       pelvis, so they ride {@code body}; its torso pivots at its neck, so it turns about its hips.</li>
 *   <li>Goals are {@code ik_hand_l}, {@code ik_foot_l} and the right's: virtual bones where the skeleton has none
 *       ({@link CgRig#ikBones}).</li>
 * </ul>
 */
public final class CgRigs {

    private static final String[] FINGERS = {"thumb", "index", "middle", "ring", "pinky"};

    private CgRigs() {
    }

    /** The default rig {@code skeleton}'s names fit, read through {@code adapter}; null for neither shape. */
    @Nullable
    public static CgRig detect(CgSkeleton skeleton, CgRigAdapter adapter) {
        CgRig standard = standardHumanoid(skeleton, adapter);
        return standard != null ? standard : geckoHumanoid(skeleton, adapter);
    }

    /** The standard humanoid's rig, or null where {@code skeleton} lacks its body's bones. */
    @Nullable
    public static CgRig standardHumanoid(CgSkeleton skeleton, CgRigAdapter adapter) {
        for (String bone : new String[] {"pelvis", "spine_01", "head", "upperarm_l", "hand_l", "upperarm_r", "hand_r",
                "thigh_l", "foot_l", "thigh_r", "foot_r"}) {
            if (skeleton.indexOf(bone) < 0) return null;
        }
        CgRig.Builder b = CgRig.builder("crystalgraphics:standard_humanoid", adapter)
                .root(skeleton.indexOf("root") >= 0 ? "root" : skeleton.name(0)).pelvis("pelvis")
                .chain("spine", "spine_01", last(skeleton, "spine_", "spine_01"));
        if (skeleton.indexOf("neck_01") >= 0) b.chain("neck", "neck_01", last(skeleton, "neck_", "neck_01"));
        b.chain("head", "head", "head");
        for (String s : new String[] {"l", "r"}) {
            if (skeleton.indexOf("clavicle_" + s) >= 0) b.chain("clavicle_" + s, "clavicle_" + s, "clavicle_" + s);
            b.chain("arm_" + s, "upperarm_" + s, "hand_" + s, "ik_hand_" + s);
            b.chain("leg_" + s, "thigh_" + s, "foot_" + s, "ik_foot_" + s);
            for (String f : FINGERS) {
                String start = f + "_01_" + s, end = f + "_03_" + s;
                if (skeleton.indexOf(start) >= 0 && skeleton.indexOf(end) >= 0) b.chain(f + "_" + s, start, end);
            }
        }
        return b.build();
    }

    /** GeckoLib's humanoid's rig, or null where {@code skeleton} lacks its parts. */
    @Nullable
    public static CgRig geckoHumanoid(CgSkeleton skeleton, CgRigAdapter adapter) {
        for (String bone : new String[] {"model", "body", "head", "left_arm", "left_arm_2", "left_hand_locator", "right_arm",
                "right_arm_2", "right_hand_locator", "left_leg", "left_leg_2", "left_foot_locator", "right_leg",
                "right_leg_2", "right_foot_locator"}) {
            if (skeleton.indexOf(bone) < 0) return null;
        }
        // its front, and so its knees' bend, is its adapter's facing; its elbows bend the other way
        float fx = adapter.facing().x(), fz = adapter.facing().z();
        CgRig.Builder b = CgRig.builder("crystalgraphics:gecko_humanoid", adapter).root(skeleton.name(0)).pelvis("model")
                .chain("spine", "body", "body").pivot("spine", 0f, hips(skeleton), 0f)
                .chain("head", "head", "head").rides("head", "body");
        for (String[] side : new String[][] {{"l", "left"}, {"r", "right"}}) {
            b.chain("arm_" + side[0], side[1] + "_arm", side[1] + "_hand_locator", "ik_hand_" + side[0]).bend("arm_" + side[0], -fx, 0f, -fz)
                    .rides("arm_" + side[0], "body");
            b.chain("leg_" + side[0], side[1] + "_leg", side[1] + "_foot_locator", "ik_foot_" + side[0]).bend("leg_" + side[0], fx, 0f, fz);
        }
        return b.build();
    }

    /** GeckoLib's humanoid's hip height at rest: where its legs hang from. */
    private static float hips(CgSkeleton skeleton) {
        Matrix4f[] rest = new Matrix4f[skeleton.boneCount()];
        for (int i = 0; i < rest.length; i++) rest[i] = new Matrix4f();
        skeleton.restGlobal(rest);
        return (rest[skeleton.indexOf("left_leg")].m31() + rest[skeleton.indexOf("right_leg")].m31()) / 2f;
    }

    /** The highest-numbered bone {@code prefix}NN that {@code skeleton} has, from {@code first}. */
    private static String last(CgSkeleton skeleton, String prefix, String first) {
        String best = first;
        for (int i = 2; i < 10; i++) {
            String name = prefix + (i < 10 ? "0" + i : String.valueOf(i));
            if (skeleton.indexOf(name) >= 0) best = name;
        }
        return best;
    }
}
