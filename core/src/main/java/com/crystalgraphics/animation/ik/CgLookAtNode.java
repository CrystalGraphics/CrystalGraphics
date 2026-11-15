package com.crystalgraphics.animation.ik;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * One bone turned to aim an axis of its own at a point: Unreal's Look At ({@code FAnimNode_LookAt},
 * AnimNode_LookAt.cpp, over {@code AnimationCore::SolveAim}). The point is a world location, or an offset in another
 * bone's space; a new point can be eased toward over a time.
 *
 * <pre>{@code
 * // the head following a point the game sets each frame, its +y axis aimed, no more than 60 degrees off
 * CgLookAtNode.of(body, "head", CgIkTarget.world(LOOK_X, LOOK_Y, LOOK_Z)).clamp(60f)
 *
 * // the eyes on the other fighter's head bone, eased over 0.2 s, kept level by their +x axis
 * CgLookAtNode.of(body, "eye_l", CgIkTarget.of(CgBoneSpace.BONE, "rival_head", 0f, 0f, 0f))
 *         .lookAtAxis(0f, 0f, 1f).lookUpAxis(1f, 0f, 0f).interpolation(0.2f, CgLookAtNode.Blend.SINUSOIDAL, 0f)
 * }</pre>
 *
 * <ul>
 *   <li>The axes are in the bone's own space, Unreal's defaults: aim +y, up +x; the up axis is used only when set.</li>
 *   <li>With an interpolation time, a target that moves more than its trigger distance starts a new ease from where
 *       the last ease was heading: Unreal's, including its first ease starting at the skeleton's origin.</li>
 * </ul>
 */
public final class CgLookAtNode extends CgSkeletalControlNode {

    /** {@code EInterpolationBlend}: the ease toward a new target. */
    public enum Blend { LINEAR, CUBIC, SINUSOIDAL, EASE_IN_OUT_EXPONENT_2, EASE_IN_OUT_EXPONENT_3, EASE_IN_OUT_EXPONENT_4, EASE_IN_OUT_EXPONENT_5 }

    /** Reads {@code kind: look_at}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgLookAtNode n = of(source(c, at, m), c.string(at, m, "bone"), CgIkTarget.read(c, at + ".target", m.get("target")))
                .alpha(alpha(c, at, m)).clamp(number(c, at, m, "clamp", 0f));
        float[] aim = vector(c, at, m, "look_at_axis", 0f, 1f, 0f);
        n = n.lookAtAxis(aim[0], aim[1], aim[2]);
        float[] up = CgControlAlpha.numbers(c, at + ".look_up_axis", m.get("look_up_axis"), 3);
        if (up != null) n = n.lookUpAxis(up[0], up[1], up[2]);
        return n.interpolation(number(c, at, m, "interpolation_time", 0f), choice(c, at, m, "interpolation", Blend.class, Blend.LINEAR),
                number(c, at, m, "interpolation_trigger", 0f));
    };

    private final String bone;
    private final CgIkTarget target;
    private final Vector3f lookAtAxis;
    @Nullable
    private final Vector3f lookUpAxis;
    private final float clamp, interpTime, interpTrigger;
    private final Blend blend;

    private CgLookAtNode(CgAnimNode source, CgControlAlpha alpha, String bone, CgIkTarget target, Vector3f lookAtAxis,
                         @Nullable Vector3f lookUpAxis, float clamp, float interpTime, Blend blend, float interpTrigger) {
        super(source, alpha);
        this.bone = Objects.requireNonNull(bone, "bone");
        this.target = Objects.requireNonNull(target, "target");
        this.lookAtAxis = unit(lookAtAxis, "look-at axis");
        this.lookUpAxis = lookUpAxis == null ? null : unit(lookUpAxis, "look-up axis");
        this.clamp = clamp;
        this.interpTime = Math.max(0f, interpTime);
        this.blend = Objects.requireNonNull(blend, "blend");
        this.interpTrigger = Math.max(0f, interpTrigger);
    }

    private static Vector3f unit(Vector3f v, String what) {
        if (v.lengthSquared() < 1e-12f) throw new IllegalArgumentException("a zero " + what);
        return new Vector3f(v).normalize();
    }

    public static CgLookAtNode of(CgAnimNode source, String bone, CgIkTarget target) {
        return new CgLookAtNode(source, CgControlAlpha.FULL, bone, target, new Vector3f(0f, 1f, 0f), null, 0f, 0f, Blend.LINEAR, 0f);
    }

    public CgLookAtNode alpha(CgControlAlpha alpha) {
        return new CgLookAtNode(source, alpha, bone, target, lookAtAxis, lookUpAxis, clamp, interpTime, blend, interpTrigger);
    }

    /** The bone's axis, in its own space, aimed at the target. */
    public CgLookAtNode lookAtAxis(float x, float y, float z) {
        return new CgLookAtNode(source, alpha, bone, target, new Vector3f(x, y, z), lookUpAxis, clamp, interpTime, blend, interpTrigger);
    }

