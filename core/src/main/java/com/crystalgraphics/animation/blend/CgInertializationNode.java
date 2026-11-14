package com.crystalgraphics.animation.blend;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * Smooths a switch in its source's pose away over time instead of crossfading two poses: Unreal's
 * {@code FAnimNode_Inertialization} (AnimNode_Inertialization.cpp). When a state machine below it takes a transition
 * marked {@code inertialize}, the source switches at once and this node carries the old pose's offset and velocity per
 * bone into the new one, decaying it to zero over the transition's time along a quintic curve that never overshoots.
 * Only the target state updates and evaluates, so it costs one pose where a crossfade costs two.
 *
 * <pre>{@code
 * CgAnimStateMachine moves = CgAnimStateMachine.builder("moves")
 *         .state(CgAnimState.of("idle", idle)).state(CgAnimState.of("hit", hit))
 *         .transition(CgAnimTransition.of("idle", "hit", CgAnimRule.event("hit")).crossfade(0.25f).inertialize())
 *         .build();
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .output(CgInertializationNode.of(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(moves))))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>It must sit above the machine that requests it; a machine inertializing with none above fails when an instance
 *       runs the graph.</li>
 *   <li>Requests in one frame merge to the shortest; an interrupted inertialization shortens the next by the time it
 *       lost while interruptions continue (Unreal's deficit).</li>
 *   <li>Each bone's offset is in its parent's space: the instance has no world placement, so a jump of the whole
 *       character is not corrected here, and material curves are not inertialized.</li>
 * </ul>
 */
public final class CgInertializationNode extends CgAnimNode {

    /** {@code UE_KINDA_SMALL_NUMBER}. */
    private static final float SMALL = 1e-4f;
    private static final float TIME_EPSILON = 1e-7f;

    private final CgAnimNode source;

    private CgInertializationNode(CgAnimNode source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The {@code inertialization} kind's reader: {@code {kind: inertialization, source: <name>}}. */
    public static final CgAnimNodeKind KIND = (c, at, node) -> of(c.pose(c.string(at, node, "source")));

    /** Inertialization over {@code source}: what transitions below request. */
    public static CgInertializationNode of(CgAnimNode source) {
        return new CgInertializationNode(source);
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        g.enterInertializer();
        Runtime src;
        try {
            src = g.runtime(source);
        } finally {
            g.exitInertializer();
        }
        return new Inertialization(g, src);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(source);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("inertialization").node("source", source);
    }

    @Override
    public String toString() {
        return "inertialization(" + source + ")";
    }

    /**
     * {@code CalcInertialFloat} (AnimNode_Inertialization.cpp:109-224): a quintic from {@code x0} with velocity
     * {@code v0} reaching zero, with zero velocity and acceleration, at {@code t1}, its start acceleration as near zero as
     * avoids overshoot, {@code t1} shortened for a fast start.
     */
    static float inertialFloat(float x0, float v0, float t, float t1) {
        if (t < 0f) t = 0f;
        if (t >= t1 - TIME_EPSILON) return 0f;
        float sign = 1f;
        if (x0 < 0f) {
            x0 = -x0;
            v0 = -v0;
            sign = -1f;
        }
        if (v0 > 0f) v0 = 0f;
        if (!(x0 >= 0f && v0 <= 0f && t >= 0f && t1 >= 0f)) return 0f;   // NaN in: Unreal zeroes every input
        if (v0 < -SMALL) t1 = Math.min(t1, -5f * x0 / v0);
        if (t >= t1 - TIME_EPSILON) return 0f;
        float t12 = t1 * t1, t13 = t1 * t12, t14 = t1 * t13, t15 = t1 * t14;
        float a0 = Math.max(0f, (-8f * t1 * v0 - 20f * x0) / t12);
        float a = -0.5f * (a0 * t12 + 6f * t1 * v0 + 12f * x0) / t15;
        float b = 0.5f * (3f * a0 * t12 + 16f * t1 * v0 + 30f * x0) / t14;
        float c = -0.5f * (3f * a0 * t12 + 12f * t1 * v0 + 20f * x0) / t13;
        float d = 0.5f * a0;
        return ((((a * t + b) * t + c) * t + d) * t + v0) * t * sign + x0 * sign;
    }

    /** {@code FMath::UnwindRadians}. */
    static float unwind(float a) {
        while (a > Math.PI) a -= (float) (2 * Math.PI);
        while (a < -Math.PI) a += (float) (2 * Math.PI);
        return a;
    }

    private static final class Inertialization extends Runtime implements CgAnimGraphInstance.Inertializer {

        private enum State { INACTIVE, PENDING, ACTIVE }

        private final Runtime source;
        private final int bones;
        private float[] requests = new float[2];
        private int requestCount;
        private State state = State.INACTIVE;
        private float elapsed, duration, deficit, deltaTime, currDelta, prevDelta;
        private CgAnimPose prev, curr;
        private boolean hasPrev, hasCurr;
        // Per bone: translation, rotation and scale offsets as direction and magnitude with a speed
        private final float[] tDir, tMag, tSpeed, rAxis, rAngle, rSpeed, sDir, sMag, sSpeed;
        private final Vector3f v0 = new Vector3f(), v1 = new Vector3f(), v2 = new Vector3f();
        private final Quaternionf q0 = new Quaternionf(), q1 = new Quaternionf(), q2 = new Quaternionf();

        Inertialization(CgAnimGraphInstance g, Runtime source) {
            this.source = source;
            bones = g.skeleton().boneCount();
            tDir = new float[bones * 3];
            tMag = new float[bones];
            tSpeed = new float[bones];
            rAxis = new float[bones * 3];
            rAngle = new float[bones];
            rSpeed = new float[bones];
            sDir = new float[bones * 3];
            sMag = new float[bones];
            sSpeed = new float[bones];
            prev = new CgAnimPose(g.skeleton());
            curr = new CgAnimPose(g.skeleton());
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            source.initialize(g);
            requestCount = 0;
            hasPrev = hasCurr = false;
            deltaTime = 0f;
            deactivate();
        }

        @Override
        public void requestInertialization(float duration) {
            if (duration < 0f) return;
            for (int i = 0; i < requestCount; i++) if (requests[i] == duration) return;   // AddUnique
            if (requestCount == requests.length) requests = Arrays.copyOf(requests, requestCount * 2);
            requests[requestCount++] = duration;
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            g.pushInertializer(this);
            try {
                source.update(g, weight);
            } finally {
                g.popInertializer();
            }
            deltaTime += g.delta();
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            source.evaluate(g, out);
            if (requestCount > 0 && hasCurr) {
                float applied = 0f;
                if (state == State.ACTIVE) {
                    boolean applyDeficit = deficit > 0f;
                    deficit = duration - elapsed;
                    applied = applyDeficit ? deficit : 0f;
                }
                state = State.PENDING;
                elapsed = 0f;
                duration = Math.max(requests[0] - applied, 0f);
                for (int i = 1; i < requestCount; i++) duration = Math.min(duration, Math.max(requests[i] - applied, 0f));
            }
            requestCount = 0;
            if (state != State.INACTIVE) {
                elapsed += deltaTime;
                if (elapsed >= duration) deficit = 0f;
                else deficit -= Math.min(deficit, deltaTime);
                if (elapsed >= duration) deactivate();
            }
            if (state == State.PENDING) {
                initFrom(out, curr, hasPrev ? prev : curr, currDelta);
                state = State.ACTIVE;
            }
            if (state == State.ACTIVE) applyTo(out);
            if (hasCurr) {
                CgAnimPose swap = prev;
                prev = curr;
                curr = swap;
                prevDelta = currDelta;
                hasPrev = true;
            }
            curr.set(out);
            currDelta = deltaTime;
            hasCurr = true;
            deltaTime = 0f;
        }

        private void deactivate() {
            state = State.INACTIVE;
            elapsed = duration = deficit = 0f;
        }

        /** {@code InitFrom} (:761-1028): each bone's offset from the last two poses, in its parent's space. */
        private void initFrom(CgAnimPose pose, CgAnimPose prev1, CgAnimPose prev2, float prev1Delta) {
            for (int b = 0; b < bones; b++) {
                // Translation
                Vector3f p = pose.translation(b, v0);
                Vector3f t = prev1.translation(b, v1).sub(p);
                float mag = t.length();
                if (mag > SMALL) t.div(mag);
                else t.zero();
                float speed = 0f;
                if (prev1Delta > SMALL && mag > SMALL) {
                    Vector3f pt = prev2.translation(b, v2).sub(p);
                    speed = (mag - pt.dot(t)) / prev1Delta;
                }
                put(tDir, b, t);
                tMag[b] = mag;
                tSpeed[b] = speed;

                // Rotation: prev1 · pose⁻¹ as an axis and an unwound, non-negative angle
                Quaternionf pq = pose.rotation(b, q0);
                Quaternionf q = prev1.rotation(b, q1).mul(q2.set(pq).invert());
                float angle = unwind(2f * (float) Math.acos(Math.max(-1f, Math.min(1f, q.w))));
                float sq = q.x * q.x + q.y * q.y + q.z * q.z;
                Vector3f axis = sq < 1e-8f ? v1.set(1, 0, 0) : v1.set(q.x, q.y, q.z).mul(1f / (float) Math.sqrt(sq));
                if (angle < 0f) {
                    axis.negate();
                    angle = -angle;
                }
                float rspeed = 0f;
                if (prev1Delta > SMALL && angle > SMALL) {
                    Quaternionf pqq = prev2.rotation(b, q1).mul(q2.set(pq).invert());
                    float twist = unwind(2f * (float) Math.atan2(axis.x * pqq.x + axis.y * pqq.y + axis.z * pqq.z, pqq.w));
                    rspeed = unwind(angle - twist) / prev1Delta;
                }
                put(rAxis, b, axis);
                rAngle[b] = angle;
                rSpeed[b] = rspeed;

                // Scale
                Vector3f ps = pose.scale(b, v0);
                Vector3f s = prev1.scale(b, v1).sub(ps);
                float smag = s.length();
                if (smag > SMALL) s.div(smag);
                else s.zero();
                float sspeed = 0f;
                if (prev1Delta > SMALL && smag > SMALL) {
                    Vector3f pss = prev2.scale(b, v2).sub(ps);
                    sspeed = (smag - pss.dot(s)) / prev1Delta;
                }
                put(sDir, b, s);
                sMag[b] = smag;
                sSpeed[b] = sspeed;
            }
        }

        /** {@code ApplyTo} (:1030-1065): each offset decayed by {@link #inertialFloat}, added to the pose. */
        private void applyTo(CgAnimPose pose) {
            for (int b = 0; b < bones; b++) {
                float tk = inertialFloat(tMag[b], tSpeed[b], elapsed, duration);
                Vector3f t = pose.translation(b, v0).add(tDir[b * 3] * tk, tDir[b * 3 + 1] * tk, tDir[b * 3 + 2] * tk);
                float rk = inertialFloat(rAngle[b], rSpeed[b], elapsed, duration);
                Quaternionf r = q1.setAngleAxis(rk, rAxis[b * 3], rAxis[b * 3 + 1], rAxis[b * 3 + 2]).mul(pose.rotation(b, q0)).normalize();
                float sk = inertialFloat(sMag[b], sSpeed[b], elapsed, duration);
                Vector3f s = pose.scale(b, v1).add(sDir[b * 3] * sk, sDir[b * 3 + 1] * sk, sDir[b * 3 + 2] * sk);
                pose.set(b, t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z);
            }
        }

        private static void put(float[] to, int b, Vector3f v) {
            to[b * 3] = v.x;
            to[b * 3 + 1] = v.y;
            to[b * 3 + 2] = v.z;
        }
    }
}
