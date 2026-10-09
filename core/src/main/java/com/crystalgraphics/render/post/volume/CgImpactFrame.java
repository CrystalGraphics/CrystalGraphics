package com.crystalgraphics.render.post.volume;

/**
 * One drawing of an impact frame: what the picture turns into for a beat of a hit. Most are drawn ({@link #drawn()}):
 * two tones, paper and ink, with the subject (what the hitting effect glows with) filled in ink or left the paper's
 * tone, and any of focus lines, hatching off the subject's edge and a star at the focus, all in ink. {@link CgImpact}'s
 * constants are the presets; a sequence of them is a {@link CgImpactSequence}.
 *
 * <pre>{@code
 * // the subject white on black, a flash star with its cross at the focus
 * static final CgImpactFrame HIT = CgImpactFrame.drawn().paper(CgImpactFrame.Tone.DARK).fillSubject(true)
 *         .star(0.05f).cross(true).build();
 *
 * // black focus lines on white, stopping at the subject, which is left white; a new set every two frames
 * static final CgImpactFrame LINES = CgImpactFrame.drawn().paper(CgImpactFrame.Tone.LIGHT).lines(1f).hatch(0.04f)
 *         .boil(2).build();
 *
 * // red on black rather than white on black
 * static final CgImpactFrame RED = HIT.toBuilder().light(1f, 0.1f, 0.05f).build();
 * }</pre>
 *
 * <ul>
 *   <li>Immutable; build them once, in static fields.</li>
 *   <li>Ink is the tone the paper is not: on dark paper every line, stroke and star is the light colour.</li>
 *   <li>Lines and hatching read the subject; with no effect glowing on screen they have nothing to stop at or follow.</li>
 * </ul>
 */
public final class CgImpactFrame {

    /** The two tones a drawn frame is made of. */
    public enum Tone { LIGHT, DARK }

    /** How the picture becomes the frame: the composite's look number, in this order. */
    enum Kind { NEGATIVE, CONTRAST, PICTURE_LINES, DRAWN }

    /** The picture's negative. */
    public static final CgImpactFrame NEGATIVE = new Builder(Kind.NEGATIVE).build();
    /** Stark black and white, split at mid-grey. */
    public static final CgImpactFrame CONTRAST = new Builder(Kind.CONTRAST).build();
    /** Dark focus lines over the picture, a new set every frame. */
    public static final CgImpactFrame PICTURE_LINES = new Builder(Kind.PICTURE_LINES).lines(1f).boil(1).build();

    final Kind kind;
    final Tone paper;
    final boolean fillSubject, cross;
    final float lines, hatch, star, jitter;
    final int boil;
    final float lightR, lightG, lightB, darkR, darkG, darkB;

    private CgImpactFrame(Builder b) {
        kind = b.kind;
        paper = b.paper;
        fillSubject = b.fillSubject;
        cross = b.cross;
        lines = b.lines;
        hatch = b.hatch;
        star = b.star;
        jitter = b.jitter;
        boil = b.boil;
        lightR = b.lightR;
        lightG = b.lightG;
        lightB = b.lightB;
        darkR = b.darkR;
        darkG = b.darkG;
        darkB = b.darkB;
    }

    /** A drawn frame: dark paper and nothing on it until a setter adds something. */
    public static Builder drawn() {
        return new Builder(Kind.DRAWN);
    }

    public Builder toBuilder() {
        Builder b = new Builder(kind);
        b.paper = paper;
        b.fillSubject = fillSubject;
        b.cross = cross;
        b.lines = lines;
        b.hatch = hatch;
        b.star = star;
        b.jitter = jitter;
        b.boil = boil;
        b.light(lightR, lightG, lightB).dark(darkR, darkG, darkB);
        return b;
    }

    /** Whether it reads the subject: a drawn frame does. */
    public boolean readsSubject() {
        return kind == Kind.DRAWN;
    }

    /** Frames each drawing holds within a beat before it is drawn anew; 0 holds one drawing the whole beat. */
    public int boil() {
        return boil;
    }

    /** The composite's look number: 0 negative, 1 contrast, 2 lines over the picture, 3 drawn. */
    public int look() {
        return kind.ordinal();
    }

    public Tone paper() {
        return paper;
    }

    public boolean fillSubject() {
        return fillSubject;
    }

    public boolean cross() {
        return cross;
    }

    public float lines() {
        return lines;
    }

    public float hatch() {
        return hatch;
    }

    public float star() {
        return star;
    }

    public float jitter() {
        return jitter;
    }

    public float lightR() {
        return lightR;
    }

    public float lightG() {
        return lightG;
    }

    public float lightB() {
        return lightB;
    }

    public float darkR() {
        return darkR;
    }

    public float darkG() {
        return darkG;
    }

    public float darkB() {
        return darkB;
    }

    public static final class Builder {
        private final Kind kind;
        private Tone paper = Tone.DARK;
        private boolean fillSubject, cross;
        private float lines, hatch, star, jitter;
        private int boil;
        private float lightR = 1f, lightG = 1f, lightB = 1f, darkR, darkG, darkB;

        private Builder(Kind kind) {
            this.kind = kind;
        }

        /** The paper's tone; ink is the other. */
        public Builder paper(Tone paper) {
            if (paper == null) throw new IllegalArgumentException("paper");
            this.paper = paper;
            return this;
        }

        /** Fills the subject in ink; false leaves it the paper's tone, drawn only by the lines and hatching round it. */
        public Builder fillSubject(boolean fill) {
            fillSubject = fill;
            return this;
        }

        /** Focus lines from the focus, 0 to 1: how many of the possible lines are drawn. They stop at the subject. */
        public Builder lines(float amount) {
            lines = clamp(amount, 1f);
            return this;
        }

        /** Strokes off the subject's edge, outward from the focus, at most {@code length} long as a share of the screen's height. */
        public Builder hatch(float length) {
            hatch = clamp(length, 0.5f);
            return this;
        }

        /** A flash star at the focus: a core {@code radius} across as a share of the screen's height, and short spikes. */
        public Builder star(float radius) {
            star = clamp(radius, 0.5f);
            return this;
        }

        /** A thin cross through the star, across the screen. */
        public Builder cross(boolean cross) {
            this.cross = cross;
            return this;
        }

        /** Shifts the subject and focus up to {@code pixels} each drawing: Sakurai's vibration while the hit is frozen. */
        public Builder jitter(float pixels) {
            jitter = clamp(pixels, 32f);
            return this;
        }

        /** Draws anew every {@code frames} frames within a beat: 2 boils on twos. 0 holds one drawing. */
        public Builder boil(int frames) {
            if (frames < 0) throw new IllegalArgumentException("boil: " + frames);
            boil = frames;
            return this;
        }

        /** The light tone, linear 0 to 1: white by default. */
        public Builder light(float r, float g, float b) {
            lightR = r;
            lightG = g;
            lightB = b;
            return this;
        }

        /** The dark tone, linear 0 to 1: black by default. */
        public Builder dark(float r, float g, float b) {
            darkR = r;
            darkG = g;
            darkB = b;
            return this;
        }

        public CgImpactFrame build() {
            return new CgImpactFrame(this);
        }

        private static float clamp(float v, float max) {
            return Math.max(0f, Math.min(max, v));
        }
    }
}
