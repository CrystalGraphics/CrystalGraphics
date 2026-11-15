package com.crystalgraphics.animation.blend;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.sync.CgAnimAssetPlayer;
import com.crystalgraphics.animation.sync.CgAnimTickContext;
import com.crystalgraphics.animation.sync.CgMarkerSync;
import com.crystalgraphics.animation.sync.CgMarkerTickRecord;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAdditive;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.easing.CgSmoothing;

/**
 * A blend space playing at its inputs: Unreal's {@code FAnimNode_BlendSpacePlayer} (AnimNode_BlendSpacePlayer.cpp). Each
 * frame its inputs are smoothed per axis, the samples found there, their weights moved toward the new targets, and its
 * normalised time advanced, every sample at the same fraction of its own clip, or by its sync markers when they have
 * them, so feet stay in step across the blend ({@code UBlendSpace::TickAssetPlayer}, BlendSpace.cpp:517-910).
 *
 * <pre>{@code
 * CgBlendSpacePlayerNode.of(RUN, SPEED);                          // a 1D blend space by a variable
 * CgBlendSpacePlayerNode.of(STRAFE, DIRECTION, SPEED);            // 2D
 * CgBlendSpacePlayerNode.of(RUN, SPEED).playRate(STRIDE).sync(CgSyncGroup.of("feet"));
 * }</pre>
 *
 * <ul>
 *   <li>Loops by default, as Unreal's.</li>
 *   <li>Its time moves only while it is relevant; the inputs' smoothing and the weights' too.</li>
 *   <li>A blend space whose samples are additive plays differences: an apply additive or aim offset node adds them.</li>
 * </ul>
 */
public final class CgBlendSpacePlayerNode extends CgAnimNode {

    /** The {@code blend_space_player} kind's reader. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgBlendSpace space = read(c, at, m);
        Inputs in = Inputs.read(c, at, m);
        CgBlendSpacePlayerNode n = new CgBlendSpacePlayerNode(space, in, !Boolean.FALSE.equals(m.get("loop")),
                m.get("start_position") instanceof Number s ? s.floatValue() : 0f, !Boolean.FALSE.equals(m.get("reset_on_change")),
                c.syncGroup(at, m));
        return n;
    };

    private final CgBlendSpace space;
    private final Inputs inputs;
    private final boolean loop, resetOnChange;
    private final float startPosition;
    @Nullable
    private final CgSyncGroup group;

    private CgBlendSpacePlayerNode(CgBlendSpace space, Inputs inputs, boolean loop, float startPosition, boolean resetOnChange,
                                   @Nullable CgSyncGroup group) {
        this.space = Objects.requireNonNull(space, "space");
        this.inputs = inputs;
        this.loop = loop;
        this.startPosition = startPosition;
        this.resetOnChange = resetOnChange;
        this.group = group;
    }

    /** {@code space} at a constant place. */
    public static CgBlendSpacePlayerNode of(CgBlendSpace space, float x, float y) {
        return new CgBlendSpacePlayerNode(space, new Inputs(x, null, y, null, 1f, null), true, 0f, true, null);
    }

    /** A 1D {@code space} by {@code x}. */
    public static CgBlendSpacePlayerNode of(CgBlendSpace space, CgAnimVariable<Float> x) {
        return new CgBlendSpacePlayerNode(space, new Inputs(0f, Objects.requireNonNull(x, "x"), 0f, null, 1f, null), true, 0f, true, null);
    }

    /** A 2D {@code space} by {@code x} and {@code y}. */
    public static CgBlendSpacePlayerNode of(CgBlendSpace space, CgAnimVariable<Float> x, CgAnimVariable<Float> y) {
        return new CgBlendSpacePlayerNode(space, new Inputs(0f, Objects.requireNonNull(x, "x"), 0f, Objects.requireNonNull(y, "y"), 1f, null),
                true, 0f, true, null);
    }

    /** Played at {@code rate}. */
    public CgBlendSpacePlayerNode playRate(float rate) {
        return new CgBlendSpacePlayerNode(space, inputs.rate(rate, null), loop, startPosition, resetOnChange, group);
    }

