package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRootMotionParams;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * Feet fitted to the speed the character really moves at: each IK foot's stride is lengthened or shortened along the
 * direction of travel, the pelvis pulled toward the feet so the legs still reach, and each thigh turned toward its
 * foot. A leg IK after it bends the legs onto the IK feet. So a run authored at 6 m/s plays at 4 or 9 without its feet
 * sliding.
 *
 * <pre>{@code
 * // GRAPH: the stride scaled from the clips' own root motion to the speed the game moves at
 * CgAnimNode warped = CgStrideWarpingNode.of(locomotion, "pelvis", "ik_foot_root")
 *         .foot("ik_foot_l", "foot_l", "thigh_l").foot("ik_foot_r", "foot_r", "thigh_r")
 *         .locomotionSpeed(SPEED)
 *         .scaleClamp(0.5f, 2f);
 * CgAnimNode legs = CgLegIkNode.of(warped).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
 * anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);    // GRAPH reads the players' root motion
 * }</pre>
 *
 * <pre>{@code
 * // MANUAL: the scale given outright, along the skeleton's +z
 * CgStrideWarpingNode.of(locomotion, "pelvis", "ik_foot_root").foot(...).foot(...).manual(STRIDE_SCALE)
 * }</pre>
 *
 * <ul>
 *   <li>{@code GRAPH} needs root motion from everything: without any this frame it leaves the pose alone, and while
 *       the clips move slower than {@link #minRootMotionSpeed} it scales by 1. Its clips need root motion: an in-place
 *       clip gets it from its feet ({@code CgClipModifiers.rootMotionFromFeet}).</li>
 *   <li>It replaces the frame's root motion with the warped one: the character then moves at the locomotion speed.</li>
 *   <li>The IK feet, pelvis and thighs are written; the knees are a leg IK's to bend.</li>
 *   <li>Metres: the pelvis moves at most {@code pelvisMaxDistance}, 10 cm by default.</li>
 * </ul>
 */
public final class CgStrideWarpingNode extends CgSkeletalControlNode {

    /** One foot: its IK foot, FK foot and thigh. */
    public record Foot(String ikFoot, String fkFoot, String thigh) {
        public Foot {
            Objects.requireNonNull(ikFoot, "ikFoot");
            Objects.requireNonNull(fkFoot, "fkFoot");
            Objects.requireNonNull(thigh, "thigh");
        }
    }

    /** Reads {@code kind: stride_warping}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgStrideWarpingNode n = of(source(c, at, m), c.string(at, m, "pelvis"), c.string(at, m, "ik_foot_root")).alpha(alpha(c, at, m));
        if (!(m.get("feet") instanceof List<?> feet) || feet.isEmpty()) throw new CgAssetException(at + ".feet: none");
        for (int i = 0; i < feet.size(); i++) {
            String a = at + ".feet[" + i + "]";
            if (!(feet.get(i) instanceof Map<?, ?> f)) throw new CgAssetException(a + ": a map");
            n = n.foot(c.string(a, f, "ik_foot_bone"), c.string(a, f, "fk_foot_bone"), c.string(a, f, "thigh_bone"));
        }
        CgWarpingMode mode = choice(c, at, m, "mode", CgWarpingMode.class, CgWarpingMode.GRAPH);
        n = mode == CgWarpingMode.MANUAL ? n.manual(CgWarpInput.read(c, at, m, "stride_scale", 1f))
                : n.locomotionSpeed(CgWarpInput.read(c, at, m, "locomotion_speed", 0f));
        float[] dir = vector(c, at, m, "stride_direction", 0f, 0f, 1f), clamp = numbers(c, at, m, "scale_clamp"),
                interp = numbers(c, at, m, "scale_interp");
        n = n.strideDirection(dir[0], dir[1], dir[2]).minRootMotionSpeed(number(c, at, m, "min_root_motion_speed", 0.1f))
                .pelvis(number(c, at, m, "pelvis_alpha", 0.5f), number(c, at, m, "pelvis_max_distance", 0.1f),
                        number(c, at, m, "pelvis_stiffness", 1f), number(c, at, m, "pelvis_damping", 1f))
                .compensateThighs(!Boolean.FALSE.equals(m.get("compensate_thighs")), !Boolean.FALSE.equals(m.get("clamp_to_fk")))
                .orientToFloor(!Boolean.FALSE.equals(m.get("orient_to_floor")));
        if (clamp != null) n = n.scaleClamp(clamp[0], clamp[1]);
        if (interp != null) n = n.scaleInterp(interp[0], interp[1]);
        return n;
    };

    private final String pelvisBone, ikFootRootBone;
    private final List<Foot> feet;
    private final CgWarpingMode mode;
    private final CgWarpInput strideScale, locomotionSpeed;
    private final float dirX, dirY, dirZ, minRootMotionSpeed;
    private final float clampMin, clampMax, interpUp, interpDown;
    private final float pelvisAlpha, pelvisMaxDistance, pelvisStiffness, pelvisDamping;
    private final boolean compensateThighs, clampToFk, orientToFloor;

    private CgStrideWarpingNode(CgAnimNode source, CgControlAlpha alpha, String pelvisBone, String ikFootRootBone, List<Foot> feet,
                                CgWarpingMode mode, CgWarpInput strideScale, CgWarpInput locomotionSpeed, float dirX, float dirY,
                                float dirZ, float minRootMotionSpeed, float clampMin, float clampMax, float interpUp, float interpDown,
                                float pelvisAlpha, float pelvisMaxDistance, float pelvisStiffness, float pelvisDamping,
                                boolean compensateThighs, boolean clampToFk, boolean orientToFloor) {
        super(source, alpha);
        this.pelvisBone = Objects.requireNonNull(pelvisBone, "pelvisBone");
        this.ikFootRootBone = Objects.requireNonNull(ikFootRootBone, "ikFootRootBone");
        this.feet = List.copyOf(feet);
        this.mode = mode;
        this.strideScale = strideScale;
        this.locomotionSpeed = locomotionSpeed;
        this.dirX = dirX;
        this.dirY = dirY;
        this.dirZ = dirZ;
        this.minRootMotionSpeed = minRootMotionSpeed;
        this.clampMin = clampMin;
        this.clampMax = clampMax;
        this.interpUp = interpUp;
        this.interpDown = interpDown;
        this.pelvisAlpha = pelvisAlpha;
        this.pelvisMaxDistance = pelvisMaxDistance;
        this.pelvisStiffness = pelvisStiffness;
        this.pelvisDamping = pelvisDamping;
        this.compensateThighs = compensateThighs;
        this.clampToFk = clampToFk;
        this.orientToFloor = orientToFloor;
    }

    /** Stride warping over {@code source}, {@code GRAPH} at speed 0 until {@link #locomotionSpeed}. */
    public static CgStrideWarpingNode of(CgAnimNode source, String pelvisBone, String ikFootRootBone) {
        return new CgStrideWarpingNode(source, CgControlAlpha.FULL, pelvisBone, ikFootRootBone, List.of(), CgWarpingMode.GRAPH,
                CgWarpInput.of(1f), CgWarpInput.of(0f), 0f, 0f, 1f, 0.1f, Float.NaN, Float.NaN, 0f, 0f, 0.5f, 0.1f, 1f, 1f,
                true, true, true);
    }

    private CgStrideWarpingNode with(CgControlAlpha alpha, List<Foot> feet, CgWarpingMode mode, CgWarpInput strideScale,
                                     CgWarpInput locomotionSpeed) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, clampMin, clampMax, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance,
                pelvisStiffness, pelvisDamping, compensateThighs, clampToFk, orientToFloor);
    }

    public CgStrideWarpingNode alpha(CgControlAlpha alpha) {
        return with(alpha, feet, mode, strideScale, locomotionSpeed);
    }

    /** A foot: its IK foot (what is warped), its FK foot and its thigh. */
    public CgStrideWarpingNode foot(String ikFoot, String fkFoot, String thigh) {
        List<Foot> next = new ArrayList<>(feet);
        next.add(new Foot(ikFoot, fkFoot, thigh));
        return with(alpha, next, mode, strideScale, locomotionSpeed);
    }

    /** {@code GRAPH}: the scale is this speed, metres a second, over the clips' root motion speed. */
    public CgStrideWarpingNode locomotionSpeed(CgAnimVariable<Float> speed) {
        return locomotionSpeed(CgWarpInput.of(speed));
    }

    public CgStrideWarpingNode locomotionSpeed(CgWarpInput speed) {
        return with(alpha, feet, CgWarpingMode.GRAPH, strideScale, speed);
    }

    /** {@code MANUAL}: the scale given, along {@link #strideDirection}. */
    public CgStrideWarpingNode manual(CgAnimVariable<Float> scale) {
        return manual(CgWarpInput.of(scale));
    }

    public CgStrideWarpingNode manual(CgWarpInput scale) {
        return with(alpha, feet, CgWarpingMode.MANUAL, scale, locomotionSpeed);
    }

    /** {@code MANUAL}'s direction of travel, in the skeleton's space: +z by default. */
    public CgStrideWarpingNode strideDirection(float x, float y, float z) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, x, y, z,
                minRootMotionSpeed, clampMin, clampMax, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance, pelvisStiffness,
                pelvisDamping, compensateThighs, clampToFk, orientToFloor);
    }

    /** {@code GRAPH}: below this root motion speed (metres a second; 10 cm/s by default) the scale is 1. */
    public CgStrideWarpingNode minRootMotionSpeed(float metresPerSecond) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, metresPerSecond, clampMin, clampMax, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance,
                pelvisStiffness, pelvisDamping, compensateThighs, clampToFk, orientToFloor);
    }

    /** The scale held within [{@code min}, {@code max}]: a play rate covers the rest. */
    public CgStrideWarpingNode scaleClamp(float min, float max) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, min, max, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance, pelvisStiffness,
                pelvisDamping, compensateThighs, clampToFk, orientToFloor);
    }

    /** The scale eased toward its target at these speeds (per second), rising and falling; 0 for at once. */
    public CgStrideWarpingNode scaleInterp(float increasing, float decreasing) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, clampMin, clampMax, increasing, decreasing, pelvisAlpha, pelvisMaxDistance,
                pelvisStiffness, pelvisDamping, compensateThighs, clampToFk, orientToFloor);
    }

    /**
     * The pelvis pull-down: how much of it is kept (0 to 1), how far it may move (metres), and its spring's stiffness
     * and damping ratio. By default 0.5, 0.1, 1, 1.
     */
    public CgStrideWarpingNode pelvis(float alpha, float maxDistance, float stiffness, float damping) {
        return new CgStrideWarpingNode(source, this.alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, clampMin, clampMax, interpUp, interpDown, alpha, maxDistance, stiffness, damping,
                compensateThighs, clampToFk, orientToFloor);
    }

    /** Whether each thigh turns toward its warped foot, and the foot is then kept within the leg's FK length. */
    public CgStrideWarpingNode compensateThighs(boolean thighs, boolean clampToFk) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, clampMin, clampMax, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance,
                pelvisStiffness, pelvisDamping, thighs, clampToFk, orientToFloor);
    }

    /** Whether the direction is laid along the floor (the skeleton's +y up). */
    public CgStrideWarpingNode orientToFloor(boolean on) {
        return new CgStrideWarpingNode(source, alpha, pelvisBone, ikFootRootBone, feet, mode, strideScale, locomotionSpeed, dirX,
                dirY, dirZ, minRootMotionSpeed, clampMin, clampMax, interpUp, interpDown, pelvisAlpha, pelvisMaxDistance,
                pelvisStiffness, pelvisDamping, compensateThighs, clampToFk, on);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        strideScale.reads(each);
        locomotionSpeed.reads(each);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        if (feet.isEmpty()) throw new IllegalArgumentException(this + ": no feet");
        int n = feet.size();
        int pelvis = bone(g.skeleton(), pelvisBone, this);
        bone(g.skeleton(), ikFootRootBone, this);
        int[] ik = new int[n], fk = new int[n], thigh = new int[n];
        for (int i = 0; i < n; i++) {
            ik[i] = bone(g.skeleton(), feet.get(i).ikFoot, this);
            fk[i] = bone(g.skeleton(), feet.get(i).fkFoot, this);
            thigh[i] = bone(g.skeleton(), feet.get(i).thigh, this);
        }
        return new Control(1 + 2 * n) {
            final Vector3f dir = new Vector3f(0f, 0f, 1f), lastDir = new Vector3f(0f, 0f, 1f), t = new Vector3f(), a = new Vector3f(),
                    b = new Vector3f(), origin = new Vector3f(), pelvisAt = new Vector3f(), offset = new Vector3f(), initial = new Vector3f();
            final Vector3f[] ikAt = new Vector3f[n];
            final float[] fkDistances = new float[n];
            final Matrix4f m = new Matrix4f();
            final Quaternionf q = new Quaternionf(), q2 = new Quaternionf();
            final Rk4Spring spring = new Rk4Spring();
            float dt, scaleNow = 1f;
            boolean warm;

            {
                for (int i = 0; i < n; i++) ikAt[i] = new Vector3f();
            }

            @Override
            protected void initialize(CgAnimGraphInstance g) {
                spring.reset();
                scaleNow = 1f;
                warm = false;
                lastDir.set(0f, 0f, 1f);
            }

            @Override
            protected void update(CgAnimGraphInstance g, float dt) {
                this.dt = dt;
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                dir.set(dirX, dirY, dirZ);
                float scale = strideScale.get(g);
                CgRootMotionParams rootMotion = g.rootMotion();
                boolean graph = mode == CgWarpingMode.GRAPH;
                if (graph) {
                    if (!rootMotion.hasRootMotion()) return 0;
                    rootMotion.get(m).getTranslation(t);
                    float length = t.length();
                    if (length > 1e-8f) dir.set(t).div(length);
                    else dir.set(lastDir);
                    float speed = dt > 1e-8f ? length / dt : 0f;
                    scale = speed <= minRootMotionSpeed || speed < 1e-8f ? 1f : locomotionSpeed.get(g) / speed;
                }
                if (orientToFloor) {
                    // the floor's normal is the skeleton's +y: the direction laid along it
                    dir.y = 0f;
                    if (dir.lengthSquared() > 1e-12f) dir.normalize();
                    else dir.set(lastDir);
                }
                lastDir.set(dir);
                if (!Float.isNaN(clampMin)) scale = Math.max(clampMin, Math.min(clampMax, scale));
                if (warm && (interpUp > 0f || interpDown > 0f)) {
                    float speed = scale > scaleNow ? interpUp : interpDown;
                    scale = interpTo(scaleNow, scale, dt, speed);
                }
                warm = true;
                if (!Float.isFinite(scale)) scale = 1f;
                scaleNow = scale;
                if (graph) {
                    // the character carried at the warped stride: the frame's root motion scaled
                    m.setTranslation(t.x * scale, t.y * scale, t.z * scale);
                    rootMotion.set(m);
                }

                // each IK foot scaled along the direction about the point under its thigh
                for (int i = 0; i < n; i++) {
                    Vector3f foot = pose.component(ik[i], m).getTranslation(ikAt[i]);
                    pose.component(thigh[i], m).getTranslation(a);
                    // the vertical through the thigh meets the floor plane through the foot
                    origin.set(a.x, foot.y, a.z);
                    float along = b.set(foot).sub(origin).dot(dir);
                    origin.set(foot).fma(-along, dir);
                    foot.sub(origin).mul(scale).add(origin);
                }

                pose.component(pelvis, m).getTranslation(initial);
                for (int i = 0; i < n; i++) fkDistances[i] = pose.component(fk[i], m).getTranslation(a).distance(initial);
                pullDown(initial, pelvisAt);
                offset.set(pelvisAt).sub(initial);

                int count = 0;
                bones[count] = pelvis;
                out[count++].set(pose.component(pelvis)).setTranslation(pelvisAt);
                if (compensateThighs) {
                    for (int i = 0; i < n; i++) {
                        Matrix4f th = pose.component(thigh[i], m);
                        th.getTranslation(a);
                        Vector3f fkFoot = pose.component(fk[i]).getTranslation(b);
                        float fkLength = fkFoot.distance(a);
                        Vector3f from = fkFoot.sub(a).normalize();
                        a.add(offset);
                        Vector3f to = t.set(ikAt[i]).sub(a).normalize();
                        q.rotationTo(from, to);
                        th.getNormalizedRotation(q2);
                        q.mul(q2);
                        bones[count] = thigh[i];
                        out[count++].translationRotate(a.x, a.y, a.z, q).scale(th.getScale(origin));
                        if (clampToFk && ikAt[i].distance(a) > fkLength) ikAt[i].set(a).fma(fkLength, to);
                    }
                }
                for (int i = 0; i < n; i++) {
                    bones[count] = ik[i];
                    out[count++].set(pose.component(ik[i])).setTranslation(ikAt[i]);
                }
                sort(bones, out, count);
                return count;
            }

            /** The pelvis pulled toward the feet: each foot's ideal pelvis at its FK distance, averaged, sprung. */
            private void pullDown(Vector3f initial, Vector3f result) {
                result.set(initial);
                float weight = 1f / n;
                offset.zero();
                for (int iter = 0; iter < 3; iter++) {
                    b.set(result);
                    result.zero();
                    for (int i = 0; i < n; i++) {
                        a.set(b).sub(ikAt[i]);
                        float l = a.length();
                        if (l > 1e-8f) a.div(l);
                        else a.zero();
                        result.fma(weight, a.mul(fkDistances[i]).add(ikAt[i]));
                    }
                    a.set(result).sub(initial);
                    float moved = a.distance(offset);
                    offset.set(a);
                    if (moved <= 0.0001f) break;
                }
                spring.update(offset, pelvisStiffness, pelvisDamping, dt);
                result.set(initial).fma(pelvisAlpha, spring.position);
                if (result.distanceSquared(initial) >= pelvisMaxDistance * pelvisMaxDistance) {
                    a.set(result).sub(initial).normalize();
                    result.set(initial).fma(pelvisMaxDistance, a);
                }
            }
        };
    }

    /** Insertion sort of the written bones, ascending, their transforms beside them. */
    static void sort(int[] bones, Matrix4f[] out, int count) {
        for (int i = 1; i < count; i++) {
            for (int j = i; j > 0 && bones[j - 1] > bones[j]; j--) {
                int b = bones[j];
                bones[j] = bones[j - 1];
                bones[j - 1] = b;
                Matrix4f m = out[j];
                out[j] = out[j - 1];
                out[j - 1] = m;
            }
        }
    }

    /** {@code FMath::FInterpTo}. */
    static float interpTo(float current, float target, float dt, float speed) {
        if (speed <= 0f) return target;
        float d = target - current;
        if (d * d < 1e-8f) return target;
        return current + d * Math.max(0f, Math.min(1f, dt * speed));
    }

    /** A vector spring integrated by RK4: fixed 60 Hz steps, at most 0.1 s a frame. */
    static final class Rk4Spring {
        private static final float STEP = 1f / 60f;
        final Vector3f position = new Vector3f(), velocity = new Vector3f();
        private final Vector3f av = new Vector3f(), aa = new Vector3f(), bv = new Vector3f(), ba = new Vector3f(), cv = new Vector3f(),
                ca = new Vector3f(), dv = new Vector3f(), da = new Vector3f(), p = new Vector3f(), v = new Vector3f();
        private float remaining;
        private boolean moving;

        void reset() {
            position.zero();
            velocity.zero();
            remaining = 0f;
            moving = false;
        }

        void update(Vector3f target, float stiffness, float dampingRatio, float dt) {
            if (dt <= 0f) return;
            moving |= position.distance(target) > 0.001f / 100f;
            if (!moving) return;
            float damping = dampingRatio * 2f * (float) Math.sqrt(stiffness);
            remaining = Math.min(remaining + dt, 0.1f);
            while (remaining >= STEP) {
                derive(target, stiffness, damping, null, null, 0f, av, aa);
                derive(target, stiffness, damping, av, aa, STEP * 0.5f, bv, ba);
                derive(target, stiffness, damping, bv, ba, STEP * 0.5f, cv, ca);
                derive(target, stiffness, damping, cv, ca, STEP, dv, da);
                bv.add(cv).mul(2f).add(av).add(dv);
                ba.add(ca).mul(2f).add(aa).add(da);
                position.fma(STEP / 6f, bv);
                velocity.fma(STEP / 6f, ba);
                remaining -= STEP;
            }
            moving = !(position.distance(target) <= 0.001f / 100f && velocity.length() <= 0.01f / 100f);
        }

        private void derive(Vector3f target, float k, float c, Vector3f dvIn, Vector3f daIn, float h, Vector3f outV, Vector3f outA) {
            p.set(position);
            v.set(velocity);
            if (dvIn != null) {
                p.fma(h, dvIn);
                v.fma(h, daIn);
            }
            outV.set(v);
            outA.set(target).sub(p).mul(k).fma(-c, v);
        }
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("stride_warping");
        writeBase(out);
        out.put("pelvis", pelvisBone).put("ik_foot_root", ikFootRootBone);
        List<Object> list = new ArrayList<>(feet.size());
        for (Foot f : feet) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ik_foot_bone", f.ikFoot);
            e.put("fk_foot_bone", f.fkFoot);
            e.put("thigh_bone", f.thigh);
            list.add(e);
        }
        out.put("feet", list);
        if (mode == CgWarpingMode.MANUAL) {
            out.put("mode", id(mode));
            strideScale.write(out, "stride_scale", 1f);
        } else {
            locomotionSpeed.write(out, "locomotion_speed", 0f);
        }
        if (dirX != 0f || dirY != 0f || dirZ != 1f) out.put("stride_direction", List.of((double) dirX, (double) dirY, (double) dirZ));
        if (minRootMotionSpeed != 0.1f) out.put("min_root_motion_speed", minRootMotionSpeed);
        if (!Float.isNaN(clampMin)) out.put("scale_clamp", List.of((double) clampMin, (double) clampMax));
        if (interpUp != 0f || interpDown != 0f) out.put("scale_interp", List.of((double) interpUp, (double) interpDown));
        if (pelvisAlpha != 0.5f) out.put("pelvis_alpha", pelvisAlpha);
        if (pelvisMaxDistance != 0.1f) out.put("pelvis_max_distance", pelvisMaxDistance);
        if (pelvisStiffness != 1f) out.put("pelvis_stiffness", pelvisStiffness);
        if (pelvisDamping != 1f) out.put("pelvis_damping", pelvisDamping);
        if (!compensateThighs) out.put("compensate_thighs", false);
        if (!clampToFk) out.put("clamp_to_fk", false);
        if (!orientToFloor) out.put("orient_to_floor", false);
    }

    /** {@code m.key} as two numbers, or null where absent. */
    static float[] numbers(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key) {
        return CgControlAlpha.numbers(c, at + "." + key, m.get(key), 2);
    }

    @Override
    public String toString() {
        return "stride_warping(" + feet.size() + " feet)";
    }
}
