package com.crystalgraphics.render.post.volume;

import java.util.ArrayList;
import java.util.List;

/**
 * An impact frame as anime draws one: a few beats, each a {@link CgImpactFrame} held for whole frames at an animation
 * rate, then cut to the next, never faded. An effect reads the frame and its seed for the time since its hit each tick
 * and sets them on a volume at full weight.
 *
 * <pre>{@code
 * static final CgImpactSequence HIT = CgImpactSequence.at(24f)
 *         .beat(CgImpact.SUBJECT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(CgImpact.FOCUS_LINES, 4)
 *         .beat(CgImpact.WHITE, 1).build();
 *
 * CgImpactFrame look = HIT.look(sinceHit);              // null before 0 and once it is over
 * impact.weight(look != null ? 1f : 0f);
 * if (look != null) settings.impact(look, HIT.amount(sinceHit), HIT.seed(sinceHit));
 * }</pre>
 *
 * {@code fadeOut} holds the last beat past the end, its amount stepping down a frame at a time; the picture moves under
 * it. Anime more often cuts from white to the result already grown, which an effect running on beneath its beats gets
 * without one.
 *
 * <pre>{@code
 * CgImpactSequence.at(24f).beat(CgImpact.FOCUS_LINES, 4).beat(CgImpact.WHITE, 1).fadeOut(4).build();
 * // WHITE at 1, then 0.8, 0.6, 0.4, 0.2 of the way over the picture, a frame each
 * }</pre>
 *
 * <ul>
 *   <li>The seed changes each beat, and every {@link CgImpactFrame#boil()} frames inside one: a held scene still reads
 *       as drawn anew.</li>
 *   <li>Immutable once built; {@link #look} and {@link #seed} allocate nothing.</li>
 *   <li>{@link #seconds()} is its beats' length; a fade comes after.</li>
 * </ul>
 */
public final class CgImpactSequence {

    private final float fps;
    private final CgImpactFrame[] looks;
    /** The frame each beat ends at, counted from the first. */
    private final int[] ends;
    /** Frames the last beat fades over after the end. */
    private final int fade;

    private CgImpactSequence(float fps, CgImpactFrame[] looks, int[] ends, int fade) {
        this.fps = fps;
        this.looks = looks;
        this.ends = ends;
        this.fade = fade;
    }

    /** A sequence held at {@code fps} frames a second: 24 is anime's on ones, 12 on twos. */
    public static Builder at(float fps) {
        if (!(fps > 0f)) throw new IllegalArgumentException("fps must be positive: " + fps);
        return new Builder(fps);
    }

    /** The frame drawn {@code seconds} into it, the last through its fade, or null before it starts and once it is over. */
    public CgImpactFrame look(float seconds) {
        int beat = beat(seconds);
        return beat < 0 ? null : looks[beat];
    }

    /** How far over the picture it is {@code seconds} in: 1 through the beats, stepping down through the fade, else 0. */
    public float amount(float seconds) {
        if (beat(seconds) < 0) return 0f;
        int past = (int) (seconds * fps) - frames();
        return past < 0 ? 1f : 1f - (past + 1f) / (fade + 1f);
    }

    /** The drawing's seed {@code seconds} into it: new each beat and each boil, the last beat's through the fade; 0 outside it. */
    public int seed(float seconds) {
        int beat = beat(seconds);
        if (beat < 0) return 0;
        int boil = looks[beat].boil(), into = Math.min((int) (seconds * fps), ends[beat] - 1) - (beat == 0 ? 0 : ends[beat - 1]);
        return 1 + beat * 64 + (boil > 0 ? into / boil : 0);
    }

    private int beat(float seconds) {
        if (!(seconds >= 0f)) return -1;
        int frame = (int) (seconds * fps);
        for (int i = 0; i < ends.length; i++) {
            if (frame < ends[i]) return i;
        }
        return frame < frames() + fade ? ends.length - 1 : -1;
    }

    /** How long its beats last: what a hitstop holds for. */
    public float seconds() {
        return frames() / fps;
    }

    /** How long the last beat fades for after {@link #seconds()}. */
    public float fadeSeconds() {
        return fade / fps;
    }

    /** Frames the last beat fades over. */
    public int fadeFrames() {
        return fade;
    }

    /** Its frames, every beat's together; frame {@code k} starts {@code k / fps()} seconds in. */
    public int frames() {
        return ends.length == 0 ? 0 : ends[ends.length - 1];
    }

    public float fps() {
        return fps;
    }

    public static final class Builder {
        private final float fps;
        private final List<CgImpactFrame> looks = new ArrayList<>();
        private final List<Integer> frames = new ArrayList<>();
        private int fade;

        private Builder(float fps) {
            this.fps = fps;
        }

        /** Holds the last beat {@code frames} more frames past the end, its amount stepping down to nothing; 0 cuts. */
        public Builder fadeOut(int frames) {
            if (frames < 0) throw new IllegalArgumentException("a fade is zero frames or more: " + frames);
            fade = frames;
            return this;
        }

        /** Holds {@code look} for {@code frames} frames, one or more. */
        public Builder beat(CgImpactFrame look, int frames) {
            if (look == null) throw new IllegalArgumentException("look");
            if (frames < 1) throw new IllegalArgumentException("a beat holds one frame or more: " + frames);
            looks.add(look);
            this.frames.add(frames);
            return this;
        }

        /** Holds preset {@code look} for {@code frames} frames. */
        public Builder beat(CgImpact look, int frames) {
            if (look == null) throw new IllegalArgumentException("look");
            return beat(look.frame(), frames);
        }

        public CgImpactSequence build() {
            int[] ends = new int[frames.size()];
            int total = 0;
            for (int i = 0; i < ends.length; i++) ends[i] = total += frames.get(i);
            if (ends.length == 0 && fade > 0) throw new IllegalStateException("a fade needs a beat to fade");
            return new CgImpactSequence(fps, looks.toArray(new CgImpactFrame[0]), ends, fade);
        }
    }
}
