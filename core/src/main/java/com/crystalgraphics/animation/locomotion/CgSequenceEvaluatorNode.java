package com.crystalgraphics.animation.locomotion;

import java.util.List;
import java.util.Locale;
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
import com.crystalgraphics.animation.sync.CgMarkerTickRecord;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A clip held at a time the graph sets each frame rather than one that runs: Unreal's
 * {@code FAnimNode_SequenceEvaluator}. The time is a constant or a variable's, in seconds or as a share of the clip, so
 * a game state drives the pose (Minecraft's swing progress plays the swing clip); or it is found by distance matching:
 * a start advanced by the ground covered, a stop placed by the ground still to cover ({@link CgDistanceMatching}).
 *
 * <pre>{@code
 * CgSequenceEvaluatorNode.of(AIM).explicitTime(AIM_TIME)                       // seconds, set by the game
 * CgSequenceEvaluatorNode.of(SWING).explicitTime(SWING_PROGRESS).normalized()  // 0-1 across the clip
 * CgSequenceEvaluatorNode.of(KNEEL).at(0.4f)                                   // one frame, held
 * CgSequenceEvaluatorNode.of(RUN_START).advanceByDistance(TRAVELLED, 0.75f, 1.25f)   // metres this frame; rate kept within
 * CgSequenceEvaluatorNode.of(RUN_STOP).matchToTarget(TO_STOP)                  // metres still to go
 * }</pre>
 *
 * <p>In a file, {@code time} is a number or a float variable's name, {@code normalized} makes it a share of the clip;
 * the distance modes read {@code distance}:</p>
 * <pre>{@code
 * swing: { kind: sequence_evaluator, clip: mymod:hero/swing, time: swing, normalized: true }
 * stop:  { kind: sequence_evaluator, clip: mymod:hero/run_stop, mode: match_to_target, distance: to_stop }
 * }</pre>
 *
 * <ul>
 *   <li>Distance matching reads the clip's {@link CgClipModifiers#DISTANCE} curve unless {@link #curve} names
 *       another; a clip without it fails when an instance binds the graph, naming it.</li>
 *   <li>It records itself as a player, so its curves land and its root motion is extracted over each frame's move; it
 *       is never in a sync group.</li>
 *   <li>Its time starts at 0 each time it becomes relevant again ({@code initialize}).</li>
 * </ul>
 */
public final class CgSequenceEvaluatorNode extends CgAnimNode {

    /** How its time is found each frame. */
    public enum Mode {
        /** The variable's, in seconds. */
        EXPLICIT,
        /** Advanced by the variable's metres travelled this frame along the distance curve. */
        ADVANCE_BY_DISTANCE,
        /** Where the distance curve reaches minus the variable's metres to go. */
        MATCH_TO_TARGET
    }

    /** Reads {@code kind: sequence_evaluator}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        String clipId = c.string(at, m, "clip");
        CgSequenceEvaluatorNode n = of(c.clip(at + ".clip", clipId), clipId);
        if (Boolean.TRUE.equals(m.get("loop"))) n = n.loop();
        if (m.get("curve") instanceof String curve) n = n.curve(curve);
        Object mode = m.get("mode");
        String name = mode == null ? "explicit" : String.valueOf(mode);
        if (name.equals("explicit")) {
            Object time = m.get("time");
            if (time instanceof String var) n = n.explicitTime(floatVariable(c, at + ".time", var));
            else if (time != null) n = n.at(c.number(at + ".time", time));
            return Boolean.TRUE.equals(m.get("normalized")) ? n.normalized() : n;
        }
        CgAnimVariable<Float> v = floatVariable(c, at + ".distance", c.string(at, m, "distance"));
        return switch (name) {
            case "advance_by_distance" -> {
                float min = -1f, max = -1f;
                if (m.get("play_rate_clamp") instanceof List<?> l && l.size() == 2) {
                    min = c.number(at + ".play_rate_clamp[0]", l.get(0));
                    max = c.number(at + ".play_rate_clamp[1]", l.get(1));
                }
                yield n.advanceByDistance(v, min, max);
            }
            case "match_to_target" -> n.matchToTarget(v);
            default -> throw new CgAssetException(at + ".mode: " + name + " is not explicit, advance_by_distance or match_to_target");
        };
    };

    @SuppressWarnings("unchecked")
    private static CgAnimVariable<Float> floatVariable(CgAnimNodeKind.Context c, String at, String name) {
        CgAnimVariable<?> v = c.variable(at, name);
        if (v.type() != CgAnimVariable.Type.FLOAT) throw new CgAssetException(at + ": " + name + " is not a float");
        return (CgAnimVariable<Float>) v;
    }

    private final CgAnimSequence clip;
    private final String clipId;
    private final boolean loop;
    private final Mode mode;
    @Nullable
    private final CgAnimVariable<Float> input;
    private final String curve;
    private final float minRate, maxRate;
    /** The explicit time where no variable sets it. */
    private final float constant;
    /** Whether the explicit time is a share of the clip. */
    private final boolean normalized;

    private CgSequenceEvaluatorNode(CgAnimSequence clip, String clipId, boolean loop, Mode mode, @Nullable CgAnimVariable<Float> input,
                                    String curve, float minRate, float maxRate, float constant, boolean normalized) {
        this.constant = constant;
        this.normalized = normalized;
        this.clip = Objects.requireNonNull(clip, "clip");
        this.clipId = clipId;
        this.loop = loop;
        this.mode = mode;
        this.input = input;
        this.curve = curve;
        this.minRate = minRate;
        this.maxRate = maxRate;
    }

    /** {@code clip} held at its start until a mode sets its time. */
    public static CgSequenceEvaluatorNode of(CgAnimSequence clip) {
        return new CgSequenceEvaluatorNode(clip, clip.name(), false, Mode.EXPLICIT, null, CgClipModifiers.DISTANCE, -1f, -1f, 0f, false);
    }

    static CgSequenceEvaluatorNode of(CgAnimSequence clip, String id) {
        return new CgSequenceEvaluatorNode(clip, id, false, Mode.EXPLICIT, null, CgClipModifiers.DISTANCE, -1f, -1f, 0f, false);
    }

    /** Its time wraps past its end. */
    public CgSequenceEvaluatorNode loop() {
        return new CgSequenceEvaluatorNode(clip, clipId, true, mode, input, curve, minRate, maxRate, constant, normalized);
    }

    /** The distance curve it matches by. */
    public CgSequenceEvaluatorNode curve(String name) {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, mode, input, name, minRate, maxRate, constant, normalized);
    }

    /** Held at {@code time}: seconds, or a share of the clip where {@link #normalized}. */
    public CgSequenceEvaluatorNode at(float time) {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, Mode.EXPLICIT, null, curve, minRate, maxRate, time, normalized);
    }

    /** Its explicit time is a share of the clip, 0 its start and 1 its end, rather than seconds. */
    public CgSequenceEvaluatorNode normalized() {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, mode, input, curve, minRate, maxRate, constant, true);
    }

    /** Its time, seconds (or a share, {@link #normalized}), read from {@code seconds} each frame. */
    public CgSequenceEvaluatorNode explicitTime(CgAnimVariable<Float> seconds) {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, Mode.EXPLICIT, Objects.requireNonNull(seconds, "seconds"), curve, minRate, maxRate, constant, normalized);
    }

    /**
     * Advanced each frame by {@code travelled} metres along its curve, the resulting rate held within [{@code min},
     * {@code max}] where {@code 0 <= min < max} (a start's first steps never too slow nor too fast).
     */
    public CgSequenceEvaluatorNode advanceByDistance(CgAnimVariable<Float> travelled, float min, float max) {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, Mode.ADVANCE_BY_DISTANCE, Objects.requireNonNull(travelled, "travelled"),
                curve, min, max, constant, normalized);
    }

    /** Placed each frame where its curve stands {@code toGo} metres short of its stop. */
    public CgSequenceEvaluatorNode matchToTarget(CgAnimVariable<Float> toGo) {
        return new CgSequenceEvaluatorNode(clip, clipId, loop, Mode.MATCH_TO_TARGET, Objects.requireNonNull(toGo, "toGo"), curve, minRate, maxRate, constant, normalized);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        if (input != null) each.accept(input);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        if (mode != Mode.EXPLICIT && clip.curve(curve) < 0) {
            throw new IllegalArgumentException(clip.name() + " has no distance curve " + curve + " to match by");
        }
        return new EvaluatorRuntime();
    }

    private final class EvaluatorRuntime extends Runtime implements CgAnimAssetPlayer {
        private final CgMarkerTickRecord markers = new CgMarkerTickRecord();
        private float time, previous, lastDelta, weight;

        @Override
        public void initialize(CgAnimGraphInstance g) {
            time = previous = lastDelta = 0f;
        }

        @Override
        public void update(CgAnimGraphInstance g, float w) {
            weight = w;
            float length = clip.duration(), dt = g.delta(), next = time;
            if (mode == Mode.EXPLICIT) {
                float value = input != null ? g.get(input) : constant;
                next = normalized ? value * length : value;
            } else {
                float value = g.get(input);
                switch (mode) {
                    case MATCH_TO_TARGET -> next = CgDistanceMatching.timeAtDistance(clip, -value, curve);
                    case ADVANCE_BY_DISTANCE -> {
                        if (dt > 0f && value > 0f) {
                            float after = CgDistanceMatching.timeAfterDistanceTravelled(clip, time, value, curve, loop);
                            if (after < time) after += length;
                            float rate = (after - time) / dt;
                            if (minRate >= 0f && minRate < maxRate) rate = Math.max(minRate, Math.min(maxRate, rate));
                            next = CgAnimTickContext.advanceTime(loop, rate * dt, time, length);
                        }
                    }
                }
            }
            if (loop && length > 0f) {
                next %= length;
                if (next < 0f) next += length;
            } else {
                next = Math.max(0f, Math.min(length, next));
            }
            previous = time;
            float moved = next - time;
            if (loop && mode == Mode.ADVANCE_BY_DISTANCE && moved < 0f) moved += length;
            lastDelta = moved;
            time = next;
            if (w > CgAnimPose.ZERO_WEIGHT) g.record(this, w, null, false);
        }

        @Override
        public void tick(CgAnimTickContext ctx, boolean canUseMarkerSync) {
            float length = clip.duration();
            if (ctx.leader()) {
                ctx.leaderDelta(lastDelta);
                ctx.previousRatio(length > 0f ? previous / length : 0f);
                ctx.ratio(length > 0f ? time / length : 0f);
            }
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            out.rest();
            clip.sample(out, time, g.extractsRootMotion());
        }

        @Override
        public void forEachSample(float w, SampleSink sink) {
            sink.sample(clip, time, lastDelta, w);
        }

        @Override
        @Nullable
        public String[] uniqueMarkers() {
            return null;
        }

        @Override
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
            return clip.duration();
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
            return 1f;
        }

        @Override
        public float currentAssetLength() {
            return clip.duration();
        }

        @Override
        public float currentAssetTimePlayRateAdjusted() {
            return time;
        }
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("sequence_evaluator").put("clip", clipId);
        if (loop) out.put("loop", true);
        if (mode != Mode.EXPLICIT) out.put("mode", mode.name().toLowerCase(Locale.ROOT));
        if (mode == Mode.EXPLICIT) out.alpha("time", constant, input, 0f);
        else out.put("distance", input.name());
        if (normalized) out.put("normalized", true);
        if (!curve.equals(CgClipModifiers.DISTANCE)) out.put("curve", curve);
        if (minRate >= 0f && minRate < maxRate) out.put("play_rate_clamp", List.of((double) minRate, (double) maxRate));
    }

    @Override
    public String toString() {
        return "evaluator(" + clip.name() + ", " + mode + ")";
    }
}
