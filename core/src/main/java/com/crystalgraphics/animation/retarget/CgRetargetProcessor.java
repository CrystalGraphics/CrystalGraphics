package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.ik.CgIkMath;
import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Runs a {@link CgRetargeter} on poses: a source skeleton's pose in, the target skeleton's out, through the
 * retargeter's steps in order. One per pair of skeletons; it allocates nothing once made.
 *
 * <pre>{@code
 * CgRetargetProcessor p = new CgRetargetProcessor(retargeter, gokuSkeleton, playerSkeleton);
 * clip.sample(time, gokuPose);
 * p.run(gokuPose, playerPose);          // playerPose now holds the clip on the player's bones
 * playerPose.resolve();
 * }</pre>
 *
 * <pre>{@code
 * // a step switched off while it runs, to see what it does
 * p.enabled("ik_chains", false);        // the limbs as rotations alone: hands drift from where the source's are
 * }</pre>
 *
 * <ul>
 *   <li>The steps: {@code scale_source}, {@code pelvis_motion}, {@code fk_chains}, {@code ik_chains},
 *       {@code floor_constraint}. Any other, or a chain setting none of them implements, throws here by name.</li>
 *   <li>Every target bone outside a mapped chain keeps its retarget pose's local turn, riding its parent.</li>
 *   <li>The two models are put in one frame by their adapters' facings: the source turned to face as the target does.</li>
 *   <li>Not thread-safe: one thread runs it at a time.</li>
 * </ul>
 */
public final class CgRetargetProcessor {

    /** Unreal's floor defaults, in metres: the source's foot is followed fully below 8 cm, not at all above 28. */
    static final float FLOOR_START = 0.08f, FLOOR_RANGE = 0.20f;

    private final CgRetargeter retargeter;
    private final RetargetSkeleton src, tgt;
    private final CgRetargeter.Op[] ops;
    private final boolean[] opOn;
    private final int srcPelvis, tgtPelvis;
    private final float heightRatio;
    private final int[] srcHips, tgtHips;

    /** Per target bone: its FK chain's index, or -1; and its index in that chain. */
    private final int[] fkChainOf, fkIndex;
    private final Fk[] fk;
    private final Ik[] ik;
    /** Per chain turning about a point of its own: its start bone and the point, in the model's space at rest. */
    private final int[] pivotStart;
    private final Vector3f[] pivotPoint;
    /** Per riding chain: its start bone, the bone it rides. */
    private final int[] rideStart, rideBone;
    /** Target bones copied from the source's local, by index; -1 where not. */
    private final int[] carriedFrom;

    private record Fk(CgRetargeter.ChainMap map, RetargetChain target, RetargetChain source) {
    }

    private static final class Ik {
        final CgRetargeter.ChainMap map;
        final RetargetChain target, source;
        @Nullable
        final Vector3fc bend;
        final float srcInitLen, tgtInitLen, upper, lower, heightOffset;
        final Vector3f goal = new Vector3f();
        final Quaternionf goalRot = new Quaternionf();

        Ik(CgRetargeter.ChainMap map, RetargetChain target, RetargetChain source, RetargetSkeleton src, RetargetSkeleton tgt) {
            this.map = map;
            this.target = target;
            this.source = source;
            this.bend = target.chain.bend();
            srcInitLen = src.initPos[source.end()].distance(src.initPos[source.start()]);
            tgtInitLen = tgt.initPos[target.end()].distance(tgt.initPos[target.start()]);
            int mid = target.bones[1];
            upper = tgt.initPos[mid].distance(tgt.initPos[target.start()]);
            lower = tgt.initPos[target.end()].distance(tgt.initPos[mid]);
            heightOffset = tgt.initPos[target.end()].y - src.initPos[source.end()].y;
        }
    }

    private final Vector3f va = new Vector3f(), vb = new Vector3f(), vc = new Vector3f(), vd = new Vector3f(), ve = new Vector3f();
    private final Vector3f solvedJoint = new Vector3f(), solvedEnd = new Vector3f();
    private final Vector3f s1 = new Vector3f(), s2 = new Vector3f(), s3 = new Vector3f();
    private final Quaternionf qa = new Quaternionf(), qb = new Quaternionf(), qc = new Quaternionf(), qd = new Quaternionf(),
            qe = new Quaternionf(), qf = new Quaternionf();

