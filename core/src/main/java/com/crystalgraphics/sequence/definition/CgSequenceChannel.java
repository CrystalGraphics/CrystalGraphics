package com.crystalgraphics.sequence.definition;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.curve.CgRichCurve;

/**
 * One channel of a section, compiled into flat columns: Unreal's {@code FMovieSceneFloatChannel} and its held kin
 * ({@code FMovieSceneBoolChannel}, {@code FMovieSceneIntegerChannel}, {@code FMovieSceneStringChannel}), and the
 * triggered ones (an event's keys, a Niagara system's activate and deactivate). Keys are on the sequence's ticks.
 *
 * <pre>{@code
 * CgSequenceChannel x = section.channel("translation.x");
 * float at = x.eval(ticks);                                  // a float channel: its curve at the time
 *
 * CgSequenceChannel visible = section.channel("visible");
 * boolean on = visible.bool(tick);                           // a held channel: the key at or before, else the first
 *
 * CgSequenceChannel keys = section.channel("keys");          // a system's: activate, deactivate, trigger, kill
 * for (int i = 0; i < keys.size(); i++) handle(keys.tick(i), CgSequenceChannel.Particle.values()[keys.intValue(i)]);
 * }</pre>
 *
 * <ul>
 *   <li>A float channel's weighted segments are baked to samples at compile: evaluating never solves a cubic.</li>
 *   <li>A held channel with no keys holds its {@code default}; with none, {@link #heldIndex} is -1 and the reader keeps
 *       what it had, as Unreal's {@code Evaluate} returns false.</li>
 *   <li>Keys are in tick order; a file whose keys go backwards fails at load.</li>
 *   <li>Values are in the host's units for a declared quantity, but a rotation's angles, which are radians in the
 *       sequence's own convention ({@link CgSequenceDefinition#units()}).</li>
 * </ul>
 */
public final class CgSequenceChannel {

    /** What a channel's keys hold. */
    public enum Type {
        /** A curve. */
        FLOAT,
        /** Held: a {@code bool}, read from {@link #intValue} as 0 or 1. */
        BOOL,
        /** Held: an {@code integer} or {@code byte}. */
        INTEGER,
        /** Held: a {@code string}, an {@code object}'s asset id or an {@code actor}'s binding id. */
        STRING,
        /** Triggered: an event's name ({@link #string}) and arguments ({@link #args}). */
        EVENT,
        /** Triggered: a system's {@link Particle} op, read from {@link #intValue}. */
        PARTICLE
    }

    /**
     * A {@code particle} key: Unreal's {@code EParticleKey} ({@code FMovieSceneParticleChannel}), a kill, and a detach:
     * the system left where it is, in world space, playing on.
     */
    public enum Particle { ACTIVATE, DEACTIVATE, TRIGGER, KILL, DETACH }

    private final String name;
    private final Type type;
    @Nullable
    private final CgRichCurve curve;
    private final int[] ticks, ints;
    @Nullable
    private final String[] strings;
    @Nullable
    private final Object[] args;
    private final boolean hasDefault;
    private final int defaultInt;
    @Nullable
    private final String defaultString;

    private CgSequenceChannel(String name, Type type, @Nullable CgRichCurve curve, int[] ticks, int[] ints,
                              @Nullable String[] strings, @Nullable Object[] args, boolean hasDefault, int defaultInt,
                              @Nullable String defaultString) {
        this.name = name;
        this.type = type;
        this.curve = curve;
        this.ticks = ticks;
        this.ints = ints;
        this.strings = strings;
        this.args = args;
        this.hasDefault = hasDefault;
        this.defaultInt = defaultInt;
        this.defaultString = defaultString;
    }

    static CgSequenceChannel ofCurve(String name, CgRichCurve curve, boolean hasDefault) {
        int[] ticks = new int[curve.size()];
        for (int i = 0; i < ticks.length; i++) ticks[i] = (int) curve.time(i);
        return new CgSequenceChannel(name, Type.FLOAT, curve, ticks, new int[0], null, null, hasDefault, 0, null);
    }

    static CgSequenceChannel ofKeys(String name, Type type, int[] ticks, int[] ints, @Nullable String[] strings,
                                    @Nullable Object[] args, boolean hasDefault, int defaultInt, @Nullable String defaultString) {
        return new CgSequenceChannel(name, type, null, ticks, ints, strings, args, hasDefault, defaultInt, defaultString);
    }

    /** Its path in its section: {@code translation.x}, {@code scalars.near}, {@code keys}, {@code weight}. */
    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    /** A float channel's curve, its times ticks; null for any other type. */
    @Nullable
    public CgRichCurve curve() {
        return curve;
    }

    /** How many keys. */
    public int size() {
        return ticks.length;
    }

    /** Key {@code i}'s tick. */
    public int tick(int i) {
        return ticks[i];
    }

    /** Key {@code i}'s bool (0 or 1), integer or particle op. */
    public int intValue(int i) {
        return ints[i];
    }

    /** Key {@code i}'s string, or an event's name. */
    public String string(int i) {
        return strings[i];
    }

    /** Key {@code i}'s event arguments as written; null where it has none. */
    @Nullable
    public Object args(int i) {
        return args[i];
    }

    /** A float channel at {@code tick}, a sub-frame allowed. */
    public float eval(float tick) {
        return curve.eval(tick);
    }

    /**
     * The key a held channel holds at {@code tick}: the last at or before it, else the first. -1 with no keys, where
     * the channel holds its default if it has one.
     */
    public int heldIndex(int tick) {
        int lo = 0, hi = ticks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ticks[mid] <= tick) lo = mid + 1;
            else hi = mid;
        }
        return ticks.length == 0 ? -1 : Math.max(0, lo - 1);
    }

    /** Whether it has a value at all: keys, or a {@code default}. */
    public boolean hasValue() {
        return ticks.length > 0 || hasDefault;
    }

    /** A held bool or integer channel's value at {@code tick}: its key's, else its default, else 0. */
    public int integer(int tick) {
        int i = heldIndex(tick);
        return i >= 0 ? ints[i] : defaultInt;
    }

    /** A held bool channel's value at {@code tick}. */
    public boolean bool(int tick) {
        return integer(tick) != 0;
    }

    /** A held string channel's value at {@code tick}: its key's, else its default; null with neither. */
    @Nullable
    public String stringAt(int tick) {
        int i = heldIndex(tick);
        return i >= 0 ? strings[i] : defaultString;
    }

    @Override
    public String toString() {
        return name + " " + type + " ×" + ticks.length;
    }
}
