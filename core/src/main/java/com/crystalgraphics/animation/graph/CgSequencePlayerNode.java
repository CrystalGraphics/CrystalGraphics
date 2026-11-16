package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.sync.CgAnimAssetPlayer;
import com.crystalgraphics.animation.sync.CgAnimTickContext;
import com.crystalgraphics.animation.sync.CgMarkerSync;
import com.crystalgraphics.animation.sync.CgMarkerTickRecord;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * A clip playing: Unreal's {@code FAnimNode_SequencePlayer}. Its time moves after the graph's walk, with its sync group
 * when it has one ({@code UAnimSequenceBase::TickAssetPlayer}, AnimSequenceBase.cpp:500-595): a leader by its play rate,
 * wrapping when it loops and holding its last frame when it does not; a follower by its leader's markers, or at its
 * leader's fraction of its own length.
 *
 * <pre>{@code
 * CgSequencePlayerNode idle = CgSequencePlayerNode.of(IDLE).loop();
 * CgSequencePlayerNode land = CgSequencePlayerNode.of(LAND);                       // once, then held at its end
 * CgSequencePlayerNode jog = CgSequencePlayerNode.of(JOG).loop().playRate(1.2f).startPosition(0.25f);
 * CgSequencePlayerNode walk = CgSequencePlayerNode.of(WALK).loop().playRate(STRIDE);  // a variable: play rate by speed
 * CgSequencePlayerNode run = CgSequencePlayerNode.of(RUN).loop().sync(CgSyncGroup.of("feet"));   // feet in step
 * }</pre>
 *
 * <ul>
 *   <li>An additive clip ({@code CgClipSettings.additive}) plays as differences, for an apply additive node to add.</li>
 *   <li>Its time moves only while it is relevant: a player blended out keeps its place and starts again from it; in a
 *       group it rejoins at the group's place.</li>
 *   <li>Immutable: each setter answers a new node.</li>
 * </ul>
 */
public final class CgSequencePlayerNode extends CgAnimNode {

    @Nullable
    private final CgAnimSequence clip;
    /** The id it names its clip by in data: the id it was loaded by, else the clip's name. */
    @Nullable
    private final String clipId;
    private final boolean loop;
    private final float playRate, startPosition;
    @Nullable
    private final CgAnimVariable<Float> playRateVariable;
    @Nullable
    private final CgSyncGroup group;

    private CgSequencePlayerNode(@Nullable CgAnimSequence clip, @Nullable String clipId, boolean loop, float playRate,
                                 @Nullable CgAnimVariable<Float> playRateVariable, float startPosition, @Nullable CgSyncGroup group) {
        this.clip = clip;
        this.clipId = clipId;
        this.loop = loop;
        this.playRate = playRate;
        this.playRateVariable = playRateVariable;
        this.startPosition = startPosition;
        this.group = group;
    }

    /** {@code clip}, once from its start at rate 1. */
    public static CgSequencePlayerNode of(CgAnimSequence clip) {
        return new CgSequencePlayerNode(Objects.requireNonNull(clip, "clip"), clip.name(), false, 1f, null, 0f, null);
    }

    /** {@code clip}, which data names {@code id}: what the codec builds. */
    static CgSequencePlayerNode of(CgAnimSequence clip, String id) {
        return new CgSequencePlayerNode(Objects.requireNonNull(clip, "clip"), id, false, 1f, null, 0f, null);
    }

    /** The single node graph's player: no clip until the instance plays one. */
    static CgSequencePlayerNode single() {
        return new CgSequencePlayerNode(null, null, true, 1f, null, 0f, null);
    }

    /** Wrapping at its end. */
    public CgSequencePlayerNode loop() {
        return new CgSequencePlayerNode(clip, clipId, true, playRate, playRateVariable, startPosition, group);
    }

    /** Its seconds a second of play; negative plays backwards. */
    public CgSequencePlayerNode playRate(float rate) {
        return new CgSequencePlayerNode(clip, clipId, loop, rate, null, startPosition, group);
    }

    /** Its play rate read from {@code rate} each frame: a stride matched to the character's speed. */
    public CgSequencePlayerNode playRate(CgAnimVariable<Float> rate) {
        return new CgSequencePlayerNode(clip, clipId, loop, 1f, Objects.requireNonNull(rate, "rate"), startPosition, group);
    }

    /** Where it starts, seconds. */
    public CgSequencePlayerNode startPosition(float seconds) {
        return new CgSequencePlayerNode(clip, clipId, loop, playRate, playRateVariable, seconds, group);
    }

    /** Ticked with {@code group}'s other players. */
    public CgSequencePlayerNode sync(CgSyncGroup group) {
        return new CgSequencePlayerNode(clip, clipId, loop, playRate, playRateVariable, startPosition, Objects.requireNonNull(group, "group"));
    }

    @Nullable
    public CgAnimSequence clip() {
        return clip;
    }

    public boolean loops() {
        return loop;
    }