    public CgRetargetProcessor(CgRetargeter retargeter, CgSkeleton source, CgSkeleton target) {
        this.retargeter = retargeter;
        String owner = retargeter.id();
        ops = retargeter.ops().toArray(new CgRetargeter.Op[0]);
        opOn = new boolean[ops.length];
        float sourceScale = 1f;
        for (int i = 0; i < ops.length; i++) {
            opOn[i] = true;
            switch (ops[i].kind()) {
                case "scale_source" -> sourceScale *= ops[i].a();
                case "pelvis_motion", "fk_chains", "ik_chains", "floor_constraint" -> {
                }
                default -> throw new IllegalArgumentException(owner + ": step " + ops[i].kind() + " is not implemented");
            }
        }
        src = new RetargetSkeleton(owner, source, retargeter.sourcePose(), frame(retargeter), sourceScale);
        tgt = new RetargetSkeleton(owner, target, retargeter.targetPose(), new Quaternionf(), 1f);
        srcPelvis = bone(owner, source, retargeter.source().pelvis());
        tgtPelvis = bone(owner, target, retargeter.target().pelvis());
        srcHips = hips(owner, retargeter.source(), src);
        tgtHips = hips(owner, retargeter.target(), tgt);
        heightRatio = centre(tgt.initPos, tgtHips, va).y / centre(src.initPos, srcHips, vb).y;

        fkChainOf = new int[tgt.count];
        fkIndex = new int[tgt.count];
        Arrays.fill(fkChainOf, -1);
        List<Fk> fks = new ArrayList<>();
        List<Ik> iks = new ArrayList<>();
        for (CgRetargeter.ChainMap map : retargeter.chains()) {
            CgRig.Chain t = retargeter.target().chain(map.target());
            if (t == null) throw new IllegalArgumentException(owner + ": maps target chain " + map.target() + ", not in "
                    + retargeter.target().id());
            if (map.source() == null) continue;
            CgRig.Chain s = retargeter.source().chain(map.source());
            if (s == null) throw new IllegalArgumentException(owner + ": maps source chain " + map.source() + ", not in "
                    + retargeter.source().id());
            refuse(owner, map, t, target, retargeter.target().adapter());
            RetargetChain tc = RetargetChain.resolve(owner, t, tgt), sc = RetargetChain.resolve(owner, s, src);
            if (map.rotation() != CgRetargeter.Rotation.NONE) fks.add(new Fk(map, tc, sc));
            if (map.ik() && t.goal() != null && s.goal() != null) {
                if (tc.bones.length != 3) {
                    throw new IllegalArgumentException(owner + ": chain " + t.name() + " is solved by IK but has "
                            + tc.bones.length + " bones; only a limb of three is");
                }
                iks.add(new Ik(map, tc, sc, src, tgt));
            }
        }
        fks.sort(Comparator.comparingInt(f -> f.target.start()));
        List<int[]> rides = new ArrayList<>();
        for (Fk f : fks) {
            String r = f.target.chain.rides();
            if (r != null && f.map.translation() == CgRetargeter.Translation.NONE) rides.add(new int[] {f.target.start(), bone(owner, target, r)});
        }
        List<Fk> pivoted = new ArrayList<>();
        for (Fk f : fks) if (f.target.chain.pivot() != null && f.map.translation() == CgRetargeter.Translation.NONE) pivoted.add(f);
        pivotStart = new int[pivoted.size()];
        pivotPoint = new Vector3f[pivoted.size()];
        for (int i = 0; i < pivotStart.length; i++) {
            pivotStart[i] = pivoted.get(i).target.start();
            pivotPoint[i] = new Vector3f(pivoted.get(i).target.chain.pivot());
        }
        rideStart = new int[rides.size()];
        rideBone = new int[rides.size()];
        for (int i = 0; i < rides.size(); i++) {
            rideStart[i] = rides.get(i)[0];
            rideBone[i] = rides.get(i)[1];
        }
        fk = fks.toArray(new Fk[0]);
        ik = iks.toArray(new Ik[0]);
        for (int c = 0; c < fk.length; c++) {
            for (int i = 0; i < fk[c].target.bones.length; i++) {
                int b = fk[c].target.bones[i];
                if (fkChainOf[b] >= 0) throw new IllegalArgumentException(owner + ": bone " + target.name(b) + " is in two chains");
                fkChainOf[b] = c;
                fkIndex[b] = i;
            }
        }
        carriedFrom = new int[tgt.count];
        Arrays.fill(carriedFrom, -1);
        for (String name : retargeter.target().carried()) {
            int t = target.indexOf(name), s = source.indexOf(name);
            if (t < 0) throw new IllegalArgumentException(owner + ": carries " + name + ", not a target bone");
            if (s >= 0) carriedFrom[t] = s;
        }
    }

