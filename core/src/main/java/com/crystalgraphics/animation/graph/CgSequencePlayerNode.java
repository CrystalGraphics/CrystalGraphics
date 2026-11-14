package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * A clip playing: Unreal's {@code FAnimNode_SequencePlayer}. Its time moves by the frame's delta times its play rate,
 * after the graph's walk, wrapping when it loops and holding its last frame when it does not
 * ({@code FAnimationRuntime::AdvanceTime}, AnimationRuntime.cpp:1562-1600).
 *
 * <pre>{@code
 * CgSequencePlayerNode idle = CgSequencePlayerNode.of(IDLE).loop();
 * CgSequencePlayerNode land = CgSequencePlayerNode.of(LAND);                  // once, then held at its end
 * CgSequencePlayerNode jog = CgSequencePlayerNode.of(JOG).loop().playRate(1.2f).startPosition(0.25f);
 * }</pre>
 *
 * <ul>
 *   <li>An additive clip ({@code CgClipSettings.additive}) plays as differences, for an apply additive node to add.</li>
 *   <li>Its time moves only while it is relevant: a player blended out keeps its place, and starts again from it,
 *       as Unreal's does without a sync group.</li>
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

    private CgSequencePlayerNode(@Nullable CgAnimSequence clip, @Nullable String clipId, boolean loop, float playRate,
                                 float startPosition) {
        this.clip = clip;
        this.clipId = clipId;
        this.loop = loop;
        this.playRate = playRate;
        this.startPosition = startPosition;
    }

    /** {@code clip}, once from its start at rate 1. */
    public static CgSequencePlayerNode of(CgAnimSequence clip) {
        return new CgSequencePlayerNode(Objects.requireNonNull(clip, "clip"), clip.name(), false, 1f, 0f);
    }

    /** {@code clip}, which data names {@code id}: what the codec builds. */
    static CgSequencePlayerNode of(CgAnimSequence clip, String id) {
        return new CgSequencePlayerNode(Objects.requireNonNull(clip, "clip"), id, false, 1f, 0f);
    }

    /** The single node graph's player: no clip until the instance plays one. */
    static CgSequencePlayerNode single() {
        return new CgSequencePlayerNode(null, null, true, 1f, 0f);
    }

    /** Wrapping at its end. */
    public CgSequencePlayerNode loop() {
        return new CgSequencePlayerNode(clip, clipId, true, playRate, startPosition);
    }

    /** Its seconds a second of play; negative plays backwards. */
    public CgSequencePlayerNode playRate(float rate) {
        return new CgSequencePlayerNode(clip, clipId, loop, rate, startPosition);
    }

    /** Where it starts, seconds. */
    public CgSequencePlayerNode startPosition(float seconds) {
        return new CgSequencePlayerNode(clip, clipId, loop, playRate, seconds);
    }

    @Nullable
    public CgAnimSequence clip() {
        return clip;
    }

    public boolean loops() {
        return loop;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new PlayerRuntime(this);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        if (clip == null) throw new UnsupportedOperationException("the single node graph's player has no data form");
        out.kind("sequence_player").put("clip", clipId);
        if (loop) out.put("loop", true);
        if (playRate != 1f) out.put("play_rate", (double) playRate);
        if (startPosition != 0f) out.put("start_position", (double) startPosition);
    }

    @Override
    public String toString() {
        return "player(" + (clip == null ? "single" : clip.name()) + ")";
    }

    /** A player's state: its clip and time. The single node graph's is set by {@code CgAnimInstance.play}. */
    public static final class PlayerRuntime extends Runtime {

        private final CgSequencePlayerNode node;
        @Nullable
        private CgAnimSequence clip;
        private boolean loop, wrapped;
        private float rate, time;

        PlayerRuntime(CgSequencePlayerNode node) {
            this.node = node;
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            clip = node.clip;
            loop = node.loop;
            rate = node.playRate;
            time = node.startPosition;
        }

        /** Plays {@code clip} from its start, looping or not: the single node graph's {@code PlayAnimation}. */
        public PlayerRuntime play(@Nullable CgAnimSequence clip, boolean loop) {
            this.clip = clip;
            this.loop = loop;
            this.time = 0f;
            return this;
        }

        /** Moves its time to {@code seconds}: the next pose is the clip's there. */
        public PlayerRuntime position(float seconds) {
            time = seconds;
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

        public boolean loops() {
            return loop;
        }

        /** Its play rate. */
        public float rate() {
            return rate;
        }

        /** Whether its last tick wrapped past an end of its looping clip: Unreal's looped-last-frame check. */
        public boolean wrapped() {
            return wrapped;
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            if (clip != null && weight > CgAnimPose.ZERO_WEIGHT) g.tick(this, clip, weight);
        }

        /** {@code FAnimationRuntime::AdvanceTime}: wrapped by {@code fmod} when looping, else clamped to its length. */
        void tick(float delta) {
            wrapped = false;
            if (clip == null) return;
            float length = clip.duration(), next = time + delta * rate;
            if (next < 0f || next > length) {
                if (loop) {
                    wrapped = true;
                    if (length != 0f) {
                        next %= length;
                        if (next < 0f) next += length;
                    } else {
                        next = 0f;
                    }
                } else {
                    next = Math.max(0f, Math.min(length, next));
                }
            }
            time = next;
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
                clip.sample(out, time);
            }
        }
    }
}
