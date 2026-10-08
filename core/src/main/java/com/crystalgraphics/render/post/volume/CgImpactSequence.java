package com.crystalgraphics.render.post.volume;

import java.util.ArrayList;
import java.util.List;

/**
 * An impact frame as anime draws one: a few beats, each a {@link CgImpact} look held for whole frames at an animation
 * rate, then cut to the next, never faded. An effect reads the look for the time since its hit each tick and sets it on
 * a volume at full weight.
 *
 * <pre>{@code
 * static final CgImpactSequence HIT = CgImpactSequence.at(24f)
 *         .beat(CgImpact.SUBJECT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(CgImpact.SUBJECT_LINES, 2).build();
 *
 * CgImpact look = HIT.look(sinceHit);                  // null before 0 and once it is over
 * impact.weight(look != null ? 1f : 0f);
 * if (look != null) settings.impact(look, 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Immutable once built; {@link #look} allocates nothing.</li>
 *   <li>Its length, {@link #seconds()}, is what a hitstop round it should hold for.</li>
 * </ul>
 */
public final class CgImpactSequence {

    private final float fps;
    private final CgImpact[] looks;
    /** The frame each beat ends at, counted from the first. */
    private final int[] ends;

    private CgImpactSequence(float fps, CgImpact[] looks, int[] ends) {
        this.fps = fps;
        this.looks = looks;
        this.ends = ends;
    }

    /** A sequence held at {@code fps} frames a second: 24 is anime's on ones, 12 on twos. */
    public static Builder at(float fps) {
        if (!(fps > 0f)) throw new IllegalArgumentException("fps must be positive: " + fps);
        return new Builder(fps);
    }

    /** The look {@code seconds} into it, or null before it starts and once it is over. */
    public CgImpact look(float seconds) {
        if (!(seconds >= 0f)) return null;
        int frame = (int) (seconds * fps);
        for (int i = 0; i < ends.length; i++) {
            if (frame < ends[i]) return looks[i];
        }
        return null;
    }

    /** How long it lasts. */
    public float seconds() {
        return ends.length == 0 ? 0f : ends[ends.length - 1] / fps;
    }

    public static final class Builder {
        private final float fps;
        private final List<CgImpact> looks = new ArrayList<>();
        private final List<Integer> frames = new ArrayList<>();

        private Builder(float fps) {
            this.fps = fps;
        }

        /** Holds {@code look} for {@code frames} frames, one or more. */
        public Builder beat(CgImpact look, int frames) {
            if (look == null) throw new IllegalArgumentException("look");
            if (frames < 1) throw new IllegalArgumentException("a beat holds one frame or more: " + frames);
            looks.add(look);
            this.frames.add(frames);
            return this;
        }

        public CgImpactSequence build() {
            int[] ends = new int[frames.size()];
            int total = 0;
            for (int i = 0; i < ends.length; i++) ends[i] = total += frames.get(i);
            return new CgImpactSequence(fps, looks.toArray(new CgImpact[0]), ends);
        }
    }
}