    private static void refuse(String owner, CgRetargeter.ChainMap map, CgRig.Chain t, CgSkeleton target, CgRigAdapter adapter) {
        switch (map.rotation()) {
            case MATCH_CHAIN, MATCH_SCALED_CHAIN, COPY_LOCAL ->
                    throw new IllegalArgumentException(owner + ": chain " + map.target() + "'s rotation " + map.rotation() + " is not implemented");
            default -> {
            }
        }
        switch (map.translation()) {
            case STRETCH_UNIFORM, STRETCH_NON_UNIFORM, ORIENT_AND_SCALE ->
                    throw new IllegalArgumentException(owner + ": chain " + map.target() + "'s translation " + map.translation() + " is not implemented");
            case NONE -> {
            }
            default -> {
                int start = target.indexOf(t.start());
                for (int b = target.indexOf(t.end()); b >= 0; b = target.parent(b)) {
                    if (adapter.rotationOnly(target, b)) {
                        throw new IllegalArgumentException(owner + ": chain " + map.target() + " moves " + target.name(b)
                                + ", which " + adapter.name() + " only rotates");
                    }
                    if (b == start) break;
                }
            }
        }
    }

    /** The turn putting the source model in the target's frame: its facing turned about +y onto the target's. */
    static Quaternionf frame(CgRetargeter r) {
        Vector3fc fs = r.source().adapter().facing(), ft = r.target().adapter().facing();
        float as = (float) Math.atan2(fs.x(), fs.z()), at = (float) Math.atan2(ft.x(), ft.z());
        return new Quaternionf().rotateY(at - as);
    }

    private static int bone(String owner, CgSkeleton s, String name) {
        int b = s.indexOf(name);
        if (b < 0) throw new IllegalArgumentException(owner + ": names bone " + name + ", not in its skeleton");
        return b;
    }

    /**
     * The hips: the starts of the legs, the chains with goals that reach down to the floor (ending below half their
     * start's height); else the pelvis alone. Not "below the pelvis": GeckoLib's humanoid's pelvis is at its neck.
     */
    private static int[] hips(String owner, CgRig rig, RetargetSkeleton side) {
        int pelvis = bone(owner, side.skeleton, rig.pelvis());
        List<Integer> out = new ArrayList<>();
        for (CgRig.Chain c : rig.chains()) {
            if (c.goal() == null) continue;
            int start = side.skeleton.indexOf(c.start()), end = side.skeleton.indexOf(c.end());
            if (start >= 0 && end >= 0 && side.initPos[end].y < side.initPos[start].y * 0.5f) out.add(start);
        }
        if (out.isEmpty()) out.add(pelvis);
        int[] hips = new int[out.size()];
        for (int i = 0; i < hips.length; i++) hips[i] = out.get(i);
        if (centre(side.initPos, hips, new Vector3f()).y <= 1e-4f) {
            throw new IllegalArgumentException(owner + ": " + rig.id() + "'s hips are not above its floor");
        }
        return hips;
    }

    private static Vector3f centre(Vector3f[] pos, int[] bones, Vector3f out) {
        out.zero();
        for (int b : bones) out.add(pos[b]);
        return out.div(bones.length);
    }

