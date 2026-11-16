package com.crystalgraphics.cue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.cue.CgCueDefinition.Field;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.net.CgSequenceNet;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.world.CgAttachment;

/**
 * Every cue asset over the network: each cue a networked type of its own, {@code <namespace>:cue/<path>}, declared as
 * the cue compiles on each side. Its fields are the parameters every play carries (GAS's
 * {@code FGameplayCueParameters}), then four floats per {@code writes} name and the cue's own {@code fields}, each
 * under its name.
 *
 * <pre>{@code
 * // a client, once its world's manager exists
 * CgCueNet.client(obj -> cues);
 * // a server, at init: which of a client's predicted plays it makes real
 * CgCueNet.acceptPredictionsFrom((peer, obj) -> near(peer, obj));
 * }</pre>
 *
 * <ul>
 *   <li>A server's {@link CgCueManager} sends what it plays; nothing else calls this class there.</li>
 *   <li>Every cue compiles at startup on both sides, so its type exists before anyone connects. A reload that changes
 *       a cue's {@code writes} or {@code fields} fails it by name until a restart.</li>
 *   <li>Two {@code writes} or {@code fields} names alike but for case fail at compile: a field's name is lowercase.</li>
 *   <li>A burst is fired, so a later viewer never sees it; a looping cue is started, so one arriving late plays it at
 *       its age, its sequence sought there.</li>
 * </ul>
 */
public final class CgCueNet {

    /** How far round its point a cue reaches when its file names no {@code range}, in blocks: today's cues' too. */
    public static final float DEFAULT_RANGE = 64f;
    /** A server tick, in seconds: what an object's age counts. */
    static final float TICK = 0.05f;

    static final CgNetField<Integer> SEED = CgNetField.ofInt("seed").initialOnly();
    static final CgNetField<Double> X = CgNetField.ofDouble("x").initialOnly(), Y = CgNetField.ofDouble("y").initialOnly(),
            Z = CgNetField.ofDouble("z").initialOnly();
    static final CgNetField<Float> NX = CgNetField.ofFloat("nx").initialOnly(), NY = CgNetField.ofFloat("ny").initialOnly(),
            NZ = CgNetField.ofFloat("nz").initialOnly(), SCALE = CgNetField.ofFloat("scale").fallback(1f).initialOnly();
    static final CgNetField<Integer> INSTIGATOR = CgNetField.ofInt("instigator").fallback(-1).initialOnly(),
            TARGET = CgNetField.ofInt("target").fallback(-1).initialOnly();
    static final CgNetField<Float> MAGNITUDE = CgNetField.ofFloat("magnitude"), NORMALIZED = CgNetField.ofFloat("normalized");
    static final CgNetField<String> SURFACE = CgNetField.of("surface", CgCodecs.STRING).initialOnly();

    /** An event's or a recurrence's burst: where, and on whom. */
    record Burst(String event, double x, double y, double z, int target) {
        static final CgCodec<Burst> CODEC = CgCodecs.record(
                CgCodecs.field("e", CgCodecs.STRING, Burst::event),
                CgCodecs.field("x", CgCodecs.DOUBLE, Burst::x),
                CgCodecs.field("y", CgCodecs.DOUBLE, Burst::y),
                CgCodecs.field("z", CgCodecs.DOUBLE, Burst::z),
                CgCodecs.field("t", CgCodecs.INT, Burst::target),
                Burst::new);
    }

    static final CgNetEvent<Burst> EVENT = CgNetEvent.of("event", Burst.CODEC), RECUR = CgNetEvent.of("recur", Burst.CODEC);

    private static final Map<String, Shape> BY_CUE = new ConcurrentHashMap<>();
    private static final Map<CgNetType, Shape> BY_TYPE = new ConcurrentHashMap<>();
    @Nullable
    private static volatile Function<CgNetObject, CgNetBehaviour> view;
    @Nullable
    private static volatile BiPredicate<CgPeer, CgNetObject> accept;