    /** Played at a rate read from {@code rate} each frame. */
    public CgBlendSpacePlayerNode playRate(CgAnimVariable<Float> rate) {
        return new CgBlendSpacePlayerNode(space, inputs.rate(1f, Objects.requireNonNull(rate, "rate")), loop, startPosition, resetOnChange, group);
    }

    /** Stops at its end instead of looping. */
    public CgBlendSpacePlayerNode once() {
        return new CgBlendSpacePlayerNode(space, inputs, false, startPosition, resetOnChange, group);
    }

    /** Where it starts, 0..1 of its normalised time. */
    public CgBlendSpacePlayerNode startPosition(float normalised) {
        return new CgBlendSpacePlayerNode(space, inputs, loop, normalised, resetOnChange, group);
    }

    /** Ticked with {@code group}'s other players. */
    public CgBlendSpacePlayerNode sync(CgSyncGroup group) {
        return new CgBlendSpacePlayerNode(space, inputs, loop, startPosition, resetOnChange, Objects.requireNonNull(group, "group"));
    }

    public CgBlendSpace space() {
        return space;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new PlayerRuntime(space, inputs, loop, startPosition, group);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        inputs.reads(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("blend_space_player").put("blend_space", space.id());
        inputs.write(out);
        if (!loop) out.put("loop", false);
        if (startPosition != 0f) out.put("start_position", (double) startPosition);
        if (!resetOnChange) out.put("reset_on_change", false);
        out.sync(group);
    }

    @Override
    public String toString() {
        return "blendSpace(" + space.id() + ")";
    }

    /** A blend space id read and bound to the mesh's clips: what both blend space kinds read. */
    static CgBlendSpace read(CgAnimNodeKind.Context c, String at, Map<?, ?> m) {
        String id = c.string(at, m, "blend_space");
        CgBlendSpaceDefinition d = (CgBlendSpaceDefinition) c.definition(at + ".blend_space", CgBodyKinds.BLEND_SPACE, id);
        return d.bind(clip -> c.clip(at + ".blend_space(" + id + ")", clip));
    }

    /** Its inputs: each a constant or a variable. */
    record Inputs(float x, @Nullable CgAnimVariable<Float> xVar, float y, @Nullable CgAnimVariable<Float> yVar, float rate,
                  @Nullable CgAnimVariable<Float> rateVar) {

        Inputs rate(float r, @Nullable CgAnimVariable<Float> v) {
            return new Inputs(x, xVar, y, yVar, r, v);
        }

        static Inputs read(CgAnimNodeKind.Context c, String at, Map<?, ?> m) {
            float[] k = new float[3];
            @SuppressWarnings("unchecked") CgAnimVariable<Float>[] v = new CgAnimVariable[3];
            String[] keys = {"x", "y", "play_rate"};
            for (int i = 0; i < 3; i++) {
                Object o = m.get(keys[i]);
                k[i] = i == 2 ? 1f : 0f;
                if (o instanceof String name) v[i] = c.variable(at + "." + keys[i], name, CgAnimVariable.Type.FLOAT);
                else if (o != null) k[i] = c.number(at + "." + keys[i], o);
            }
            return new Inputs(k[0], v[0], k[1], v[1], k[2], v[2]);
        }

        void reads(Consumer<CgAnimVariable<?>> each) {
            if (xVar != null) each.accept(xVar);
            if (yVar != null) each.accept(yVar);
            if (rateVar != null) each.accept(rateVar);
        }

        void write(CgAnimNodeWriter out) {
            out.alpha("x", x, xVar, 0f).alpha("y", y, yVar, 0f).alpha("play_rate", rate, rateVar, 1f);
        }
    }

    /** A blend space's state in one instance: its filters, its samples' weights and times, its normalised time. */
    static final class PlayerRuntime extends Runtime implements CgAnimAssetPlayer {

        private final CgBlendSpace space;
        private final Inputs inputs;
        private final boolean loop;
        private final float startPosition;
        @Nullable
        private final CgSyncGroup group;
        private final CgBlendFilter[] filters;
        private final CgMarkerTickRecord markers = new CgMarkerTickRecord();
        // This frame's samples, the last frame's, and the lookup's targets: sample index, weight, play rate, weight rate
        private final int[] idx, oldIdx, newIdx;
        private final float[] w, rate, wRate, oldW, oldRate, oldWRate, newW, newRate;
        private int count, oldCount;
        // Per sample of the blend space: its time, its time before the tick, its markers
        private final float[] sTime, sPrev;
        private final CgMarkerTickRecord[] sMarkers;
        private final float[] spring = new float[2];
        private final CgAnimPose[] poses;
        private final float[] poseWeights;
        private float px, py, rateNow, time, previous, lastDelta, weight;
        private boolean fullWeight;

        PlayerRuntime(CgBlendSpace space, Inputs inputs, boolean loop, float startPosition, @Nullable CgSyncGroup group) {
            this.space = space;
            this.inputs = inputs;
            this.loop = loop;
            this.startPosition = startPosition;
            this.group = group;
            int n = space.scratchSize(), s = space.samples().size();
            idx = new int[n];
            oldIdx = new int[n];
            newIdx = new int[n];
            w = new float[n];
            rate = new float[n];
            wRate = new float[n];
            oldW = new float[n];
            oldRate = new float[n];
            oldWRate = new float[n];
            newW = new float[n];
            newRate = new float[n];
            sTime = new float[s];
            sPrev = new float[s];
            sMarkers = new CgMarkerTickRecord[s];
            for (int i = 0; i < s; i++) sMarkers[i] = new CgMarkerTickRecord();
            filters = new CgBlendFilter[space.axes().size()];
            for (int i = 0; i < filters.length; i++) filters[i] = new CgBlendFilter();
            poses = new CgAnimPose[s];
            poseWeights = new float[s];
        }

        /** {@code Reinitialize}: no samples, its time at the start, its filters fresh. */
        @Override
        public void initialize(CgAnimGraphInstance g) {
            count = 0;
            time = previous = Math.max(0f, Math.min(1f, startPosition));
            rateNow = inputs.rateVar == null ? inputs.rate : rateNow;
            if (startPosition == 0f && inputs.rateVar == null && inputs.rate < 0f) time = previous = 1f;
            lastDelta = 0f;
            fullWeight = false;
            markers.reset();
            for (int i = 0; i < filters.length; i++) {
                CgBlendSpace.Axis a = space.axes().get(i);
                CgBlendSpace.Smoothing s = a.smoothing();
                filters[i].initialize(s.time(), s.type(), s.damping(), a.min(), a.max(), s.maxSpeed(), !a.wrap());
            }
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            this.weight = weight;
            fullWeight |= weight >= 1f - CgAnimPose.ZERO_WEIGHT;
            px = inputs.xVar != null ? g.get(inputs.xVar) : inputs.x;
            py = inputs.yVar != null ? g.get(inputs.yVar) : inputs.y;
            rateNow = inputs.rateVar != null ? g.get(inputs.rateVar) : inputs.rate;
            if (weight > CgAnimPose.ZERO_WEIGHT) g.record(this, weight, group, fullWeight);
        }

        @Override
        public void tick(CgAnimTickContext ctx, boolean canUseMarkerSync) {
            float dt = ctx.delta();
            float fx = filter(0, px, dt), fy = filters.length > 1 ? filter(1, py, dt) : py;
            // UpdateBlendSamples_Internal: last frame's kept, the targets found, the weights moved toward them
            System.arraycopy(idx, 0, oldIdx, 0, count);
            System.arraycopy(w, 0, oldW, 0, count);
            System.arraycopy(rate, 0, oldRate, 0, count);
            System.arraycopy(wRate, 0, oldWRate, 0, count);
            oldCount = count;
            int targets = space.lookup(fx, fy, newIdx, newW, newRate);
            if (targets == 0) {
                count = 0;
                return;
            }
            if (space.weightSpeed() > 0f) {
                if (interpolate(dt, targets)) normalize();
                else copyTargets(targets);
            } else {
                copyTargets(targets);
            }
            // A sample new to the list starts afresh, as Unreal's new entry does
            for (int i = 0; i < count; i++) {
                if (!inOld(idx[i])) {
                    sTime[idx[i]] = sPrev[idx[i]] = 0f;
                    sMarkers[idx[i]].reset();
                }
            }
            float pre = 0f;
            if (space.weightSpeed() > 0f) pre = space.legacyLength() ? space.length(newIdx, newW, newRate, targets) : space.length(idx, w, rate, count);
            float move = rateNow * dt * space.axisScale(px, py, fx, fy);
            boolean canMarker = space.withMarkers() >= 0 && (ctx.single() || canUseMarkerSync && ctx.canUseMarkerPosition());
            float length = space.length(idx, w, rate, count);
            if (pre > 0f && length > 0f) move *= pre / length;

            float norm = ctx.resync() ? ctx.ratio() : time, prevNorm = norm;
            if (ctx.leader()) {
                ctx.previousRatio(norm);
                int hi = canMarker ? heaviest(true) : -1;
                if (hi < 0) canMarker = false;
                if (canMarker) {
                    int s = idx[hi];
                    CgBlendSpace.Sample sample = space.samples().get(s);
                    float len = sample.clip().duration();
                    boolean reset = false;
                    if (!markers.valid(loop)) {
                        sMarkers[s].reset();
                        sTime[s] = norm * len;
                        reset = true;
                    } else if (!sMarkers[s].valid(loop) && ctx.start().valid()) {
                        sTime[s] = CgMarkerSync.indicesForPosition(sample.clip(), ctx.start(), true, sMarkers[s], sTime[s]);
                    }
                    float leaderMove = move * sample.rateScale();
                    ctx.leaderDelta(leaderMove);
                    if (Math.abs(leaderMove) > CgSmoothing.SMALL_NUMBER) {
                        sTime[s] = CgMarkerSync.tickByMarkerAsLeader(sample.clip(), sMarkers[s], ctx, sTime[s], leaderMove, loop);
                        sPrev[s] = ctx.previousTime();
                        followers(ctx, hi, reset);
                    } else if (!markers.valid(loop)) {
                        CgMarkerSync.indicesForTime(sample.clip(), sTime[s], loop, ctx, sMarkers[s]);
                        CgMarkerSync.position(sample.clip(), sMarkers[s].previous, sMarkers[s].next, sTime[s], ctx.start());
                        ctx.end().set(ctx.start());
                        followers(ctx, hi, true);
                    }
                    norm = len > 0f ? sTime[s] / len : 0f;
                    markers.set(sMarkers[s]);
                } else {
                    float t = CgAnimTickContext.advanceTime(loop, move, norm * length, length);
                    norm = length != 0f ? t / length : 0f;
                }
                ctx.ratio(norm);
            } else {
                if (!ctx.start().valid()) canMarker = false;
                if (canMarker) {
                    int hi = heaviest(false), s = idx[hi];
                    float len = space.samples().get(s).clip().duration();
                    if (dt != 0f) {
                        if (!markers.valid(loop)) sTime[s] = norm * len;
                        followers(ctx, -1, false);
                    }
                    markers.set(sMarkers[s]);
                    norm = len > 0f ? sTime[s] / len : 0f;
                } else {
                    prevNorm = ctx.previousRatio();
                    norm = ctx.ratio();
                }
            }
            // Each sample at the same fraction of its own clip, unless its markers placed it
            float cp = Math.max(0f, Math.min(1f, prevNorm)), cc = Math.max(0f, Math.min(1f, norm));
            for (int i = 0; i < count; i++) {
                if (w[i] <= CgAnimPose.ZERO_WEIGHT) continue;
                int s = idx[i];
                CgBlendSpace.Sample sample = space.samples().get(s);
                if (!canMarker || sample.clip().uniqueMarkerNames() == null) {
                    float len = sample.clip().duration();
                    boolean forwards = sample.rateScale() >= 0f;
                    sPrev[s] = (forwards ? cp : 1f - cp) * len;
                    sTime[s] = (forwards ? cc : 1f - cc) * len;
                }
            }
            time = norm;
            previous = prevNorm;
            lastDelta = move;
        }

        private float filter(int axis, float input, float dt) {
            CgBlendSpace.Axis a = space.axes().get(axis);
            if (a.wrap()) filters[axis].wrapToValue(input, a.max() - a.min());
            return filters[axis].update(input, dt);
        }

        private boolean inOld(int sample) {
            for (int i = 0; i < oldCount; i++) if (oldIdx[i] == sample) return true;
            return false;
        }

        private void copyTargets(int targets) {
            System.arraycopy(newIdx, 0, idx, 0, targets);
            System.arraycopy(newW, 0, w, 0, targets);
            System.arraycopy(newRate, 0, rate, 0, targets);
            for (int i = 0; i < targets; i++) wRate[i] = 0f;
            count = targets;
        }

        /** {@code InterpolateWeightOfSampleData} (BlendSpace.cpp:2174-2305): last frame's weights moved toward the targets. */
        private boolean interpolate(float dt, int targets) {
            count = 0;
            float total = 0f;
            for (int o = 0; o < oldCount; o++) {
                boolean found = false;
                for (int t = 0; t < targets; t++) {
                    if (newIdx[t] != oldIdx[o]) continue;
                    smooth(oldW[o], oldWRate[o], newW[t], dt);
                    if (spring[0] > CgAnimPose.ZERO_WEIGHT) {
                        total += add(newIdx[t], newRate[t]);
                        found = true;
                    }
                    break;
                }
                if (!found) {
                    smooth(oldW[o], oldWRate[o], 0f, dt);
                    if (spring[0] > CgAnimPose.ZERO_WEIGHT) total += add(oldIdx[o], oldRate[o]);
                }
            }
            for (int t = 0; t < targets; t++) {
                boolean held = false;
                for (int i = 0; i < count && !held; i++) held = idx[i] == newIdx[t];
                if (held) continue;
                smooth(0f, 0f, newW[t], dt);
                if (spring[0] > CgAnimPose.ZERO_WEIGHT) total += add(newIdx[t], newRate[t]);
            }
            return total > CgAnimPose.ZERO_WEIGHT;
        }

        private float add(int sample, float r) {
            idx[count] = sample;
            w[count] = spring[0];
            wRate[count] = spring[1];
            rate[count++] = r;
            return Math.max(0f, Math.min(1f, spring[0]));
        }

        /** {@code SmoothWeight}: eased by a critically damped spring, else at a constant speed. Into {@code spring}. */
        private void smooth(float from, float fromRate, float target, float dt) {
            float speed = space.weightSpeed();
            if (speed <= 0f) {
                spring[0] = target;
                spring[1] = 0f;
            } else if (space.weightEase()) {
                spring[0] = from;
                spring[1] = fromRate;
                CgSmoothing.criticallyDamped(spring, target, 0f, dt, 1f / ((float) Math.E * speed));
            } else {
                spring[0] = CgSmoothing.interpConstantTo(from, target, dt, speed);
                spring[1] = fromRate;
            }
        }

        /** {@code FBlendSampleData::NormalizeDataWeight} (AnimationAsset.cpp:783-850). */
        private void normalize() {
            float total = 0f;
            for (int i = 0; i < count; i++) total += w[i];
            if (total > CgAnimPose.ZERO_WEIGHT) {
                if (Math.abs(total - 1f) > CgAnimPose.ZERO_WEIGHT) for (int i = 0; i < count; i++) w[i] /= total;
            } else {
                for (int i = 0; i < count; i++) w[i] = 1f / count;
            }
        }

        /** The heaviest sample in the list, among those whose clips have markers when {@code marked}; -1 for none. */
        private int heaviest(boolean marked) {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (marked && space.samples().get(idx[i]).clip().uniqueMarkerNames() == null) continue;
                if (best < 0 || w[i] > w[best]) best = i;
            }
            return best;
        }

        /** {@code TickFollowerSamples}: every other marked sample follows the leader's markers. */
        private void followers(CgAnimTickContext ctx, int leader, boolean reset) {
            for (int i = 0; i < count; i++) {
                if (i == leader) continue;
                int s = idx[i];
                CgAnimSequence clip = space.samples().get(s).clip();
                if (reset) sMarkers[s].reset();
                if (clip.uniqueMarkerNames() != null) {
                    sTime[s] = CgMarkerSync.tickByMarkerAsFollower(clip, sMarkers[s], ctx, sTime[s], ctx.leaderDelta(), loop);
                    sPrev[s] = ctx.previousTime();
                }
            }
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (count == 0) {
                if (space.additive() == CgAdditive.Type.NONE) out.rest();
                else additiveIdentity(out);
                return;
            }
            int n = 0;
            for (int i = 0; i < count; i++) {
                CgAnimSequence clip = space.samples().get(idx[i]).clip();
                CgAnimPose p = g.borrow();
                float t = Math.max(0f, Math.min(clip.duration(), sTime[idx[i]]));
                if (space.additive() == CgAdditive.Type.NONE) {
                    p.rest();
                    clip.sample(p, t, g.extractsRootMotion());
                } else {
                    CgAnimPose base = g.borrow();
                    clip.sampleAdditive(p, base, t);
                    g.giveBack(base);
                }
                poses[n] = p;
                poseWeights[n++] = Math.max(0f, Math.min(1f, w[i]));
            }
            out.blend(poses, poseWeights, n);
            for (int i = n - 1; i >= 0; i--) {
                g.giveBack(poses[i]);
                poses[i] = null;
            }
        }

        private static void additiveIdentity(CgAnimPose out) {
            for (int b = 0; b < out.skeleton().boneCount(); b++) out.set(b, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f);
        }

        @Override
        public void forEachSample(float weight, SampleSink sink) {
            for (int i = 0; i < count; i++) {
                if (w[i] <= CgAnimPose.ZERO_WEIGHT) continue;
                int s = idx[i];
                CgAnimSequence clip = space.samples().get(s).clip();
                float moved = sTime[s] - sPrev[s];
                // against the play rate: it wrapped
                if (moved * rateNow < 0f && loop) moved += Math.signum(rateNow) * clip.duration();
                sink.sample(clip, sTime[s], moved, weight * w[i]);
            }
        }

        // ── the asset player ─────────────────────────────────────────────────────────────

        /** This frame's samples: how many, and each one's sample index and weight. */
        int samples() {
            return count;
        }

        int sample(int i) {
            return idx[i];
        }

        float sampleWeight(int i) {
            return w[i];
        }

        /** Sample {@code s}'s time in its clip, seconds. */
        float sampleTime(int s) {
            return sTime[s];
        }

        @Override
        @Nullable
        public String[] uniqueMarkers() {
            return space.uniqueMarkers();
        }

        @Override
        public Object asset() {
            return space;
        }

        @Override
        public CgMarkerTickRecord markerRecord() {
            return markers;
        }

        @Override
        public float weight() {
            return weight;
        }

        /** A blend space's play length is 1 (BlendSpace.h:496). */
        @Override
        public float playLength() {
            return 1f;
        }

        @Override
        public float accumulatedTime() {
            return time;
        }

        @Override
        public float previousTime() {
            return previous;
        }

        @Override
        public float lastDelta() {
            return lastDelta;
        }

        @Override
        public boolean looping() {
            return loop;
        }

        @Override
        public float playRate() {
            return rateNow;
        }

        @Override
        public float currentAssetLength() {
            int hi = heaviest(false);
            return hi < 0 ? 0f : space.samples().get(idx[hi]).clip().duration();
        }

        @Override
        public float currentAssetTimePlayRateAdjusted() {
            float len = currentAssetLength();
            return rateNow < 0f ? len - time * len : len * time;
        }
    }
}
