package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A way to end an effect other than its own: over some seconds, numbers and colours tweened to where they end, then
 * gone, skipping what the effect's graceful {@code stop()} would play. A bolt that fizzles rather than bursts, an aura
 * faded when its buff is dispelled.
 *
 * <pre>{@code
 * // declared once, as a constant on the effect's class
 * public static final CgVfxEnding FIZZLE = CgVfxEnding.over("mymod:bolt/fizzle", 0.4f)   // its name and its seconds
 *         .animate(Bolt.RADIUS, 0f, CgEasings.IN_QUAD)          // each value: where it ends, how it gets there
 *         .animate(Bolt.CORE, 0xFFFF3020, CgEasings.LINEAR)
 *         .fadeOut(CgEasings.IN_QUAD)                           // and the whole effect's presence to 0
 *         .build();
 *
 * bolt.end(FIZZLE);                  // an effect you played
 * playing.end(FIZZLE);               // a lasting cue: every viewer's copy fizzles
 * aura.end(CgVfxEnding.FADE_OUT);    // any effect, faded out over 0.3 s
 *
 * // a fade of another length is an ending of its own
 * public static final CgVfxEnding SLOW_FADE = CgVfxEnding.over("mymod:slow_fade", 1.2f).fadeOut(CgEasings.IN_QUAD).build();
 * }</pre>
 *
 * <ul>
 *   <li>It crosses the network as its name alone, each client finding the ending declared under it ({@link #named}):
 *   declare it on a class every client has loaded by then (the effect's, or the look's), or it does nothing there.</li>
 *   <li>Prefix the name with your mod's id: a second ending of one name throws.</li>
 *   <li>Ending an effect whose look lacks one of its values throws.</li>
 *   <li>An effect hears it in {@code ending(CgVfxEnding)}, to skip what a graceful end would play (a wave's blast).</li>
 *   <li>Its easings travel by their place in {@link CgEasings}: one made by a factory reaches viewers as linear.</li>
 * </ul>
 */
public final class CgVfxEnding {

    private static final Map<String, CgVfxEnding> BY_NAME = new ConcurrentHashMap<>();

    /** Any effect's presence eased to nothing over 0.3 seconds, then gone: every engine layer fades with it. */
    public static final CgVfxEnding FADE_OUT = over("crystalgraphics:fade_out", 0.3f).fadeOut(CgEasings.IN_QUAD).build();

    /** One value animated: a number or a colour's floats, to where it ends. */
    public static final class Target {
        final CgVfxParam param;
        final float[] to;
        final CgEasing easing;

        Target(CgVfxParam param, float[] to, CgEasing easing) {
            this.param = param;
            this.to = to;
            this.easing = easing;
        }

        public CgVfxParam param() {
            return param;
        }

        public float[] to() {
            return to.clone();
        }

        public CgEasing easing() {
            return easing;
        }
    }

    private final String name;
    private final float seconds;
    private final List<Target> targets;
    private final boolean fadesOut;
    private final CgEasing fadeEasing;

    private CgVfxEnding(Builder b) {
        this.name = b.name;
        this.seconds = b.seconds;
        this.targets = Collections.unmodifiableList(new ArrayList<>(b.targets));
        this.fadesOut = b.fadesOut;
        this.fadeEasing = b.fadeEasing;
    }

    /** An ending named {@code name} taking {@code seconds}. */
    public static Builder over(String name, float seconds) {
        return new Builder(name, seconds);
    }

    /** The ending declared as {@code name}, or null: what a viewer resolves a cue's ending by. */
    public static CgVfxEnding named(String name) {
        return BY_NAME.get(name);
    }

    public String name() {
        return name;
    }

    public float seconds() {
        return seconds;
    }

    public List<Target> targets() {
        return targets;
    }

    /** Whether its effect's presence eases to 0 over it. */
    public boolean fadesOut() {
        return fadesOut;
    }

    public CgEasing fadeEasing() {
        return fadeEasing;
    }

    @Override
    public String toString() {
        return "ending " + name;
    }

    public static final class Builder {

        private final String name;
        private final float seconds;
        private final List<Target> targets = new ArrayList<>();
        private boolean fadesOut;
        private CgEasing fadeEasing = CgEasings.LINEAR;

        private Builder(String name, float seconds) {
            this.name = Objects.requireNonNull(name, "name");
            if (!(seconds >= 0f)) throw new IllegalArgumentException("seconds " + seconds);
            this.seconds = seconds;
        }

        /** {@code param} eased to {@code to} over the ending. */
        public Builder animate(CgVfxParam.Scalar param, float to, CgEasing easing) {
            targets.add(new Target(Objects.requireNonNull(param, "param"), new float[] {to}, easing));
            return this;
        }

        /** A colour eased to {@code 0xAARRGGBB}. */
        public Builder animate(CgVfxParam.Color param, int argb, CgEasing easing) {
            float[] to = {CgVfxParam.Color.red(argb), CgVfxParam.Color.green(argb), CgVfxParam.Color.blue(argb),
                    CgVfxParam.Color.alpha(argb)};
            targets.add(new Target(Objects.requireNonNull(param, "param"), to, easing));
            return this;
        }

        /** The effect's presence eased to 0 as well: what every engine layer multiplies its light by. */
        public Builder fadeOut(CgEasing easing) {
            fadesOut = true;
            fadeEasing = Objects.requireNonNull(easing, "easing");
            return this;
        }

        public CgVfxEnding build() {
            CgVfxEnding ending = new CgVfxEnding(this);
            if (BY_NAME.putIfAbsent(name, ending) != null) {
                throw new IllegalStateException("An ending named " + name + " exists already");
            }
            return ending;
        }
    }
}
