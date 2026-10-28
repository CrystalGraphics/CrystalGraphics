package com.crystalgraphics.asset.camera;

import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.camera.CgCameraShakeState;
import com.crystalgraphics.vfx.camera.CgPerlinShakePattern;
import com.crystalgraphics.vfx.camera.CgSampleHoldShakePattern;
import com.crystalgraphics.vfx.camera.CgShakePattern;
import com.crystalgraphics.vfx.camera.CgShakePlay;
import com.crystalgraphics.vfx.camera.CgShakeSource;
import com.crystalgraphics.vfx.camera.CgWaveShakePattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A shake asset compiled ({@code cameras/shakes/}, Unreal's {@code UCameraShakeBase}): its pattern in the host's units,
 * its scale, and where and how a play is felt. {@link #play} starts it; every play is summed into the camera in
 * {@code SHAKE}, beside {@link CgCameraShake}'s trauma.
 *
 * <pre>{@code
 * CgCameraShakeDefinition.register();                            // at init: the shake kind compiles to these
 * CgCameraShakeDefinition shake = (CgCameraShakeDefinition) load.definition();
 * CgShakePlay play = shake.play(x, y, z, 1f, seed);              // felt by distance from the point
 * play.stop(false);                                              // an endless shake blends out
 * }</pre>
 *
 * <ul>
 *   <li>Patterns played: {@code perlin}, {@code wave}, {@code sample_hold}, {@code trauma}, and {@code composite} of
 *       the first three. {@code sequence}, a trauma inside a composite and a {@code user_defined} play space fail at
 *       load.</li>
 *   <li>Lengths are kept in blocks and angles in degrees, through the asset's units.</li>
 *   <li>A trauma pattern plays through {@link CgCameraShake}: {@link #play} returns null for it.</li>
 *   <li>A {@code single_instance} shake already playing restarts where it is asked again, as Unreal's.</li>
 * </ul>
 */
public final class CgCameraShakeDefinition implements CgShakeSource {

    /** The shake kind's compiler. */
    public static final CgAssetCompiler<CgCameraShakeDefinition> COMPILER = (asset, deps) -> compile(asset, deps.units());

    private final String id;
    private final Spec pattern;
    private final float scale, inner, outer, falloff;
    private final boolean singleInstance, world;
    private final int[] axes;
    private final ArrayDeque<CgShakePlay> pool = new ArrayDeque<>();

    private CgCameraShakeDefinition(String id, Spec pattern, float scale, boolean singleInstance, float inner, float outer,
                                    float falloff, boolean world, int[] axes) {
        this.id = id;
        this.pattern = pattern;
        this.scale = scale;
        this.singleInstance = singleInstance;
        this.inner = inner;
        this.outer = outer;
        this.falloff = falloff;
        this.world = world;
        this.axes = axes;
    }

    /** Registers {@link #COMPILER} as the shake kind's. */
    public static void register() {
        CgAssetCompiler.register(CgCameraKinds.SHAKE, COMPILER);
    }

    /** {@code asset}'s definition in the host's units. */
    public static CgCameraShakeDefinition compile(CgAssetData asset) {
        return compile(asset, CgUnits.HOST);
    }

    /** {@code asset}'s definition, its values written in {@code units}; throws on a pattern or space it does not play. */
    public static CgCameraShakeDefinition compile(CgAssetData asset, CgUnits units) {
        Map<Object, Object> tree = asset.resolved();
        String at = asset.id();
        Spec pattern = spec(at + ": pattern", tree.get("pattern"), units);
        Map<?, ?> play = tree.get("play") instanceof Map<?, ?> p ? p : Map.of();
        Object space = play.get("space") == null ? "camera_local" : play.get("space");
        boolean world = switch (String.valueOf(space)) {
            case "camera_local" -> false;
            case "world" -> true;
            default -> throw new IllegalArgumentException(at + ": play.space " + space + " is not played yet");
        };
        return new CgCameraShakeDefinition(at, pattern, number(tree.get("scale"), 1f), Boolean.TRUE.equals(tree.get("single_instance")),
                length(units, play.get("inner")), length(units, play.get("outer")), number(play.get("falloff"), 1f), world,
                units.axes().clone());
    }

    // A pattern as compiled; built into a CgShakePattern once a pooled play needs one.

    private sealed interface Spec permits Oscillation, SampleHold, Trauma, Composite {
    }

    /** {@code perlin} or {@code wave}: each channel's amplitude and frequency, and whether a wave's phase is random. */
    private record Oscillation(boolean perlin, float[] amplitude, float[] frequency, boolean[] random, float[] multipliers,
                               Timing timing) implements Spec {
    }

    private record SampleHold(float interval, float[] amplitude, Timing timing) implements Spec {
    }

    private record Trauma(CgCameraShake shake) implements Spec {
    }

    private record Composite(List<Spec> children, Timing timing) implements Spec {
    }

    /** Seconds: blend in, blend out, and a duration of 0 for endless. */
    private record Timing(float blendIn, float blendOut, float duration) {
    }

    private static Spec spec(String at, @Nullable Object written, CgUnits units) {
        if (!(written instanceof Map<?, ?> p)) throw new IllegalArgumentException(at + ": a shake names its pattern");
        String kind = String.valueOf(p.get("kind"));
        Timing timing = new Timing(time(units, p.get("blend_in")), time(units, p.get("blend_out")), time(units, p.get("duration")));
        switch (kind) {
            case "perlin":
            case "wave": {
                float[] amp = new float[CgShakePattern.CHANNELS], freq = new float[CgShakePattern.CHANNELS];
                boolean[] random = new boolean[CgShakePattern.CHANNELS];
                float[] multipliers = {1f, 1f, 1f, 1f};
                oscillating(p.get("location"), LOCATION, CgShakePattern.X, true, units, amp, freq, random, multipliers, 0);
                oscillating(p.get("rotation"), ROTATION, CgShakePattern.PITCH, false, units, amp, freq, random, multipliers, 2);
                if (p.get("fov") instanceof Map<?, ?> fov) axis(fov, CgShakePattern.FOV, false, units, amp, freq, random);
                return new Oscillation(kind.equals("perlin"), amp, freq, random, multipliers, timing);
            }
            case "sample_hold": {
                float[] amp = new float[CgShakePattern.CHANNELS];
                held(p.get("location"), LOCATION, CgShakePattern.X, true, units, amp);
                held(p.get("rotation"), ROTATION, CgShakePattern.PITCH, false, units, amp);
                if (p.get("fov") instanceof Map<?, ?> fov) amp[CgShakePattern.FOV] = degrees(units, fov.get("amplitude"));
                return new SampleHold(time(units, p.get("interval")), amp, timing);
            }
            case "trauma": {
                CgCameraShake.Builder b = CgCameraShake.builder().trauma(number(p.get("trauma"), 0f)).punch(number(p.get("punch"), 0f));
                if (p.get("tremor") instanceof Map<?, ?> t) b.tremor(number(t.get("amount"), 0f), time(units, t.get("seconds")));
                if (p.get("kick") instanceof Map<?, ?> k) b.kick(number(k.get("amount"), 0f), time(units, k.get("seconds")));
                if (p.get("arrives") instanceof Map<?, ?> a) {
                    if (a.get("easing") != null && !"linear".equals(a.get("easing"))) {
                        throw new IllegalArgumentException(at + ".arrives: easing " + a.get("easing") + " is not played yet");
                    }
                    b.arrives(length(units, a.get("reach")), time(units, a.get("seconds")), CgEasings.LINEAR);
                } else {
                    b.atOnce();
                }
                return new Trauma(b.build());
            }
            case "composite": {
                List<Spec> children = new ArrayList<>();
                if (p.get("children") instanceof List<?> l) {
                    for (int i = 0; i < l.size(); i++) {
                        Spec c = spec(at + ".children[" + i + "]", l.get(i), units);
                        if (c instanceof Trauma) throw new IllegalArgumentException(at + ".children[" + i + "]: a trauma child is not played yet");
                        children.add(c);
                    }
                }
                return new Composite(Collections.unmodifiableList(children), composed(timing, children));
            }
            default:
                throw new IllegalArgumentException(at + ": a " + kind + " shake pattern is not played yet");
        }
    }

    /** A composite lasts as long as its longest child unless it says, and is endless if a child is. */
    private static Timing composed(Timing own, List<Spec> children) {
        if (own.duration() > 0f) return own;
        float longest = 0f;
        for (Spec c : children) {
            float d = timing(c).duration();
            if (d <= 0f) return own;
            longest = Math.max(longest, d);
        }
        return new Timing(own.blendIn(), own.blendOut(), longest);
    }

    private static Timing timing(Spec s) {
        if (s instanceof Oscillation o) return o.timing();
        if (s instanceof SampleHold h) return h.timing();
        if (s instanceof Composite c) return c.timing();
        return new Timing(0f, 0f, 0f);
    }

    private static final String[] LOCATION = {"x", "y", "z"}, ROTATION = {"pitch", "yaw", "roll"};

    private static void oscillating(@Nullable Object group, String[] names, int first, boolean length, CgUnits units, float[] amp,
                                    float[] freq, boolean[] random, float[] multipliers, int at) {
        if (!(group instanceof Map<?, ?> g)) return;
        multipliers[at] = number(g.get("amplitude_multiplier"), 1f);
        multipliers[at + 1] = number(g.get("frequency_multiplier"), 1f);
        for (int i = 0; i < names.length; i++) {
            if (g.get(names[i]) instanceof Map<?, ?> a) axis(a, first + i, length, units, amp, freq, random);
        }
    }

    private static void axis(Map<?, ?> a, int channel, boolean length, CgUnits units, float[] amp, float[] freq, boolean[] random) {
        amp[channel] = length ? length(units, a.get("amplitude")) : degrees(units, a.get("amplitude"));
        freq[channel] = (float) (number(a.get("frequency"), 1f) / units.seconds());
        random[channel] = !"zero".equals(a.get("initial_offset"));
    }

    private static void held(@Nullable Object group, String[] names, int first, boolean length, CgUnits units, float[] amp) {
        if (!(group instanceof Map<?, ?> g)) return;
        for (int i = 0; i < names.length; i++) {
            if (g.get(names[i]) instanceof Map<?, ?> a) {
                amp[first + i] = length ? length(units, a.get("amplitude")) : degrees(units, a.get("amplitude"));
            }
        }
    }

    private static float number(@Nullable Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    private static float length(CgUnits units, @Nullable Object v) {
        return v instanceof Number n ? (float) (n.doubleValue() * units.metres()) : 0f;
    }

    /** Seconds; {@code infinite} and absent are 0. */
    private static float time(CgUnits units, @Nullable Object v) {
        return v instanceof Number n ? (float) (n.doubleValue() * units.seconds()) : 0f;
    }

    /** A shake's angles stay degrees: the asset's angle unit to radians, then degrees. */
    private static float degrees(CgUnits units, @Nullable Object v) {
        return v instanceof Number n ? (float) Math.toDegrees(n.doubleValue() * units.radians()) : 0f;
    }

    // Plays: a built pattern and its blend, pooled.

    /**
     * Starts it at the point, scaled by {@code scale} times its own, its random parts from {@code seed}: summed into
     * the camera in {@code SHAKE} from this frame. Null for a trauma pattern.
     */
    @Nullable
    public CgShakePlay play(double x, double y, double z, float scale, long seed) {
        return play(x, y, z, scale, seed, inner, outer, falloff, world);
    }

    /**
     * As {@link #play(double, double, double, float, long)}, felt as the play site says: a cue's or a track's own
     * radii (blocks; an {@code outer} of 0 for everywhere), falloff and space. A trauma pattern keeps its own.
     */
    @Nullable
    public CgShakePlay play(double x, double y, double z, float scale, long seed, float inner, float outer, float falloff, boolean world) {
        if (pattern instanceof Trauma t) {
            t.shake().play(x, y, z, this.scale * scale);
            return null;
        }
        CgShakePlay play = singleInstance ? CgShakePlay.playing(this) : null;
        if (play == null) {
            Timing timing = timing(pattern);
            play = pool.isEmpty() ? new CgShakePlay(this, build(pattern), new CgCameraShakeState(timing.blendIn(), timing.blendOut(), timing.duration()))
                    : pool.pop();
        }
        restart(play.pattern(), pattern, seed);
        return play.start(x, y, z, this.scale * scale, inner, outer, falloff, world);
    }

    /**
     * A new pattern started from {@code seed}, unweighted: what a play of it samples, for a preview. Throws for a
     * trauma pattern, which has none.
     */
    public CgShakePattern sample(long seed) {
        if (pattern instanceof Trauma) throw new IllegalStateException(id + " is a trauma shake: CgCameraShake plays it");
        CgShakePattern p = build(pattern);
        restart(p, pattern, seed);
        return p;
    }

    @Override
    public void give(CgShakePlay play) {
        pool.push(play);
    }

    private static CgShakePattern build(Spec s) {
        if (s instanceof Oscillation o) {
            float[] m = o.multipliers();
            if (o.perlin()) {
                CgPerlinShakePattern p = new CgPerlinShakePattern().locationMultipliers(m[0], m[1]).rotationMultipliers(m[2], m[3]);
                for (int c = 0; c < CgShakePattern.CHANNELS; c++) p.channel(c, o.amplitude()[c], o.frequency()[c]);
                return p;
            }
            CgWaveShakePattern w = new CgWaveShakePattern().locationMultipliers(m[0], m[1]).rotationMultipliers(m[2], m[3]);
            for (int c = 0; c < CgShakePattern.CHANNELS; c++) w.channel(c, o.amplitude()[c], o.frequency()[c], o.random()[c]);
            return w;
        }
        if (s instanceof SampleHold h) {
            CgSampleHoldShakePattern p = new CgSampleHoldShakePattern(h.interval());
            for (int c = 0; c < CgShakePattern.CHANNELS; c++) p.amplitude(c, h.amplitude()[c]);
            return p;
        }
        Composite c = (Composite) s;
        CgShakePattern[] children = new CgShakePattern[c.children().size()];
        CgCameraShakeState[] states = new CgCameraShakeState[children.length];
        for (int i = 0; i < children.length; i++) {
            Spec child = c.children().get(i);
            Timing t = timing(child);
            children[i] = build(child);
            states[i] = new CgCameraShakeState(t.blendIn(), t.blendOut(), t.duration());
        }
        return new Sum(children, states);
    }

    private static void restart(CgShakePattern p, Spec s, long seed) {
        if (p instanceof CgPerlinShakePattern perlin) {
            perlin.start(seed);
        } else if (p instanceof CgWaveShakePattern wave) {
            wave.start(seed);
        } else if (p instanceof CgSampleHoldShakePattern hold) {
            hold.start(seed);
        } else if (p instanceof Sum sum) {
            List<Spec> children = ((Composite) s).children();
            for (int i = 0; i < sum.children.length; i++) {
                restart(sum.children[i], children.get(i), seed * 31 + i + 1);
                sum.states[i].start();
            }
        }
    }

    /**
     * A composite's children, each under its own blend and duration and added together, as
     * {@code UCompositeCameraShakePattern} applies them in turn; a finished child adds nothing.
     */
    static final class Sum implements CgShakePattern {
        final CgShakePattern[] children;
        final CgCameraShakeState[] states;
        private final float[] scratch = new float[CHANNELS];

        Sum(CgShakePattern[] children, CgCameraShakeState[] states) {
            this.children = children;
            this.states = states;
        }

        @Override
        public void update(float dt, float[] out) {
            Arrays.fill(out, 0f);
            for (int i = 0; i < children.length; i++) {
                float w = states[i].update(dt);
                if (!states[i].playing()) continue;
                children[i].update(dt, scratch);
                for (int k = 0; k < CHANNELS; k++) out[k] += scratch[k] * w;
            }
        }
    }

    public String id() {
        return id;
    }

    public float scale() {
        return scale;
    }

    public boolean singleInstance() {
        return singleInstance;
    }

    @Override
    public boolean world() {
        return world;
    }

    @Override
    public int[] axes() {
        return axes;
    }

    @Override
    public float inner() {
        return inner;
    }

    @Override
    public float outer() {
        return outer;
    }

    @Override
    public float falloff() {
        return falloff;
    }

    @Override
    public String toString() {
        return "CgCameraShakeDefinition[" + id + "]";
    }
}