    public CgRetargeter retargeter() {
        return retargeter;
    }

    public CgSkeleton sourceSkeleton() {
        return src.skeleton;
    }

    public CgSkeleton targetSkeleton() {
        return tgt.skeleton;
    }

    /** Target hip height over the source's: how much a source move is scaled. */
    public float heightRatio() {
        return heightRatio;
    }

    /** Switches every step of {@code kind} on or off; off, it is skipped. */
    public void enabled(String kind, boolean on) {
        for (int i = 0; i < ops.length; i++) if (ops[i].kind().equals(kind)) opOn[i] = on;
    }

    public boolean enabled(String kind) {
        for (int i = 0; i < ops.length; i++) if (ops[i].kind().equals(kind)) return opOn[i];
        return false;
    }

    /** Retargets {@code source} into {@code target}; {@code target}'s virtual bones derived. Neither is resolved. */
    public void run(CgAnimPose source, CgAnimPose target) {
        src.load(source);
        src.resolve();
        tgt.resetToPose();
        tgt.resolve();
        boolean solve = false;
        for (int i = 0; i < ops.length; i++) {
            if (!opOn[i]) continue;
            CgRetargeter.Op op = ops[i];
            switch (op.kind()) {
                case "pelvis_motion" -> pelvis(op);
                case "fk_chains" -> fkChains();
                case "ik_chains" -> {
                    ikGoals();
                    solve = true;
                }
                case "floor_constraint" -> {
                    if (solve) floor(op);
                }
                default -> {
                }
            }
        }
        if (solve) {
            for (Ik c : ik) solve(c);
        }
        for (int b = 0; b < tgt.count; b++) {
            Vector3f t = tgt.lpos[b];
            Quaternionf r = tgt.lrot[b];
            tgt.skeleton.restScale(b, va);
            target.set(b, t.x, t.y, t.z, r.x, r.y, r.z, r.w, va.x, va.y, va.z);
        }
        target.deriveVirtualBones(null);
    }

    private void pelvis(CgRetargeter.Op op) {
        float scaleH = op.a(), scaleV = op.b(), tAlpha = op.c(), rAlpha = op.d();
        // the source's turn since its retarget pose, put on the target's
        src.initRot[srcPelvis].conjugate(qa);
        src.rot[srcPelvis].mul(qa, qb).mul(tgt.initRot[tgtPelvis]);
        tgt.initRot[tgtPelvis].slerp(qb, rAlpha, qc);
        // the hips' move from the retarget pose, scaled by the height ratio; the pelvis turns about the hips, wherever
        // its own pivot is (GeckoLib's humanoid pivots at its neck)
        centre(src.pos, srcHips, va).sub(centre(src.initPos, srcHips, vb)).mul(heightRatio);
        va.mul(scaleH, scaleV, scaleH).mul(tAlpha);
        Vector3f hipsInit = centre(tgt.initPos, tgtHips, vb);
        tgt.initRot[tgtPelvis].conjugate(qa);
        qc.mul(qa, qa);
        tgt.initPos[tgtPelvis].sub(hipsInit, vd);
        qa.transform(vd).add(hipsInit).add(va);
        tgt.setGlobal(tgtPelvis, vd, qc);
        tgt.resolveBelow(tgtPelvis);
    }

