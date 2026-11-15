package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.ik.CgIkMath;
import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retarget poses made for you: the target's bones turned so each mapped chain points the way its source chain does,
 * so an arms-down model follows a source that stands arms out.
 *
 * <pre>{@code
 * Map<String, Quaternionf> pose = CgRetargetPoses.alignTarget(retargeter, gokuSkeleton, playerSkeleton);
 * CgRetargeter aligned = retargeter.toBuilder().targetPose(pose).build();
 * }</pre>
 *
 * <ul>
 *   <li>Chains are aligned root first, each bone along the chain's direction a tenth of the chain further on; a chain's
 *       last tenth, and a single bone, keep their turn.</li>
 *   <li>The pelvis is never turned: the body's lean is the source's to give.</li>
 * </ul>
 */
public final class CgRetargetPoses {

    /** How far along a chain its direction at a bone is read. */
    static final float STEP = 0.1f;

    private CgRetargetPoses() {
    }

    /** {@code retargeter}'s target pose with every mapped chain aligned to its source; its own entries kept beneath. */
    public static Map<String, Quaternionf> alignTarget(CgRetargeter retargeter, CgSkeleton sourceSkeleton, CgSkeleton targetSkeleton) {
        String owner = retargeter.id();
        RetargetSkeleton src = new RetargetSkeleton(owner, sourceSkeleton, retargeter.sourcePose(),
                CgRetargetProcessor.frame(retargeter), 1f);
        RetargetSkeleton tgt = new RetargetSkeleton(owner, targetSkeleton, retargeter.targetPose(), new Quaternionf(), 1f);
        int pelvis = targetSkeleton.indexOf(retargeter.target().pelvis());

        List<RetargetChain[]> pairs = new ArrayList<>();
        for (CgRetargeter.ChainMap map : retargeter.chains()) {
            CgRig.Chain t = retargeter.target().chain(map.target());
            CgRig.Chain s = map.source() == null ? null : retargeter.source().chain(map.source());
            if (t == null || s == null) continue;
            pairs.add(new RetargetChain[] {RetargetChain.resolve(owner, t, tgt), RetargetChain.resolve(owner, s, src)});
        }
        pairs.sort(Comparator.comparingInt(p -> p[0].start()));

        Vector3f a = new Vector3f(), b = new Vector3f(), tgtDir = new Vector3f(), srcDir = new Vector3f();
        Quaternionf qa = new Quaternionf(), turn = new Quaternionf(), next = new Quaternionf();
        boolean[] changed = new boolean[tgt.count];
        for (RetargetChain[] pair : pairs) {
            RetargetChain tc = pair[0], sc = pair[1];
            for (int i = 0; i < tc.bones.length; i++) {
                int bone = tc.bones[i];
                float param = tc.params[i];
                if (bone == pelvis || param + STEP >= 1f) continue;
                tc.sample(tgt.pos, tgt.rot, param, a, qa);
                tc.sample(tgt.pos, tgt.rot, param + STEP, b, qa);
                b.sub(a, tgtDir);
                sc.sample(src.pos, src.rot, param, a, qa);
                sc.sample(src.pos, src.rot, param + STEP, b, qa);
                b.sub(a, srcDir);
                if (tgtDir.lengthSquared() < 1e-12f || srcDir.lengthSquared() < 1e-12f) continue;
                CgIkMath.findBetweenNormals(tgtDir.normalize(), srcDir.normalize(), turn);
                turn.mul(tgt.rot[bone], next);
                tgt.setGlobal(bone, tgt.pos[bone], next);
                tgt.resolveBelow(bone);
                changed[bone] = true;
            }
        }

        Map<String, Quaternionf> out = new LinkedHashMap<>(retargeter.targetPose());
        Quaternionf rest = new Quaternionf();
        for (int bone = 0; bone < tgt.count; bone++) {
            if (!changed[bone]) continue;
            // local = rest * offset
            Quaternionf offset = targetSkeleton.restRotation(bone, rest).conjugate().mul(tgt.lrot[bone], new Quaternionf()).normalize();
            out.put(targetSkeleton.name(bone), offset);
        }
        return out;
    }
}