    /** The bone's axis, in its own space, kept where it is: the target is projected onto the plane it is normal to. */
    public CgLookAtNode lookUpAxis(float x, float y, float z) {
        return new CgLookAtNode(source, alpha, bone, target, lookAtAxis, new Vector3f(x, y, z), clamp, interpTime, blend, interpTrigger);
    }

    /** The most degrees the aim turns from where the input points it; 0, unclamped. */
    public CgLookAtNode clamp(float degrees) {
        return new CgLookAtNode(source, alpha, bone, target, lookAtAxis, lookUpAxis, degrees, interpTime, blend, interpTrigger);
    }

    /** A new target eased toward over {@code seconds} by {@code blend}, once it moves more than {@code trigger}. */
    public CgLookAtNode interpolation(float seconds, Blend blend, float trigger) {
        return new CgLookAtNode(source, alpha, bone, target, lookAtAxis, lookUpAxis, clamp, seconds, blend, trigger);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int b = bone(g.skeleton(), bone, this);
        int targetBone = target.bone(g.skeleton(), this);
        return new Control(1) {
            private final Matrix4f targetTm = new Matrix4f();
            private final Vector3f current = new Vector3f(), previous = new Vector3f(), lookAt = new Vector3f(),
                    found = new Vector3f(), origin = new Vector3f(), aim = new Vector3f(), up = new Vector3f(),
                    scratch = new Vector3f(), t = new Vector3f(), s = new Vector3f();
            private final Quaternionf delta = new Quaternionf(), q = new Quaternionf();
            private float accumulated;

            @Override
            protected void initialize(CgAnimGraphInstance g) {
                current.zero();
                previous.zero();
                lookAt.zero();
                accumulated = 0f;
            }

            @Override
            protected void update(CgAnimGraphInstance g, float dt) {
                accumulated = Math.max(0f, Math.min(interpTime, accumulated + dt));
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                Matrix4f m = out[0].set(pose.component(b));
                target.resolve(g, pose, targetBone, targetTm).getTranslation(found);
                if (found.distanceSquared(current) > interpTrigger * interpTrigger) {
                    if (accumulated >= interpTime) accumulated = 0f;   // starting to move: the ease restarts
                    previous.set(current);
                    current.set(found);
                } else if (interpTrigger == 0f) {
                    current.set(found);
                }
                if (interpTime > 0f) {
                    float a = accumulated / interpTime;
                    if (a < 1f) lookAt.set(previous).lerp(current, ease(a, blend));
                } else {
                    lookAt.set(current);
                }
                m.getNormalizedRotation(q);
                q.transform(aim.set(lookAtAxis));
                Vector3f upAxis = lookUpAxis == null ? null : q.transform(up.set(lookUpAxis));
                m.getTranslation(origin);
                CgIkMath.solveAim(origin, lookAt, aim, upAxis, clamp, scratch, delta);
                m.getScale(s);
                m.getTranslation(t);
                m.translationRotateScale(t, delta.mul(q, q).normalize(), s);
                bones[0] = b;
                return 1;
            }
        };
    }

    /** {@code AlphaToBlendType}. */
    static float ease(float a, Blend blend) {
        float r = switch (blend) {
            case LINEAR -> a;
            case SINUSOIDAL -> (float) (Math.sin(a * Math.PI - Math.PI / 2) + 1) / 2f;
            case CUBIC -> a * a * (3f - 2f * a);   // FMath::CubicInterp(0, 0, 1, 0, a)
            case EASE_IN_OUT_EXPONENT_2 -> easeInOut(a, 2);
            case EASE_IN_OUT_EXPONENT_3 -> easeInOut(a, 3);
            case EASE_IN_OUT_EXPONENT_4 -> easeInOut(a, 4);
            case EASE_IN_OUT_EXPONENT_5 -> easeInOut(a, 5);
        };
        return Math.max(0f, Math.min(1f, r));
    }

    /** {@code FMath::InterpEaseInOut(0, 1, a, exp)}. */
    private static float easeInOut(float a, int exp) {
        return a < 0.5f ? 0.5f * (float) Math.pow(2f * a, exp) : 1f - 0.5f * (float) Math.pow(2f * (1f - a), exp);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        target.reads(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("look_at");
        writeBase(out);
        out.put("bone", bone).put("target", target.tree());
        if (lookAtAxis.x != 0f || lookAtAxis.y != 1f || lookAtAxis.z != 0f) out.put("look_at_axis", List.of(lookAtAxis.x, lookAtAxis.y, lookAtAxis.z));
        if (lookUpAxis != null) out.put("look_up_axis", List.of(lookUpAxis.x, lookUpAxis.y, lookUpAxis.z));
        if (clamp != 0f) out.put("clamp", clamp);
        if (interpTime != 0f) out.put("interpolation_time", interpTime);
        if (blend != Blend.LINEAR) out.put("interpolation", id(blend));
        if (interpTrigger != 0f) out.put("interpolation_trigger", interpTrigger);
    }

    @Override
    public String toString() {
        return "look_at(" + bone + ")";
    }
}