    /** Every target bone in bone order: chain bones from their source chains, the rest riding their parents. */
    private void fkChains() {
        for (int b = 0; b < tgt.count; b++) {
            int carried = carriedFrom[b];
            if (carried >= 0) {
                tgt.lrot[b].set(src.lrot[carried]);
                tgt.lpos[b].set(src.lpos[carried]).mul(heightRatio);
            }
            int c = fkChainOf[b];
            if (c < 0 || b == tgtPelvis) {
                if (b != tgtPelvis) tgt.global(b);
                continue;
            }
            Fk f = fk[c];
            int i = fkIndex[b];
            // what the bone would be in its retarget pose under its parent as moved
            tgt.global(b);
            Vector3f basePos = vd.set(tgt.pos[b]);
            Quaternionf baseRot = qd.set(tgt.rot[b]);
            int s = sourceIndex(f, i);
            if (s < 0 && f.target.bones.length == 1 && f.source.bones.length > 1) {
                vc.set(src.pos[f.source.end()]);
                wholeChain(f.source, qc);
            } else if (s >= 0) {
                vc.set(src.pos[f.source.bones[s]]);
                qa.set(src.rot[f.source.bones[s]]);
                qb.set(src.initRot[f.source.bones[s]]);
            } else {
                float param = f.target.params[i];
                f.source.sample(src.pos, src.rot, param, vc, qa);
                f.source.sample(src.initPos, src.initRot, param, ve, qb);
            }
            if (s >= 0 || f.target.bones.length > 1 || f.source.bones.length == 1) {
                // (current * init^-1) * targetInit, all global
                qb.conjugate();
                qa.mul(qb, qc);
            }
            qc.mul(tgt.initRot[b]).normalize();
            baseRot.slerp(qc, f.map.rotationAlpha(), qc);
            switch (f.map.translation()) {
                case GLOBALLY_SCALED -> vc.mul(heightRatio);
                case ABSOLUTE -> {
                }
                default -> vc.set(basePos);
            }
            basePos.lerp(vc, f.map.translationAlpha(), vc);
            tgt.setGlobal(b, vc, qc);
        }
        // a chain pivoted elsewhere turns about its own point, carried by its parent
        for (int i = 0; i < pivotStart.length; i++) {
            int start = pivotStart[i], parent = tgt.skeleton.parent(start);
            Vector3f point = pivotPoint[i];
            if (parent < 0) {
                vc.set(point);
            } else {
                tgt.initRot[parent].conjugate(qa);
                tgt.rot[parent].mul(qa, qa).transform(point.sub(tgt.initPos[parent], vc)).add(tgt.pos[parent]);
            }
            tgt.initRot[start].conjugate(qa);
            tgt.rot[start].mul(qa, qa).transform(tgt.initPos[start].sub(point, va)).add(vc);
            tgt.setGlobal(start, va, tgt.rot[start]);
            tgt.resolveBelow(start);
        }
        // a chain hung from elsewhere is carried by the bone it rides, as if it were its child
        for (int i = 0; i < rideStart.length; i++) {
            int start = rideStart[i], r = rideBone[i];
            tgt.initRot[r].conjugate(qa);
            tgt.rot[r].mul(qa, qa);
            qa.transform(tgt.initPos[start].sub(tgt.initPos[r], va)).add(tgt.pos[r]);
            tgt.setGlobal(start, va, tgt.rot[start]);
            tgt.resolveBelow(start);
        }
    }

    /**
     * The turn of a whole source chain, for one target bone standing in for all of it (a rigid torso for a spine):
     * the swing taking the chain's start-to-end line from its retarget pose to now, then its end's twist about that
     * line. Interpolation would give it the end's turn alone, which a curled spine overstates.
     */
    private void wholeChain(RetargetChain chain, Quaternionf out) {
        int start = chain.start(), end = chain.end();
        src.initPos[end].sub(src.initPos[start], va);
        src.pos[end].sub(src.pos[start], vb);
        if (va.lengthSquared() < 1e-12f || vb.lengthSquared() < 1e-12f) {
            src.initRot[end].conjugate(out);
            src.rot[end].mul(out, out);
            return;
        }
        va.normalize();
        CgIkMath.findBetweenNormals(va, vb.normalize(), qe);
        // what the end turned beyond that swing, kept only about the line
        src.initRot[end].conjugate(qf);
        src.rot[end].mul(qf, qf);
        qe.conjugate(out).mul(qf, qf);
        CgIkMath.swingTwist(qf, va, out, qf);
        qe.mul(qf, out);
    }

    /** For one-to-one modes, the source bone a target bone takes; -1 to sample by param. */
    private static int sourceIndex(Fk f, int i) {
        int n = f.source.bones.length, m = f.target.bones.length;
        return switch (f.map.rotation()) {
            case ONE_TO_ONE -> Math.min(i, n - 1);
            case ONE_TO_ONE_REVERSED -> Math.max(0, n - 1 - (m - 1 - i));
            default -> -1;
        };
    }