    @Nullable
    public CgSyncGroup syncGroup() {
        return group;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new PlayerRuntime(this);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        if (playRateVariable != null) each.accept(playRateVariable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        if (clip == null) throw new UnsupportedOperationException("the single node graph's player has no data form");
        out.kind("sequence_player").put("clip", clipId);
        if (loop) out.put("loop", true);
        out.alpha("play_rate", playRate, playRateVariable, 1f);
        if (startPosition != 0f) out.put("start_position", (double) startPosition);
        out.sync(group);
    }

    @Override
    public String toString() {
        return "player(" + (clip == null ? "single" : clip.name()) + ")";
    }

    /** A player's state: its clip and time. The single node graph's is set by {@code CgAnimInstance.play}. */
    public static final class PlayerRuntime extends Runtime implements CgAnimAssetPlayer {

        private final CgSequencePlayerNode node;
        private final CgMarkerTickRecord markers = new CgMarkerTickRecord();
        @Nullable
        private CgAnimSequence clip;
        private boolean loop, fullWeight;
        private float rate, rateNow, time, previous, lastDelta, weight;

        PlayerRuntime(CgSequencePlayerNode node) {
            this.node = node;
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            clip = node.clip;
            loop = node.loop;
            rate = node.playRate;
            time = previous = node.startPosition;
            lastDelta = 0f;
            fullWeight = false;
            markers.reset();
        }

        /** Plays {@code clip} from its start, looping or not: the single node graph's {@code PlayAnimation}. */
        public PlayerRuntime play(@Nullable CgAnimSequence clip, boolean loop) {
            this.clip = clip;
            this.loop = loop;
            this.time = previous = 0f;
            markers.reset();
            return this;
        }

        /** Moves its time to {@code seconds}: the next pose is the clip's there ({@code SetAccumulatedTime}). */
        public PlayerRuntime position(float seconds) {
            time = previous = seconds;
            markers.reset();
            return this;
        }

        public PlayerRuntime rate(float rate) {
            this.rate = rate;
            return this;
        }

        @Nullable
        public CgAnimSequence clip() {
            return clip;
        }

        /** Its time, seconds. */
        public float time() {
            return time;
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            this.weight = weight;
            fullWeight |= weight >= 1f - CgAnimPose.ZERO_WEIGHT;
            rateNow = node.playRateVariable != null ? g.get(node.playRateVariable) : rate;
            if (clip != null && weight > CgAnimPose.ZERO_WEIGHT) g.record(this, weight, node.group, fullWeight);
        }

        @Override
        public void tick(CgAnimTickContext ctx, boolean canUseMarkerSync) {
            CgAnimSequence c = clip;
            if (c == null) return;
            float length = c.duration(), t = time;
            if (ctx.resync()) t = ctx.ratio() * length;
            float before = t, delta = 0f;
            if (ctx.leader()) {
                delta = rateNow * ctx.delta();
                ctx.leaderDelta(delta);
                ctx.previousRatio(length > 0f ? before / length : 0f);
                if (delta != 0f) {
                    if (canUseMarkerSync && ctx.canUseMarkerPosition()) {
                        t = CgMarkerSync.tickByMarkerAsLeader(c, markers, ctx, t, delta, loop);
                        before = ctx.previousTime();
                    } else {
                        t = CgAnimTickContext.advanceTime(loop, delta, t, length);
                    }
                } else if (canUseMarkerSync && ctx.canUseMarkerPosition() && !markers.valid(loop)) {
                    CgMarkerSync.indicesForTime(c, t, loop, ctx, markers);
                }
                ctx.ratio(length > 0f ? t / length : 0f);
            } else {
                if (canUseMarkerSync) {
                    if (ctx.canUseMarkerPosition() && ctx.start().valid()) {
                        t = CgMarkerSync.tickByMarkerAsFollower(c, markers, ctx, t, ctx.leaderDelta(), loop);
                        before = ctx.previousTime();
                    } else {
                        delta = rateNow * ctx.delta();
                        t = CgAnimTickContext.advanceTime(loop, delta, t, length);
                    }
                } else {
                    before = ctx.previousRatio() * length;
                    t = ctx.ratio() * length;
                }
                if (t != before) {
                    delta = t - before;
                    // Against the play rate: it wrapped
                    if (delta * rateNow < 0f) delta += Math.signum(rateNow) * length;
                }
            }
            time = t;
            previous = before;
            lastDelta = delta;
        }

        @Override
        public void forEachSample(float weight, SampleSink sink) {
            if (clip != null) sink.sample(clip, time, lastDelta, weight);
        }

        @Override
        @Nullable
        public String[] uniqueMarkers() {
            return clip == null ? null : clip.uniqueMarkerNames();
        }

        @Override
        @Nullable
        public Object asset() {
            return clip;
        }

        @Override
        public CgMarkerTickRecord markerRecord() {
            return markers;
        }

        @Override
        public float weight() {
            return weight;
        }

        @Override
        public float playLength() {
            return clip == null ? 0f : clip.duration();
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

        /** Its play rate this frame: the variable's where it reads one. */
        @Override
        public float playRate() {
            return rateNow;
        }

        @Override
        public float currentAssetLength() {
            return playLength();
        }

        @Override
        public float currentAssetTimePlayRateAdjusted() {
            return rateNow < 0f ? playLength() - time : time;
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (clip == null) {
                out.rest();
            } else if (clip.settings().additive().isAdditive()) {
                CgAnimPose base = g.borrow();
                clip.sampleAdditive(out, base, time);
                g.giveBack(base);
            } else {
                out.rest();
                clip.sample(out, time, g.extractsRootMotion());
            }
        }
    }
}
