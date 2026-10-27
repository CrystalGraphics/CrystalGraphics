package com.crystalgraphics.api.animation;

import java.util.Objects;

/**
 * A clip placed on a timeline: when it plays, how fast, which part of it, and how its weight eases in and out.
 * Unreal's skeletal animation section ({@code FMovieSceneSkeletalAnimationParams}): the same fields, mapped to the
 * clip's time and weighted as Sequencer maps and weighs one.
 *
 * <pre>{@code
 * CgAnimSection roar = CgAnimSection.of(kurama.sequence("roar")).at(2.0).build();     // 2 s on, for its length
 * CgAnimSection idle = CgAnimSection.of(kurama.sequence("idle"))
 *         .at(0.0).until(Double.POSITIVE_INFINITY)                                   // loops for ever
 *         .playRate(0.8f).easeOut(0.25f)
 *         .build();
 * instance.play(idle).play(roar);                                                     // roar cross-fades over idle
 * }</pre>
 *
 * <ul>
 *   <li>Immutable; any thread. Times are seconds on the timeline of whoever plays it ({@link CgAnimInstance}).</li>
 *   <li>A section outlasting its clip loops, else it holds its last frame, as Unreal decides
 *       (MovieSceneSkeletalAnimationSection.cpp:68-72); {@link Builder#loop()} forces a loop.</li>
 *   <li>{@link #animationTime} and {@link #weight} are pure functions of time: a seek lands where playing reaches.</li>
 * </ul>
 */
public final class CgAnimSection {

    private final CgAnimSequence sequence;
    private final double start, end;
    private final float playRate, startOffset, endOffset, firstLoopOffset, weight, easeIn, easeOut;
    private final boolean reverse, loop;
    private final CgAnimEasing easeInCurve, easeOutCurve;

    private CgAnimSection(Builder b) {
        this.sequence = b.sequence;
        this.start = b.start;
        this.end = Double.isNaN(b.end) ? b.start + b.sequence.duration() / Math.abs(b.playRate) : b.end;
        this.playRate = b.playRate;
        this.startOffset = b.startOffset;
        this.endOffset = b.endOffset;
        this.firstLoopOffset = b.firstLoopOffset;
        this.weight = b.weight;
        this.easeIn = b.easeIn;
        this.easeOut = b.easeOut;
        this.reverse = b.reverse;
        this.easeInCurve = b.easeInCurve;
        this.easeOutCurve = b.easeOutCurve;
        // Unreal loops a section whose span, with its offsets, passes the clip's length.
        this.loop = b.forceLoop || end - start + firstLoopOffset + startOffset + endOffset > sequence.duration();
    }

    /** A section of {@code sequence} from time 0, for the clip's length at play rate 1. */
    public static Builder of(CgAnimSequence sequence) {
        return new Builder(Objects.requireNonNull(sequence, "sequence"));
    }

    public CgAnimSequence sequence() {
        return sequence;
    }

    /** When it starts on the timeline, seconds. */
    public double start() {
        return start;
    }

    /** When it ends on the timeline, seconds, excluded; may be infinite. */
    public double end() {
        return end;
    }

    /** Whether the timeline at {@code time} is within it. */
    public boolean contains(double time) {
        return time >= start && time < end;
    }

    public boolean loops() {
        return loop;
    }

    /**
     * The clip's time at timeline {@code time}: Unreal's {@code MakeTransform}
     * (MovieSceneSectionTimingParameters.cpp:12-60, 97-122). Section-local seconds times the play rate, plus the start
     * and first-loop offsets (and the played span for a negative rate), looped over the played span or clamped to it,
     * then reversed.
     */
    public float animationTime(double time) {
        double first = startOffset, last = sequence.duration() - endOffset, span = last - first;
        if (span <= 0) return (float) Math.max(first, 0);
        double t = (time - start) * playRate + startOffset + firstLoopOffset;
        if (playRate < 0) t += span;
        if (loop) {
            t = first + floorMod(t - first, span);
        } else {
            t = Math.max(first, Math.min(last, t));
        }
        // Reversed within the played span: where it begins at 0 this is Unreal's (span - t).
        if (reverse) t = first + last - t;
        return (float) t;
    }

