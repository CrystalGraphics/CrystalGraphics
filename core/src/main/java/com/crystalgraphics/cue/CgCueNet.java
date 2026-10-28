package com.crystalgraphics.cue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.cue.CgCueDefinition.Field;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.world.CgAttachment;

/**
 * Every cue asset over the network: one networked type, {@code crystalgraphics:cue}, declared at init on both sides,
 * whose objects name the cue they play. Mirrors GAS's replication of cues as a tag and its parameters, never a type
 * per cue: a cue loads long after players connect, and a type declared then reaches nobody.
 *
 * <pre>{@code
 * // a client, once its world's manager exists
 * CgCueNet.client(obj -> cues);
 * }</pre>
 *
 * <ul>
 *   <li>A server's {@link CgCueManager} sends what it plays; nothing calls this class there.</li>
 *   <li>A cue's {@code fields} and, when it is authoritative, its {@code writes} travel in slot banks in the
 *       definition's order: at most {@link #WRITES} writes, {@link #VALUES} number fields and {@link #TEXTS} strings.
 *       A cue past them fails when it plays, by name.</li>
 *   <li>A burst is fired, so a later viewer never sees it; a looping cue is started, so one arriving late plays it at
 *       its age, its sequence sought there.</li>
 * </ul>
 */
public final class CgCueNet {

    /** Writes a cue may send, four floats each. */
    public static final int WRITES = 16;
    /** Its own {@code int}, {@code float}, {@code double} and {@code bool} fields. */
    public static final int VALUES = 8;
    /** Its own {@code string} fields. */
    public static final int TEXTS = 4;
    /** How far round its point a cue reaches when its file names no {@code range}, in blocks: today's cues' too. */
    public static final float DEFAULT_RANGE = 64f;
    /** A server tick, in seconds: what an object's age counts. */
    static final float TICK = 0.05f;

    static final CgNetField<String> CUE = CgNetField.of("cue", CgCodecs.STRING).initialOnly();
    static final CgNetField<Integer> SEED = CgNetField.ofInt("seed").initialOnly();
    static final CgNetField<Double> X = CgNetField.ofDouble("x").initialOnly(), Y = CgNetField.ofDouble("y").initialOnly(),
            Z = CgNetField.ofDouble("z").initialOnly();
    static final CgNetField<Float> NX = CgNetField.ofFloat("nx").initialOnly(), NY = CgNetField.ofFloat("ny").initialOnly(),
            NZ = CgNetField.ofFloat("nz").initialOnly();
    static final CgNetField<Integer> INSTIGATOR = CgNetField.ofInt("instigator").fallback(-1).initialOnly(),
            TARGET = CgNetField.ofInt("target").fallback(-1).initialOnly();
    static final CgNetField<Float> MAGNITUDE = CgNetField.ofFloat("magnitude"), NORMALIZED = CgNetField.ofFloat("normalized");
    static final CgNetField<String> SURFACE = CgNetField.of("surface", CgCodecs.STRING).initialOnly();
    static final List<CgNetField<Float>> W = new ArrayList<>();
    static final List<CgNetField<Double>> V = new ArrayList<>();
    static final List<CgNetField<String>> T = new ArrayList<>();

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

    /** The type every cue plays as. */
    public static final CgNetType TYPE;

    static {
        List<CgNetField<?>> fields = new ArrayList<>(List.of(CUE, SEED, X, Y, Z, NX, NY, NZ, INSTIGATOR, TARGET, MAGNITUDE, NORMALIZED, SURFACE));
        for (int i = 0; i < WRITES * 4; i++) W.add(CgNetField.ofFloat("w" + i));
        for (int i = 0; i < VALUES; i++) V.add(CgNetField.ofDouble("v" + i));
        for (int i = 0; i < TEXTS; i++) T.add(CgNetField.of("t" + i, CgCodecs.STRING));
        fields.addAll(W);
        fields.addAll(V);
        fields.addAll(T);
        TYPE = CgNetType.define("crystalgraphics:cue").fields(fields.toArray(new CgNetField<?>[0])).events(EVENT, RECUR).build();
    }

    private CgCueNet() {
    }

    /** Declares the type: at network init, on both sides, before anyone connects. */
    public static void install() {
    }

    /**
     * Plays the cues this client is sent through the manager {@code managers} answers for each object: the world's,
     * or in a test with many clients, the one whose client sees it.
     */
    public static void client(Function<CgNetObject, CgCueManager> managers) {
        TYPE.onClient(obj -> new View(managers.apply(obj)));
    }