    private void ikGoals() {
        for (Ik c : ik) {
            Vector3f srcStart = src.pos[c.source.start()], srcEnd = src.pos[c.source.end()];
            float srcLen = srcEnd.distance(srcStart);
            srcEnd.sub(srcStart, va);
            if (srcLen > 1e-6f) va.div(srcLen);
            float reach = c.srcInitLen > 1e-6f ? srcLen / c.srcInitLen * c.tgtInitLen : c.tgtInitLen;
            c.goal.set(tgt.pos[c.target.start()]).fma(reach, va);
            c.goal.lerp(srcEnd, c.map.blendToSource());
            Vector3f start = tgt.pos[c.target.start()];
            c.goal.sub(start, vb).mul(c.map.extension()).add(start, c.goal);
            c.goal.add(c.map.staticOffset());
            // (current * init^-1) * targetInit
            src.initRot[c.source.end()].conjugate(qa);
            src.rot[c.source.end()].mul(qa, c.goalRot).mul(tgt.initRot[c.target.end()]).normalize();
        }
    }

    private void floor(CgRetargeter.Op op) {
        float weight = op.a();
        for (Ik c : ik) {
            float h = Math.abs(src.pos[c.source.end()].y);
            if (h >= FLOOR_START + FLOOR_RANGE) continue;
            float falloff = h < FLOOR_START ? 1f : 1f - (h - FLOOR_START) / FLOOR_RANGE;
            c.goal.y += (h + c.heightOffset - c.goal.y) * falloff * weight;
        }
    }

    private void solve(Ik c) {
        int start = c.target.start(), mid = c.target.bones[1], end = c.target.end();
        Vector3f s = tgt.pos[start], j = tgt.pos[mid];
        // the knee as FK left it; a limb left straight bends the rig's way, turned with the pelvis
        c.goal.sub(s, va);
        j.sub(s, vb);
        if (va.lengthSquared() > 1e-10f) vb.fma(-vb.dot(va) / va.lengthSquared(), va);
        if (vb.length() < 0.01f * c.upper && c.bend != null) {
            tgt.initRot[tgtPelvis].conjugate(qa);
            tgt.rot[tgtPelvis].mul(qa, qa).transform(c.bend, vb);
        }
        vc.set(j).add(vb);
        CgIkMath.twoBone(s, j, vc, c.goal, c.upper, c.lower, false, 1f, 1f, solvedJoint, solvedEnd, s1, s2, s3);
        // turn the upper bone onto the solved joint, then the lower onto the end
        aim(start, j, solvedJoint);
        tgt.resolveBelow(start);
        aim(mid, tgt.pos[end], solvedEnd);
        tgt.resolveBelow(mid);
        tgt.setGlobal(end, tgt.pos[end], c.goalRot);
        tgt.resolveBelow(end);
    }

    /** Turns bone {@code b} so the point {@code from} it carries lands on {@code to}. */
    private void aim(int b, Vector3fc from, Vector3fc to) {
        Vector3f p = tgt.pos[b];
        from.sub(p, va);
        to.sub(p, vb);
        if (va.lengthSquared() < 1e-12f || vb.lengthSquared() < 1e-12f) return;
        CgIkMath.findBetweenNormals(va.normalize(), vb.normalize(), qa);
        qa.mul(tgt.rot[b], qb);
        tgt.setGlobal(b, p, qb);
    }

    /** Target chain {@code chain}'s IK goal as of the last run, in the target model's space; false where it has none. */
    public boolean goal(String chain, Vector3f out) {
        for (Ik c : ik) {
            if (c.target.chain.name().equals(chain)) {
                out.set(c.goal);
                return true;
            }
        }
        return false;
    }

    /** Source bone {@code bone} as of the last run, in the target model's space and scale. */
    public Vector3f sourcePosition(int bone, Vector3f out) {
        return out.set(src.pos[bone]);
    }
}