    /**
     * How much it counts at timeline {@code time}: its weight times its ease-in and ease-out (Sequencer's
     * {@code Weight * EvaluateEasing}, MovieSceneSkeletalAnimationSystem.cpp:552-557, MovieSceneSection.cpp:1010-1056);
     * 0 outside it.
     */
    public float weight(double time) {
        if (!contains(time)) return 0f;
        float in = 1f, out = 1f;
        if (easeIn > 0f) {
            double x = (time - start) / easeIn;
            in = x > 1 ? 1f : easeInCurve.evaluate((float) x);
        }
        if (easeOut > 0f && !Double.isInfinite(end)) {
            double x = (time - (end - easeOut)) / easeOut;
            out = x < 0 ? 1f : x > 1 ? 0f : 1f - easeOutCurve.evaluate((float) x);
        }
        return weight * in * out;
    }

    private static double floorMod(double x, double m) {
        double r = x % m;
        return r < 0 ? r + m : r;
    }

    @Override
    public String toString() {
        return "CgAnimSection(" + sequence.name() + " " + start + ".." + end + ")";
    }

    /** A section's settings; {@link #build()} makes the section. */
    public static final class Builder {

        private final CgAnimSequence sequence;
        private double start, end = Double.NaN;
        private float playRate = 1f, startOffset, endOffset, firstLoopOffset, weight = 1f, easeIn, easeOut;
        private boolean reverse, forceLoop;
        private CgAnimEasing easeInCurve = CgAnimEasing.CUBIC_IN_OUT, easeOutCurve = CgAnimEasing.CUBIC_IN_OUT;

        private Builder(CgAnimSequence sequence) {
            this.sequence = sequence;
        }

        /** Starts at timeline {@code seconds}. */
        public Builder at(double seconds) {
            start = seconds;
            return this;
        }

        /** Ends at timeline {@code seconds}, excluded: past the clip's length it loops. Default: the clip once. */
        public Builder until(double seconds) {
            end = seconds;
            return this;
        }

        /** Clip seconds a timeline second; negative plays it backwards from its end. Not 0. */
        public Builder playRate(float rate) {
            if (rate == 0f || !Float.isFinite(rate)) throw new IllegalArgumentException("play rate " + rate);
            playRate = rate;
            return this;
        }

        /** Clip seconds skipped at the start of every loop. */
        public Builder startOffset(float seconds) {
            startOffset = Math.max(0f, seconds);
            return this;
        }

        /** Clip seconds left off the end of every loop. */
        public Builder endOffset(float seconds) {
            endOffset = Math.max(0f, seconds);
            return this;
        }

        /** Clip seconds the first loop starts into. */
        public Builder firstLoopOffset(float seconds) {
            firstLoopOffset = Math.max(0f, seconds);
            return this;
        }

        public Builder reverse() {
            reverse = true;
            return this;
        }

        /** Loops however long it lasts. */
        public Builder loop() {
            forceLoop = true;
            return this;
        }

        /** Its weight before easing, 0 to 1. */
        public Builder weight(float weight) {
            this.weight = Math.max(0f, Math.min(1f, weight));
            return this;
        }

        /** Its weight rises over its first {@code seconds}, along {@link CgAnimEasing#CUBIC_IN_OUT}. */
        public Builder easeIn(float seconds) {
            return easeIn(seconds, CgAnimEasing.CUBIC_IN_OUT);
        }

        public Builder easeIn(float seconds, CgAnimEasing curve) {
            easeIn = Math.max(0f, seconds);
            easeInCurve = Objects.requireNonNull(curve, "curve");
            return this;
        }

        /** Its weight falls over its last {@code seconds}, along {@link CgAnimEasing#CUBIC_IN_OUT}. */
        public Builder easeOut(float seconds) {
            return easeOut(seconds, CgAnimEasing.CUBIC_IN_OUT);
        }

        public Builder easeOut(float seconds, CgAnimEasing curve) {
            easeOut = Math.max(0f, seconds);
            easeOutCurve = Objects.requireNonNull(curve, "curve");
            return this;
        }

        public CgAnimSection build() {
            if (!Double.isNaN(end) && end <= start) throw new IllegalArgumentException("ends at " + end + ", not after its start " + start);
            if (startOffset + endOffset >= sequence.duration() && sequence.duration() > 0) {
                throw new IllegalArgumentException("offsets " + startOffset + " and " + endOffset + " leave nothing of " + sequence.name());
            }
            return new CgAnimSection(this);
        }
    }
}