    /** {@code cue} played with {@code at}, ready to fire or start; null where {@code at} names no anchor. */
    @Nullable
    static CgNetObject spawn(CgCueDefinition cue, CgCueParameters at) {
        CgAnchor anchor = at.anchor();
        if (anchor == null) return null;
        if (cue.writes().size() > WRITES) throw new IllegalStateException(cue.id() + ": writes " + cue.writes().size() + " names, past " + WRITES);
        CgNetObject obj = TYPE.spawn(anchor);
        obj.range(Float.isNaN(cue.range()) ? DEFAULT_RANGE : cue.range());
        return fill(obj, cue, at);
    }

    /**
     * {@code cue} as this client predicts it for its own input, under {@code key}, its view bound to {@code playing} so
     * what the server later says of it reaches that play; null where {@code at} names no anchor.
     */
    @Nullable
    static CgNetObject predict(CgCueDefinition cue, CgCueParameters at, CgCueManager cues, CgCuePlaying playing, CgPredictionKey key) {
        CgAnchor anchor = at.anchor();
        if (anchor == null) return null;
        if (cue.writes().size() > WRITES) throw new IllegalStateException(cue.id() + ": writes " + cue.writes().size() + " names, past " + WRITES);
        CgNetObject obj = TYPE.predict(anchor).within(key).behaviour(new View(cues, playing));
        return fill(obj, cue, at);
    }

    private static CgNetObject fill(CgNetObject obj, CgCueDefinition cue, CgCueParameters at) {
        obj.set(CUE, cue.id()).setInt(SEED, (int) at.seed())
                .setDouble(X, at.x()).setDouble(Y, at.y()).setDouble(Z, at.z())
                .setFloat(NX, at.nx()).setFloat(NY, at.ny()).setFloat(NZ, at.nz())
                .setInt(INSTIGATOR, entity(at.instigator())).setInt(TARGET, entity(at.target()))
                .setFloat(MAGNITUDE, at.magnitude()).setFloat(NORMALIZED, at.normalizedMagnitude());
        if (at.surface() != null) obj.set(SURFACE, at.surface());
        return obj;
    }

    /** Sets {@code obj}'s slot for {@code cue}'s field {@code name}: a number into the value bank, a string into the text bank. */
    static void field(CgCueDefinition cue, CgNetObject obj, String name, Object value) {
        int values = 0, texts = 0;
        for (Field f : cue.fields()) {
            boolean text = f.type() == Field.Type.STRING;
            if (f.name().equals(name)) {
                if (text) obj.set(T.get(texts), String.valueOf(value));
                else obj.setDouble(V.get(values), value instanceof Boolean b ? (b ? 1 : 0) : ((Number) value).doubleValue());
                return;
            }
            if (text) texts++;
            else values++;
        }
        throw new IllegalArgumentException(cue.id() + " has no field " + name);
    }

    /** {@code write}'s four floats into its slots. */
    static void write(CgNetObject obj, int write, float[] value) {
        for (int k = 0; k < 4; k++) obj.setFloat(W.get(write * 4 + k), value[k]);
    }

    /** {@code obj}'s parameters into {@code into}, its entities through {@code entities}. */
    static void read(CgNetObject obj, CgCueParameters into, CgCueManager entities) {
        into.reset().at(obj.getDouble(X), obj.getDouble(Y), obj.getDouble(Z))
                .normal(obj.getFloat(NX), obj.getFloat(NY), obj.getFloat(NZ))
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
     * A write as a viewer sees it: the slot's value as the server last sent it, brought to each frame by its mode. A
     * goal is a critically damped spring of ω {@link Write#rate()}, so it never overshoots a target that stops.
     */
    static final class Replica implements CgCuePlaying.Provider {
        private final CgNetObject obj;
        private final int write;
        private final Write mode;
        private final float[] last = new float[4], previous = new float[4], x = new float[4], v = new float[4];
        private double changedAt = Double.NaN, at = Double.NaN;

        Replica(CgNetObject obj, int write, Write mode) {
            this.obj = obj;
            this.write = write;
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
            return obj.getFloat(W.get(write * 4 + k));
        }

        /** Interpolated: from the value it held when the slot changed to the new one, over a server tick. */
        private float value(int k, double time) {
            float s = (float) Math.min(1, Math.max(0, (time - changedAt) / TICK));
            return previous[k] + (last[k] - previous[k]) * s;
        }
    }
}