    private CgCueNet() {
    }

    /** A cue's type: its fields by place and name. One per cue id, fixed until a restart. */
    static final class Shape {
        final String cue;
        final CgNetType type;
        /** Four a {@code writes} name, in the cue's order. */
        final CgNetField<Float>[] writes;
        /** By {@code writes} name's place: whether the play started with a value for it; unset, the systems' defaults hold. */
        final CgNetField<Boolean>[] set;
        /** Its own {@code fields}, by the cue's names. */
        final Map<String, CgNetField<?>> fields = new HashMap<>();

        @SuppressWarnings("unchecked")
        Shape(CgCueDefinition cue, CgNetType type) {
            this.cue = cue.id();
            this.type = type;
            writes = new CgNetField[cue.writes().size() * 4];
            set = new CgNetField[cue.writes().size()];
            int w = 0;
            for (String name : cue.writes().keySet()) {
                for (int k = 0; k < 4; k++) writes[w * 4 + k] = (CgNetField<Float>) type.field(writeName(name, k));
                set[w] = (CgNetField<Boolean>) type.field(setName(name));
                w++;
            }
            for (Field f : cue.fields()) fields.put(f.name(), type.field(fieldName(f.name())));
        }
    }

    private static String writeName(String name, int k) {
        return "write." + name.toLowerCase(Locale.ROOT) + "." + k;
    }

    private static String setName(String name) {
        return "write." + name.toLowerCase(Locale.ROOT) + ".set";
    }

    private static String fieldName(String name) {
        return "field." + name.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code cue}'s type name: {@code mymod:beam} plays as {@code mymod:cue/beam}, lowercased as type names are (an
     * imported cue keeps its game's capitals).
     */
    static String typeName(String cue) {
        int colon = cue.indexOf(':');
        return (cue.substring(0, colon) + ":cue/" + cue.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    /**
     * Declares {@code cue}'s type, or finds it declared with the same shape: its compiler's call, on each side.
     *
     * @throws IllegalStateException naming the cue, where its {@code writes} or {@code fields} changed since it was
     *                               declared
     */
    static Shape declare(CgCueDefinition cue) {
        List<CgNetField<?>> list = new ArrayList<>(List.of(SEED, X, Y, Z, NX, NY, NZ, SCALE, INSTIGATOR, TARGET, MAGNITUDE,
                NORMALIZED, SURFACE));
        for (String name : cue.writes().keySet()) {
            for (int k = 0; k < 4; k++) list.add(CgNetField.ofFloat(writeName(name, k)));
            list.add(CgNetField.ofBool(setName(name)).initialOnly());
        }
        for (Field f : cue.fields()) list.add(field(f));
        CgNetType type;
        try {
            type = CgNetType.define(typeName(cue.id())).fields(list.toArray(new CgNetField<?>[0])).events(EVENT, RECUR).buildOnce();
        } catch (IllegalArgumentException | IllegalStateException bad) {
            throw new IllegalStateException(cue.id() + ": " + bad.getMessage(), bad);
        }
        Shape shape = BY_TYPE.computeIfAbsent(type, t -> new Shape(cue, t));
        BY_CUE.put(cue.id(), shape);
        Function<CgNetObject, CgNetBehaviour> v = view;
        if (v != null) type.onClient(v);
        BiPredicate<CgPeer, CgNetObject> a = accept;
        if (a != null) type.acceptPredictionsFrom(a);
        return shape;
    }

    private static CgNetField<?> field(Field f) {
        String name = fieldName(f.name());
        Object fallback = f.fallback();
        return switch (f.type()) {
            case INT -> fallback == null ? CgNetField.ofInt(name) : CgNetField.ofInt(name).fallback(((Number) fallback).intValue());
            case FLOAT -> fallback == null ? CgNetField.ofFloat(name) : CgNetField.ofFloat(name).fallback(((Number) fallback).floatValue());
            case DOUBLE -> fallback == null ? CgNetField.ofDouble(name) : CgNetField.ofDouble(name).fallback(((Number) fallback).doubleValue());
            case BOOL -> fallback == null ? CgNetField.ofBool(name) : CgNetField.ofBool(name).fallback((Boolean) fallback);
            case STRING -> fallback == null ? CgNetField.of(name, CgCodecs.STRING) : CgNetField.of(name, CgCodecs.STRING).fallback(String.valueOf(fallback));
        };
    }

    /** {@code cue}'s shape, declared now if its compiler has not: a cue built in Java. */
    static Shape shape(CgCueDefinition cue) {
        Shape s = BY_CUE.get(cue.id());
        return s != null ? s : declare(cue);
    }

    /** The type {@code cue} plays as. */
    public static CgNetType type(CgCueDefinition cue) {
        return shape(cue).type;
    }

    /** The id of the cue {@code obj} plays; null for an object of no cue's type. */
    @Nullable
    public static String cueOf(CgNetObject obj) {
        Shape s = BY_TYPE.get(obj.type());
        return s == null ? null : s.cue;
    }

    /**
     * Plays the cues this client is sent through the manager {@code managers} answers for each object: the world's,
     * or in a test with many clients, the one whose client sees it.
     */
    public static void client(Function<CgNetObject, CgCueManager> managers) {
        Function<CgNetObject, CgNetBehaviour> v = obj -> new View(managers.apply(obj));
        view = v;
        for (Shape s : BY_TYPE.values()) s.type.onClient(v);
        CgSequenceNet.cues((obj, sequence, roles, seed) -> managers.apply(obj).sequenceOfCue(CgSequenceNet.cueOf(obj)));
    }

    /**
     * Server: which plays a client predicted to make real, asked with the proposed object; every one refused until set.
     * Applies to every cue's type, declared or yet to be.
     */
    public static void acceptPredictionsFrom(BiPredicate<CgPeer, CgNetObject> rule) {
        accept = rule;
        for (Shape s : BY_TYPE.values()) s.type.acceptPredictionsFrom(rule);
    }

    /** {@code cue} played with {@code at}, ready to fire or start; null where {@code at} names no anchor. */
    @Nullable
    static CgNetObject spawn(CgCueDefinition cue, CgCueParameters at) {
        CgAnchor anchor = at.anchor();
        if (anchor == null) return null;
        Shape shape = shape(cue);
        CgNetObject obj = shape.type.spawn(anchor);
        obj.range(Float.isNaN(cue.range()) ? DEFAULT_RANGE : cue.range());
        return fill(obj, shape, cue, at);
    }

    /**
     * {@code cue} as this client predicts it for its own input, under {@code key}, its view bound to {@code playing} so
     * what the server later says of it reaches that play; null where {@code at} names no anchor.
     */
    @Nullable
    static CgNetObject predict(CgCueDefinition cue, CgCueParameters at, CgCueManager cues, CgCuePlaying playing, CgPredictionKey key) {
        CgAnchor anchor = at.anchor();
        if (anchor == null) return null;
        Shape shape = shape(cue);
        CgNetObject obj = shape.type.predict(anchor).within(key).behaviour(new View(cues, playing));
        return fill(obj, shape, cue, at);
    }

    private static CgNetObject fill(CgNetObject obj, Shape shape, CgCueDefinition cue, CgCueParameters at) {
        obj.setInt(SEED, (int) at.seed())
                .setDouble(X, at.x()).setDouble(Y, at.y()).setDouble(Z, at.z())
                .setFloat(NX, at.nx()).setFloat(NY, at.ny()).setFloat(NZ, at.nz()).setFloat(SCALE, at.scale())
                .setInt(INSTIGATOR, entity(at.instigator())).setInt(TARGET, entity(at.target()))
                .setFloat(MAGNITUDE, at.magnitude()).setFloat(NORMALIZED, at.normalizedMagnitude());
        if (at.surface() != null) obj.set(SURFACE, at.surface());
        if (at.writes() == 0) return obj;
        int w = 0;
        for (String name : cue.writes().keySet()) {
            int i = at.writeIndex(name);
            if (i >= 0) {
                for (int k = 0; k < 4; k++) obj.setFloat(shape.writes[w * 4 + k], at.writeValue(i, k));
                obj.setBool(shape.set[w], true);
            }
            w++;
        }
        return obj;
    }

    /** The {@code writes} values {@code obj}'s play started with, into {@code into}, by {@code cue}'s names. */
    static void readWrites(CgNetObject obj, CgCueDefinition cue, CgCueParameters into) {
        Shape shape = BY_TYPE.get(obj.type());
        CgNetField<Float>[] slots = shape.writes;
        int w = 0;
        for (String name : cue.writes().keySet()) {
            if (obj.getBool(shape.set[w])) {
                int b = w * 4;
                into.write(name, obj.getFloat(slots[b]), obj.getFloat(slots[b + 1]), obj.getFloat(slots[b + 2]),
                        obj.getFloat(slots[b + 3]));
            }
            w++;
        }
    }

    /** Sets {@code obj}'s field for the cue's own field {@code name}: a number, a bool or a string, as its type. */
    @SuppressWarnings("unchecked")
    static void field(CgCueDefinition cue, CgNetObject obj, String name, Object value) {
        CgNetField<?> f = BY_TYPE.get(obj.type()).fields.get(name);
        if (f == null) throw new IllegalArgumentException(cue.id() + " has no field " + name);
        switch (f.kind()) {
            case INT -> obj.setInt((CgNetField<Integer>) f, number(value).intValue());
            case FLOAT -> obj.setFloat((CgNetField<Float>) f, number(value).floatValue());
            case DOUBLE -> obj.setDouble((CgNetField<Double>) f, number(value).doubleValue());
            case BOOL -> obj.setBool((CgNetField<Boolean>) f, value instanceof Boolean b ? b : number(value).doubleValue() != 0);
            default -> obj.set((CgNetField<String>) f, String.valueOf(value));
        }
    }

    private static Number number(Object value) {
        return value instanceof Boolean b ? (b ? 1 : 0) : (Number) value;
    }

    /** The cue's own field {@code name} on {@code obj}: a number field's value, a bool as 0 or 1. */
    @SuppressWarnings("unchecked")
    static double number(CgCueDefinition cue, CgNetObject obj, String name) {
        CgNetField<?> f = BY_TYPE.get(obj.type()).fields.get(name);
        if (f == null || f.kind() == CgNetField.Kind.CODEC) throw new IllegalArgumentException(cue.id() + " has no number field " + name);
        return f.kind() == CgNetField.Kind.DOUBLE ? obj.getDouble((CgNetField<Double>) f) : obj.getNumber(f);
    }

    /** {@code write}'s four floats into its fields. */
    static void write(CgNetObject obj, int write, float[] value) {
        CgNetField<Float>[] slots = BY_TYPE.get(obj.type()).writes;
        for (int k = 0; k < 4; k++) obj.setFloat(slots[write * 4 + k], value[k]);
    }

    /** {@code obj}'s parameters into {@code into}, its entities through {@code entities}. */
    static void read(CgNetObject obj, CgCueParameters into, CgCueManager entities) {
        into.reset().at(obj.getDouble(X), obj.getDouble(Y), obj.getDouble(Z))
                .normal(obj.getFloat(NX), obj.getFloat(NY), obj.getFloat(NZ)).scale(obj.getFloat(SCALE))
                .instigator(entities.entity(obj.getInt(INSTIGATOR))).target(entities.entity(obj.getInt(TARGET)))
                .magnitude(obj.getFloat(MAGNITUDE)).normalizedMagnitude(obj.getFloat(NORMALIZED))
                .seed(obj.getInt(SEED)).surface(obj.get(SURFACE));
    }

    static Burst burst(String event, CgCueParameters at) {
        return new Burst(event, at.x(), at.y(), at.z(), entity(at.target()));
    }

    private static int entity(@Nullable Object attachment) {
        return attachment instanceof CgAttachment a ? a.entity() : -1;
    }

    /** A cue object as a client plays it: through its world's manager, its authoritative writes replicated. */
    private static final class View implements CgNetBehaviour {
        private final CgCueManager cues;
        private final CgCueParameters at = new CgCueParameters();
        @Nullable
        private CgCuePlaying playing;

        View(CgCueManager cues) {
            this.cues = cues;
        }

        /** A prediction's view: the play it already is. */
        View(CgCueManager cues, CgCuePlaying playing) {
            this.cues = cues;
            this.playing = playing;
        }

        @Override
        public void spawned(CgNetObject obj, float age) {
            if (playing != null) return;   // a prediction: already playing
            read(obj, at, cues);
            playing = cues.received(obj, at, age * TICK);
        }

        @Override
        public void event(CgNetObject obj, CgNetEvent<?> event, Object value) {
            if (playing == null || !(value instanceof Burst b)) return;
            at.at(b.x(), b.y(), b.z()).target(cues.entity(b.target()));
            if (event == EVENT) playing.event(b.event(), at);
            else if (event == RECUR) playing.recur(at);
        }

        @Override
        public void stopping(CgNetObject obj) {
            if (playing != null && playing.active()) playing.remove();
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            if (playing == null || !playing.active()) return;
            if (obj.isPredicted()) playing.discarded();
            else if (killed) playing.kill();
            else playing.remove();
            playing = null;
        }
    }

    /**
     * A write as a viewer sees it: its fields' values as the server last sent them, brought to each frame by its mode.
     * A goal is a critically damped spring of ω {@link Write#rate()}, so it never overshoots a target that stops.
     */
    static final class Replica implements CgCuePlaying.Provider {
        private final CgNetObject obj;
        private final CgNetField<Float>[] slots;
        private final int base;
        private final Write mode;
        private final float[] last = new float[4], previous = new float[4], x = new float[4], v = new float[4];
        private double changedAt = Double.NaN, at = Double.NaN;

        Replica(CgNetObject obj, int write, Write mode) {
            this.obj = obj;
            this.slots = BY_TYPE.get(obj.type()).writes;
            this.base = write * 4;
            this.mode = mode;
        }

        @Override
        public void write(double time, float[] out) {
            if (Double.isNaN(at)) {
                for (int k = 0; k < 4; k++) last[k] = previous[k] = x[k] = slot(k);
                at = changedAt = time;
            } else {
                boolean changed = false;
                for (int k = 0; k < 4; k++) changed |= slot(k) != last[k];
                if (changed) {
                    for (int k = 0; k < 4; k++) {
                        previous[k] = value(k, time);
                        last[k] = slot(k);
                    }
                    changedAt = time;
                }
            }
            float dt = (float) Math.max(0, time - at);
            at = time;
            for (int k = 0; k < 4; k++) {
                switch (mode.replication()) {
                    case SNAP -> out[k] = last[k];
                    case INTERPOLATE -> out[k] = value(k, time);
                    case GOAL -> {
                        // The critically damped spring's exact step: stable at any dt, and never past a target it nears.
                        float w = mode.rate(), d = x[k] - last[k], e = (float) Math.exp(-w * dt), j = (v[k] + w * d) * dt;
                        x[k] = last[k] + (d + j) * e;
                        v[k] = (v[k] - w * j) * e;
                        out[k] = x[k];
                    }
                }
            }
        }

        private float slot(int k) {
            return obj.getFloat(slots[base + k]);
        }

        /** Interpolated: from the value it held when the field changed to the new one, over a server tick. */
        private float value(int k, double time) {
            float s = (float) Math.min(1, Math.max(0, (time - changedAt) / TICK));
            return previous[k] + (last[k] - previous[k]) * s;
        }
    }
}
