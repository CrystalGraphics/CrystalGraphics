package com.crystalgraphics.vfx.net;

import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hit.CgSentHit;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.util.CgRandom;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgEntityAttachments;
import com.crystalgraphics.world.CgEntityQueries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An effect named for the network: the server plays it, every client that sees it builds and draws it. A cue is a
 * networked object type ({@link CgNetType}) whose objects are effects: its look's numbers and colours are fields, so
 * what the server sets reaches every viewer, and one who arrives later. Declared once in common code with what it
 * plays. Server-safe: nothing here draws.
 *
 * <pre>{@code
 * // common, at init, both sides
 * public static final CgVfxCue EXPLOSION = VFX.cue("explosion", Blast.LOOK,
 *         (look, cue) -> new Blast(look, cue.x(), cue.y(), cue.z())).build();
 * public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(), CgEnergyWave::fromCue)
 *         .range(96).authoritative().build();   // the server runs it too
 * }</pre>
 *
 * <p>From the server, a one-shot, gone once played, never seen by a player who joins later:</p>
 *
 * <pre>{@code
 * HIT_SPARK.at(level, x, y, z).toward(dx, dy, dz).follow(target, "rpgcore:body").keepOffset().send();
 * EXPLOSION.at(level, x, y, z).set(Blast.RADIUS, 6f).send();         // a look parameter, by its constant
 * }</pre>
 *
 * <p>Or a lasting effect, which a player who comes into view starts at its age:</p>
 *
 * <pre>{@code
 * CgVfxCue.Playing beam = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).target(tx, ty, tz).start();
 * beam.target(nx, ny, nz);    // every viewer, at the end of the tick
 * beam.stop();                // gracefully: what is in flight plays out
 * }</pre>
 *
 * <p>A cue's own data, beyond its look, is fields it declares:</p>
 *
 * <pre>{@code
 * public static final CgNetField<Integer> LEVEL = CgNetField.ofInt("level");
 * public static final CgVfxCue HIT = VFX.cue("hit_spark").fields(LEVEL).build();       // common
 * HIT.at(level, x, y, z).setInt(LEVEL, 2).send();                                          // server
 * VFX.register(HIT, HitSpark.LOOK, (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z(), cue.getInt(LEVEL)));
 * }</pre>
 *
 * <p>From a client, playing it now and asking the server to show everyone else (the server must allow it):</p>
 *
 * <pre>{@code
 * HIT_SPARK.at(x, y, z).predict();                                            // client
 * HIT_SPARK.fromClients((player, cue) -> reach(player, cue) < 6);             // server, at init
 * }</pre>
 *
 * <ul>
 *   <li><b>Declare at init on both sides</b>, before any player joins, and finish with {@link Builder#build()}.</li>
 *   <li>A play goes to the players tracking what it follows, else those within {@link #range} of its point.</li>
 *   <li>Effects are cosmetic: what a player must not miss (damage, a state) is the game's to replicate, unless the cue
 *       is {@linkplain Builder#authoritative() authoritative}.</li>
 *   <li>Server calls on the server thread; {@link Play#play()} and {@link Play#predict()} on the client's.</li>
 * </ul>
 */
public final class CgVfxCue {

    /** Blocks a play reaches round its point when it follows nothing: vanilla's level-event radius. */
    public static final float DEFAULT_RANGE = 64f;

    /** {@link #FLAGS}' bits. */
    static final int TOWARD = 1, TARGET = 2, KEEP_OFFSET = 4;

    // The fields every cue lists, first.
    static final CgNetField<Integer> SEED = CgNetField.ofInt("cue/seed").initialOnly();
    static final CgNetField<Integer> FLAGS = CgNetField.ofInt("cue/flags");
    /** Its point, where it is anchored on an entity; a point anchor carries its own. */
    static final CgNetField<Double> X = CgNetField.ofDouble("cue/x").initialOnly(),
            Y = CgNetField.ofDouble("cue/y").initialOnly(), Z = CgNetField.ofDouble("cue/z").initialOnly();
    /** The entity it follows by network id where it is anchored at a point. */
    static final CgNetField<Integer> FOLLOW = CgNetField.ofInt("cue/follow").fallback(-1).initialOnly();
    static final CgNetField<String> POINT = CgNetField.of("cue/point", CgCodecs.STRING).initialOnly();
    static final CgNetField<Float> DX = CgNetField.ofFloat("cue/dx"), DY = CgNetField.ofFloat("cue/dy"),
            DZ = CgNetField.ofFloat("cue/dz");
    static final CgNetField<Double> TX = CgNetField.ofDouble("cue/tx"), TY = CgNetField.ofDouble("cue/ty"),
            TZ = CgNetField.ofDouble("cue/tz");
    static final CgNetField<Integer> STRUCK = CgNetField.ofInt("cue/struck").fallback(-1);
    private static final CgNetField<?>[] CORE = {SEED, FLAGS, X, Y, Z, FOLLOW, POINT, DX, DY, DZ, TX, TY, TZ, STRUCK};

    /** What an authoritative cue's server copy struck, its part by its place in the look's schema. */
    static final CgNetEvent<CgSentHit> HIT = CgNetEvent.of("cue/hit", CgSentHit.wire());
    /**
     * A tween its server copy began, for every viewer to play: the parameter's place in the look's layout, the easing's
     * in {@code CgEasings}, the seconds, then where it ends.
     */
    static final CgNetEvent<List<Float>> TWEEN = CgNetEvent.of("cue/tween", CgCodecs.listOf(CgCodecs.FLOAT));
    /** An ending its server copy began, by name ({@code CgVfxEnding.named}); and the field a late viewer reads it from. */
    static final CgNetEvent<String> END = CgNetEvent.of("cue/end", CgCodecs.STRING);
    static final CgNetField<String> ENDING = CgNetField.of("cue/ending", CgCodecs.STRING);

    private static final Map<String, CgVfxCue> CUES = new ConcurrentHashMap<>();
    private static final Map<CgVfxLayout, LookFields> LOOK_FIELDS = new IdentityHashMap<>();

    private final String id;
    final CgNetType type;
    @Nullable
    final LookFields look;
    private final float range;
    final boolean authoritative;
    private volatile Policy policy;
    private final List<Consumer<? super CgHit>> confirmations = new CopyOnWriteArrayList<>();
    /** The confirmed hit handed to listeners; client thread. */
    private final CgHit arrived = new CgHit();

    private CgVfxCue(Builder b, CgNetType type) {
        this.id = b.id;
        this.type = type;
        this.look = b.look == null ? null : lookFields(b.look.layout());
        this.range = b.range;
        this.authoritative = b.authoritative;
    }

    /** Starts declaring {@code id} ({@code "namespace:path"}), with nothing it plays: a client says that. */
    public static Builder define(String id) {
        return new Builder(id, null, null);
    }

    /**
     * Starts declaring {@code id} with what it plays on every side: its look, whose parameters become its fields, and
     * how an effect is made from a play. Registered for tools by its id.
     */
    public static Builder define(String id, CgVfxLook look, CgVfxRegistry.CueFactory factory) {
        return new Builder(id, Objects.requireNonNull(look, "look"), Objects.requireNonNull(factory, "factory"));
    }

    /** Declares a cue. */
    public static final class Builder {
        private final String id;
        @Nullable
        private final CgVfxLook look;
        @Nullable
        private final CgVfxRegistry.CueFactory factory;
        private final List<CgNetField<?>> fields = new ArrayList<>();
        private float range = DEFAULT_RANGE;
        private boolean authoritative, persisted;

        private Builder(String id, @Nullable CgVfxLook look, @Nullable CgVfxRegistry.CueFactory factory) {
            if (id.indexOf(':') < 0) throw new IllegalArgumentException("a cue id is namespace:path: '" + id + "'");
            this.id = id;
            this.look = look;
            this.factory = factory;
        }

        /** How far round its point a play reaches when it follows nothing; {@link #DEFAULT_RANGE} unless said. */
        public Builder range(float blocks) {
            if (!(blocks > 0)) throw new IllegalArgumentException("range must be positive: " + blocks);
            range = blocks;
            return this;
        }

        /**
         * Runs its effect on the server too, for as long as a {@linkplain Play#start() lasting} play lasts: the same class
         * its viewers play, headless against the server's level ({@code CgVfxAuthority}). It decides where the effect goes
         * and what it strikes; the server hears its hits ({@link Playing#onHit}) and viewers hear them confirmed
         * ({@link #onConfirmedHit}); and it publishes what its viewers draw ({@link Authoritative}).
         *
         * <pre>{@code
         * public static final CgVfxCue WAVE = VFX.cue("kamehameha", LOOK, CgEnergyWave::fromCue).authoritative().build();
         *
         * CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();   // server
         * wave.onHit(CgEnergyWave.PART_BEAM, h -> damage(h.entity()));
         * }</pre>
         *
         * <ul>
         *   <li>Lasting plays only: a one-shot runs nowhere but on its viewers.</li>
         *   <li>Its {@code at(level, ...)} is a level or an entity in one, whose world it tests.</li>
         *   <li>Declared with what it plays, so the server can build it; not {@link #persisted()}.</li>
         * </ul>
         */
        public Builder authoritative() {
            authoritative = true;
            return this;
        }

        /** Lasting plays are saved with the world and resume at their age when it starts again. Not with follow. */
        public Builder persisted() {
            persisted = true;
            return this;
        }

        /**
         * Data of its own, beyond its look's parameters: set on a play, read in its factory and by {@link Live}.
         *
         * <pre>{@code
         * VFX.cue("hit_spark").fields(LEVEL, GUARDED).build();
         * }</pre>
         */
        public Builder fields(CgNetField<?>... fields) {
            Collections.addAll(this.fields, fields);
            return this;
        }

        public CgVfxCue build() {
            if (authoritative && persisted) throw new IllegalStateException("cue '" + id + "': an authoritative cue cannot persist");
            if (authoritative && factory == null) {
                throw new IllegalStateException("cue '" + id + "' is authoritative, so the server builds it: declare it with"
                        + " what it plays, cue(path, look, factory)");
            }
            if (CUES.containsKey(id)) throw new IllegalStateException("cue '" + id + "' is already declared");
            CgNetType.Builder type = CgNetType.define(id).fields(CORE).fields(ENDING)
                    .fields(fields.toArray(new CgNetField<?>[0])).events(HIT, TWEEN, END).range(range).keepRefused();
            if (look != null) {
                type.fields(look.layout().networked().toArray(new CgNetField<?>[0]));
                type.fields(lookFields(look.layout()).all);
            }
            if (persisted) type.persisted();
            CgVfxCue cue = new CgVfxCue(this, type.build());
            cue.type.onEvent(HIT, cue::confirmed);
            if (factory != null) CgVfxRegistry.register(cue, look, factory);
            CUES.put(id, cue);
            CgVfxCueClient.declared(cue);
            return cue;
        }
    }

    /** The cue declared as {@code id}, or null. */
    @Nullable
    public static CgVfxCue get(String id) {
        return CUES.get(id);
    }

    static Collection<CgVfxCue> all() {
        return CUES.values();
    }

    public String id() {
        return id;
    }

    /** The networked type its plays are objects of. */
    public CgNetType type() {
        return type;
    }

    /** How far round its point a play reaches when it follows nothing. */
    public float range() {
        return range;
    }

    /** Whether the server runs its effect too ({@link Builder#authoritative()}). */
    public boolean hasAuthority() {
        return authoritative;
    }

    /**
     * Lets clients {@linkplain Play#predict() predict} it: each play a client asks for is passed to {@code policy},
     * after a rate limit and a check that its point is within {@link #range} of the player, and shown to everyone else
     * when it says yes. Nobody may until this is called. Server, at init.
     */
    public CgVfxCue fromClients(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        type.acceptPredictionsFrom(this::allow);
        return this;
    }

    private boolean allow(CgPeer peer, CgNetObject proposed) {
        Object player = peer.player();
        Policy policy = this.policy;
        if (player == null || policy == null) return false;
        Play play = Play.read(this, proposed);
        double[] at = new double[3];
        if (!CgEntityQueries.position(player, at)) return false;
        double dx = at[0] - play.x, dy = at[1] - play.y, dz = at[2] - play.z;
        return dx * dx + dy * dy + dz * dz <= (double) range * range && policy.allow(player, play);
    }

    /** Plays clients asked for and were refused: no policy, over the rate, out of reach, or the policy said no. */
    public long refused() {
        return type.refusedPredictions();
    }

    /** On the server: a play at a point in a level ({@code level} a level, an entity in it, or a dimension id). */
    public Play at(Object level, double x, double y, double z) {
        Play play = new Play(this, x, y, z);
        play.level = Objects.requireNonNull(level, "level");
        return play;
    }

    /**
     * On the server: a play on an entity, following it at attachment point {@code point}
     * ({@code CgEntityAttachments.HANDS_FORWARD}, or one a mod declared), in the entity's level.
     *
     * <pre>{@code
     * WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).toward(look.x, look.y, look.z).start();
     * }</pre>
     */
    public Play on(Object entity, int point) {
        double[] at = new double[3];
        if (!CgEntityQueries.position(entity, at)) {
            throw new IllegalArgumentException("not an entity: " + entity);
        }
        return at(entity, at[0], at[1], at[2]).follow(entity, point);
    }

    /** On a client: a play at a point, to {@link Play#play()} here or {@link Play#predict()}. */
    public Play at(double x, double y, double z) {
        return new Play(this, x, y, z);
    }

    /**
     * On a client: hears what an {@linkplain Builder#authoritative() authoritative} play's server copy strikes, as the
     * server heard it: the hit that counted. It carries what was met, the point and the part; no normal or distance.
     * Client thread; the hit is valid during the call.
     *
     * <pre>{@code
     * WAVE.onConfirmedHit(CgEnergyWave.PART_BURST, h -> decals.scorch(h.x(), h.y(), h.z()));
     * }</pre>
     */
    public CgVfxCue onConfirmedHit(Consumer<? super CgHit> listener) {
        confirmations.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** As {@link #onConfirmedHit(Consumer)}, hearing only what {@code part} strikes. */
    public CgVfxCue onConfirmedHit(CgHitPart part, Consumer<? super CgHit> listener) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(listener, "listener");
        return onConfirmedHit(h -> {
            if (h.part() == part) listener.accept(h);
        });
    }

    private void confirmed(CgNetObject obj, CgSentHit sent) {
        if (confirmations.isEmpty()) return;
        sent.read(arrived, look == null ? null : look.layout.partAt(sent.part()));
        for (Consumer<? super CgHit> listener : confirmations) listener.accept(arrived);
    }

    /** Who may predict a cue, and where. */
    @FunctionalInterface
    public interface Policy {
        /** Whether {@code player} (the loader's player) may show {@code play} to everyone else. */
        boolean allow(Object player, Play play);
    }

    /**
     * An effect that takes a lasting cue's changes: a new target, a direction, a field of its own. Its look's numbers and
     * colours are set onto it first, with or without this. On every viewer, once after each change the server sends;
     * and on the server, for a change made through {@link Playing}.
     *
     * <pre>{@code
     * public final class Beam extends CgVfxEffect implements CgVfxCue.Live {
     *     public void cueChanged(CgVfxCue.Play cue) {
     *         if (cue.hasTarget()) target(cue.tx(), cue.ty(), cue.tz());
     *     }
     * }
     * }</pre>
     */
    public interface Live {
        void cueChanged(Play cue);
    }

    /**
     * An effect whose server copy decides what its viewers draw ({@link Builder#authoritative()}): after each server tick
     * it writes that into its cue, which reaches every viewer, where {@link Live} takes it. A value written unchanged
     * sends nothing. The numbers and colours set on the effect need none of this: every authoritative effect sends those.
     *
     * <pre>{@code
     * public void publish(CgVfxCue.Play cue) {
     *     cue.target(endX, endY, endZ).struck(hitEntity);   // where the beam truly ends
     * }
     * }</pre>
     *
     * State a look has no parameter for travels in fields its schema declares ({@code CgVfxSchema.networked}).
     */
    public interface Authoritative {
        void publish(Play cue);
    }

    @Override
    public String toString() {
        return id;
    }

    // ── Look parameters as fields ───────────────────────────────────────────────────────────────

    /** A layout's numbers, colours and palettes as float fields, one a component; shared by every cue playing it. */
    static final class LookFields {
        final CgVfxLayout layout;
        final CgVfxParam[] params;
        /** By parameter: its components' fields. */
        final CgNetField<Float>[][] fields;
        final CgNetField<?>[] all;
        /** A field's parameter, by identity. */
        final Map<CgNetField<?>, Integer> byField = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        LookFields(CgVfxLayout layout) {
            this.layout = layout;
            List<CgVfxParam> values = new ArrayList<>();
            for (CgVfxParam param : layout.params()) if (param.numeric()) values.add(param);
            params = values.toArray(new CgVfxParam[0]);
            fields = new CgNetField[params.length][];
            List<CgNetField<?>> all = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                String name = "look/" + fieldName(layout.qualifiedName(params[i]));
                int size = params[i].floats();
                fields[i] = new CgNetField[size];
                for (int k = 0; k < size; k++) {
                    String component = size == 1 ? name : size == 3 ? name + "/" + "xyz".charAt(k)
                            : size == 4 ? name + "/" + "rgba".charAt(k)
                            : name + "/" + k / 4 + "rgba".charAt(k % 4);
                    fields[i][k] = CgNetField.ofFloat(component);
                    byField.put(fields[i][k], i);
                    all.add(fields[i][k]);
                }
            }
            this.all = all.toArray(new CgNetField<?>[0]);
        }

        @Nullable
        CgNetField<Float>[] of(CgVfxParam param) {
            for (int i = 0; i < params.length; i++) if (params[i] == param) return fields[i];
            return null;
        }

        /** Sets parameter {@code i} onto {@code effect} from {@code play}, where the server set it. */
        void apply(int i, Play play, CgVfxEffect<?> effect) {
            CgNetField<Float>[] f = fields[i];
            if (!play.has(f[0])) return;
            CgVfxParam param = params[i];
            if (param instanceof CgVfxParam.Scalar) {
                effect.set((CgVfxParam.Scalar) param, play.getFloat(f[0]));
            } else if (param instanceof CgVfxParam.Color) {
                effect.set((CgVfxParam.Color) param, play.getFloat(f[0]), play.getFloat(f[1]), play.getFloat(f[2]),
                        play.getFloat(f[3]));
            } else if (param instanceof CgVfxParam.Vector) {
                effect.set((CgVfxParam.Vector) param, play.getFloat(f[0]), play.getFloat(f[1]), play.getFloat(f[2]));
            } else {
                CgVfxParam.Palette palette = (CgVfxParam.Palette) param;
                for (int e = 0; e < palette.count(); e++) {
                    effect.set(palette, e, play.getFloat(f[e * 4]), play.getFloat(f[e * 4 + 1]), play.getFloat(f[e * 4 + 2]),
                            play.getFloat(f[e * 4 + 3]));
                }
            }
        }
    }

    private static LookFields lookFields(CgVfxLayout layout) {
        synchronized (LOOK_FIELDS) {
            return LOOK_FIELDS.computeIfAbsent(layout, LookFields::new);
        }
    }

    /** A tween as {@link #TWEEN} sends it: the parameter's place in the layout, the easing's, the seconds, the target. */
    static List<Float> tween(@Nullable LookFields look, CgVfxParam param, float[] to, float seconds, CgEasing easing) {
        if (look == null) throw new IllegalArgumentException("a cue with no look animates nothing");
        List<Float> out = new ArrayList<>(3 + to.length);
        out.add((float) look.layout.params().indexOf(param));
        out.add((float) CgEasings.indexOf(easing));
        out.add(seconds);
        for (float v : to) out.add(v);
        return out;
    }

    /** Plays a {@link #TWEEN} on a viewer's {@code effect}. */
    static void play(@Nullable LookFields look, CgVfxEffect<?> effect, List<Float> tween) {
        if (look == null || effect.look().layout() != look.layout || tween.size() < 4) return;
        int index = Math.round(tween.get(0));
        List<CgVfxParam> params = look.layout.params();
        if (index < 0 || index >= params.size()) return;
        CgVfxParam param = params.get(index);
        CgEasing easing = CgEasings.byIndex(Math.round(tween.get(1)));
        float seconds = tween.get(2);
        if (param instanceof CgVfxParam.Scalar) {
            effect.animate((CgVfxParam.Scalar) param, tween.get(3), seconds, easing);
        } else if (param instanceof CgVfxParam.Color && tween.size() >= 7) {
            effect.animate((CgVfxParam.Color) param, tween.get(3), tween.get(4), tween.get(5), tween.get(6), seconds, easing);
        }
    }

    /** A parameter's name as a field's: {@code blastShake} is {@code blast_shake}. */
    private static String fieldName(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (out.length() > 0) out.append('_');
                out.append(Character.toLowerCase(c));
            } else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '.' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** On a client: {@code field} changed on a viewer's {@code effect}; whether it was a look parameter, now set. */
    boolean applyLook(Play play, CgVfxEffect<?> effect, CgNetField<?> field) {
        if (look == null) return false;
        Integer i = look.byField.get(field);
        if (i == null) return false;
        if (effect.look().layout() == look.layout) look.apply(i, play, effect);
        return true;
    }

    // ── One play ────────────────────────────────────────────────────────────────────────────────

    /**
     * One play of a cue: built on the server and sent or started, or received by a client and handed to its factory and
     * to {@link Live}. Read with the getters; build with the rest.
     */
    public static final class Play {
        final CgVfxCue cue;
        double x, y, z;
        int flags;
        /** Its direction and its target, each valid while its bit of {@link #flags} is set. */
        double ax, ay, az, tgx, tgy, tgz;
        int followId = -1;
        @Nullable
        String point;
        int seed = CgRandom.nextInt();
        float age, lifetime;
        int struck = -1;
        boolean authoritative;

        // Server only.
        @Nullable
        Object level, followEntity;
        /** The object it is, once sent, started or predicted, or as a client received it. */
        @Nullable
        CgNetObject obj;
        /** Fields set before there was an object: field, value, field, value. */
        @Nullable
        private List<Object> pending;

        Play(CgVfxCue cue, double x, double y, double z) {
            this.cue = cue;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** {@code obj} as a play: what a viewer builds from, or a client proposed. */
        static Play read(CgVfxCue cue, CgNetObject obj) {
            Play play = new Play(cue, 0, 0, 0);
            play.obj = obj;
            play.readFrom(obj);
            return play;
        }

        void readFrom(CgNetObject obj) {
            boolean point = obj.anchor() == CgAnchor.Kind.POINT;
            x = point ? obj.x() : obj.getDouble(X);
            y = point ? obj.y() : obj.getDouble(Y);
            z = point ? obj.z() : obj.getDouble(Z);
            followId = point ? obj.getInt(FOLLOW) : obj.entityId();
            this.point = obj.get(POINT);
            seed = obj.getInt(SEED);
            flags = obj.getInt(FLAGS);
            ax = obj.getFloat(DX);
            ay = obj.getFloat(DY);
            az = obj.getFloat(DZ);
            tgx = obj.getDouble(TX);
            tgy = obj.getDouble(TY);
            tgz = obj.getDouble(TZ);
            struck = obj.getInt(STRUCK);
        }

        /** Writes everything it was built with into {@code obj}, still pending. */
        private void attach(CgNetObject obj) {
            this.obj = obj;
            obj.setInt(SEED, seed).setInt(FLAGS, flags).setInt(STRUCK, struck);
            if (obj.anchor() != CgAnchor.Kind.POINT) obj.setDouble(X, x).setDouble(Y, y).setDouble(Z, z);
            else if (followId >= 0) obj.setInt(FOLLOW, followId);
            if (point != null) obj.set(POINT, point);
            if ((flags & TOWARD) != 0) obj.setFloat(DX, (float) ax).setFloat(DY, (float) ay).setFloat(DZ, (float) az);
            if ((flags & TARGET) != 0) obj.setDouble(TX, tgx).setDouble(TY, tgy).setDouble(TZ, tgz);
            if (pending != null) {
                for (int i = 0; i < pending.size(); i += 2) set(obj, pending.get(i), pending.get(i + 1));
                pending = null;
            }
        }

        @SuppressWarnings("unchecked")
        private static void set(CgNetObject obj, Object field, Object value) {
            obj.set((CgNetField<Object>) field, value);
        }

        /** Where a write lands: its object on the server, else held until it has one. */
        private boolean writesThrough() {
            if (obj == null) return false;
            if (!obj.isServer() && !obj.isPredicted()) throw new IllegalStateException("a received play is the server's to change");
            return true;
        }

        // ── Building ──

        /** Facing a direction. With a {@link #target} too, it faces this way and goes there: a beam aimed by a player. */
        public Play toward(double dx, double dy, double dz) {
            flags |= TOWARD;
            ax = dx;
            ay = dy;
            az = dz;
            if (writesThrough()) obj.setInt(FLAGS, flags).setFloat(DX, (float) dx).setFloat(DY, (float) dy).setFloat(DZ, (float) dz);
            return this;
        }

        /** Aimed at a point: where a beam homes and blasts. */
        public Play target(double tx, double ty, double tz) {
            flags |= TARGET;
            tgx = tx;
            tgy = ty;
            tgz = tz;
            if (writesThrough()) obj.setInt(FLAGS, flags).setDouble(TX, tx).setDouble(TY, ty).setDouble(TZ, tz);
            return this;
        }

        /**
         * Following an entity (the loader's own, server side) at the attachment point named {@code point}
         * ({@code "crystalgraphics:hands_forward"}, or one a mod declared; null for its feet). The point is resolved on
         * each client from the entity as drawn. It is then seen by the players tracking it, and ends with it.
         */
        public Play follow(Object entity, @Nullable String point) {
            int entityId = CgEntityQueries.idOf(entity);
            if (entityId < 0) throw new IllegalArgumentException("not an entity: " + entity);
            followEntity = entity;
            followId = entityId;
            this.point = point;
            return this;
        }

        /** As {@link #follow(Object, String)}, at a point by id ({@code CgEntityAttachments.HANDS_FORWARD}). */
        public Play follow(Object entity, int point) {
            return follow(entity, CgEntityAttachments.name(point));
        }

        /** Following entity {@code entityId} by its network id: on a client, or where the server has no entity object. */
        public Play follow(int entityId, @Nullable String point) {
            followId = entityId;
            this.point = point;
            return this;
        }

        /**
         * Following as it was played: it stays at its point, offset from the attachment point as it was at the start,
         * rather than jumping to it. A hit spark at the contact riding the body it struck.
         *
         * <pre>{@code
         * HIT.at(level, contact.x, contact.y, contact.z).follow(target, "rpgcore:body").keepOffset().send();
         * }</pre>
         */
        public Play keepOffset() {
            flags |= KEEP_OFFSET;
            return this;
        }

        /**
         * The entity its target is on (its network id), or -1 for a block or nothing: what an authority publishes with
         * the target ({@link Authoritative}), so a viewer's feedback knows what was struck.
         */
        public Play struck(int entity) {
            struck = entity;
            if (writesThrough()) obj.setInt(STRUCK, entity);
            return this;
        }

        /** Its random seed: chosen for it, set only to replay one exactly. */
        public Play seed(int seed) {
            this.seed = seed;
            return this;
        }

        /** A lasting one stops by itself this long after it started. Server. */
        public Play lifetime(float seconds) {
            lifetime = Math.max(0f, seconds);
            return this;
        }

        /** One of its look's numbers, by the parameter's constant ({@code Blast.RADIUS}). */
        public Play set(CgVfxParam.Scalar param, float value) {
            return setFloat(lookField(param)[0], value);
        }

        /** One of its look's numbers as it carries it; NaN where nothing set it. */
        public float get(CgVfxParam.Scalar param) {
            CgNetField<Float> f = lookField(param)[0];
            return has(f) ? getFloat(f) : Float.NaN;
        }

        /** Component {@code index} of one of its look's vectors as it carries it; NaN where nothing set it. */
        public float get(CgVfxParam.Vector param, int index) {
            CgNetField<Float> f = lookField(param)[index];
            return has(f) ? getFloat(f) : Float.NaN;
        }

        /** One of its look's vectors. */
        public Play set(CgVfxParam.Vector param, float x, float y, float z) {
            CgNetField<Float>[] f = lookField(param);
            return setFloat(f[0], x).setFloat(f[1], y).setFloat(f[2], z);
        }

        /** One of its look's colours, {@code 0xAARRGGBB}. */
        public Play set(CgVfxParam.Color param, int argb) {
            return set(param, argb, 1f);
        }

        /** One of its look's colours, {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
        public Play set(CgVfxParam.Color param, int argb, float intensity) {
            return setColour(lookField(param), 0, argb, intensity);
        }

        /** Colour {@code entry} of one of its look's palettes, {@code 0xAARRGGBB}. */
        public Play set(CgVfxParam.Palette param, int entry, int argb) {
            return set(param, entry, argb, 1f);
        }

        /** Colour {@code entry} of one of its look's palettes, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
        public Play set(CgVfxParam.Palette param, int entry, int argb, float intensity) {
            return setColour(lookField(param), entry * 4, argb, intensity);
        }

        private Play setColour(CgNetField<Float>[] f, int at, int argb, float intensity) {
            return setFloat(f[at], CgVfxParam.Color.red(argb) * intensity)
                    .setFloat(f[at + 1], CgVfxParam.Color.green(argb) * intensity)
                    .setFloat(f[at + 2], CgVfxParam.Color.blue(argb) * intensity)
                    .setFloat(f[at + 3], CgVfxParam.Color.alpha(argb));
        }

        private CgNetField<Float>[] lookField(CgVfxParam param) {
            CgNetField<Float>[] f = cue.look == null ? null : cue.look.of(param);
            if (f == null) throw new IllegalArgumentException(param + " is not a number or colour of cue '" + cue.id + "''s look");
            return f;
        }

        /** One of its cue's fields ({@link Builder#fields}, or its schema's {@code networked} ones). */
        public <V> Play set(CgNetField<V> field, V value) {
            if (writesThrough()) {
                obj.set(field, value);
            } else {
                if (pending == null) pending = new ArrayList<>(4);
                pending.add(field);
                pending.add(value);
            }
            return this;
        }

        public Play setInt(CgNetField<Integer> field, int value) {
            if (writesThrough()) obj.setInt(field, value);
            else set(field, value);
            return this;
        }

        public Play setFloat(CgNetField<Float> field, float value) {
            if (writesThrough()) obj.setFloat(field, value);
            else set(field, value);
            return this;
        }

        public Play setBool(CgNetField<Boolean> field, boolean value) {
            if (writesThrough()) obj.setBool(field, value);
            else set(field, value);
            return this;
        }

        // ── Sending ──

        /** On the server: a one-shot, to the players tracking what it follows, else within its cue's range of it. */
        public void send() {
            spawn().fire();
        }

        /** On the server: plays it as lasting state, which every viewer sees from now on, a late one at its age. */
        public Playing start() {
            CgNetObject started = spawn();
            if (lifetime > 0f) started.lifetime(Math.max(1, Math.round(lifetime * 20f)));
            CgVfxCueAuthority authority = cue.authoritative ? new CgVfxCueAuthority(this) : null;
            if (authority != null) started.behaviour(authority);
            started.start();
            return new Playing(this, authority);
        }

        private CgNetObject spawn() {
            if (level == null) throw new IllegalStateException("send and start a play made by at(level, x, y, z)");
            if (obj != null) throw new IllegalStateException("a play is sent once");
            CgNetObject spawned = cue.type.spawn(followEntity != null ? CgAnchor.on(followEntity) : CgAnchor.at(level, x, y, z));
            attach(spawned);
            return spawned;
        }

        /** On a client: plays it here alone. Null where nothing is registered under its id. */
        @Nullable
        public CgVfxEffect<?> play() {
            return CgVfxCueClient.play(this);
        }

        /**
         * On a client: plays it here now, and asks the server to show everyone else ({@link CgVfxCue#fromClients}).
         * Refused, it plays on here alone. Null where nothing is registered under its id.
         */
        @Nullable
        public CgVfxEffect<?> predict() {
            if (obj != null) throw new IllegalStateException("a play is sent once");
            CgNetObject predicted = cue.type.predict(followId >= 0 ? CgAnchor.onEntity(followId) : CgAnchor.at(x, y, z));
            attach(predicted);
            predicted.fire();
            CgVfxCueClient.View view = predicted.behaviour();
            return view == null ? null : view.effect;
        }

        /** Its effect, as {@code entry} makes it: seeded, following what it follows, its look's values set, at its age. */
        CgVfxEffect<?> build(CgVfxRegistry.Entry entry) {
            CgVfxEffect<?> effect = CgVfxEffect.seeded(seedUnit(), () -> entry.spawn(this));
            if (followId >= 0) {
                int at = point == null ? CgEntityAttachments.FEET : CgEntityAttachments.declare(point, CgEntityAttachments.CENTRE);
                if ((flags & KEEP_OFFSET) != 0) effect.followKeepingOffset(followId, at);
                else effect.follow(followId, at);
            }
            LookFields look = cue.look;
            if (look != null && effect.look().layout() == look.layout) {
                for (int i = 0; i < look.params.length; i++) look.apply(i, this, effect);
            }
            if (age > 0f) effect.startAt(age);
            String ending = get(ENDING);
            if (ending != null) {
                CgVfxEnding named = CgVfxEnding.named(ending);
                if (named != null) effect.end(named);
            }
            return effect;
        }

        // ── Reading ──

        public CgVfxCue cue() {
            return cue;
        }

        public String id() {
            return cue.id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        /** Whether it was given a direction or a target. */
        public boolean hasDirection() {
            return (flags & (TOWARD | TARGET)) != 0;
        }

        public boolean hasTarget() {
            return (flags & TARGET) != 0;
        }

        /** Its direction, unit length: the one given, else toward its target; 0 for neither. */
        public double dx() {
            return direction(0);
        }

        public double dy() {
            return direction(1);
        }

        public double dz() {
            return direction(2);
        }

        private double direction(int axis) {
            if (!hasDirection()) return 0;
            boolean given = (flags & TOWARD) != 0;
            double vx = given ? ax : tgx - x, vy = given ? ay : tgy - y, vz = given ? az : tgz - z;
            double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (length < 1e-9) return 0;
            return (axis == 0 ? vx : axis == 1 ? vy : vz) / length;
        }

        /** Its target, or its point when it has none. */
        public double tx() {
            return hasTarget() ? tgx : x;
        }

        public double ty() {
            return hasTarget() ? tgy : y;
        }

        public double tz() {
            return hasTarget() ? tgz : z;
        }

        /** The entity its target is on, or -1 ({@link #struck(int)}). */
        public int struck() {
            return struck;
        }

        /** The entity it follows by network id, or -1. */
        public int followingId() {
            return followId;
        }

        /**
         * The host's entity it follows, cast where it is asked: on the server the one it was given, on a client the one of
         * {@link #followingId()} in the client's level; null for none, or one not loaded.
         */
        @Nullable
        public <V> V following() {
            if (followEntity != null) return cast(followEntity);
            if (followId < 0) return null;
            return level != null ? CgEntityQueries.of(level).entity(followId) : CgEntityQueries.client().entity(followId);
        }

        @SuppressWarnings("unchecked")
        private static <V> V cast(Object o) {
            return (V) o;
        }

        /** The attachment point it follows at, or null for the feet. */
        @Nullable
        public String point() {
            return point;
        }

        /** Whether it keeps its offset from the point it follows ({@link #keepOffset}). */
        public boolean keepsOffset() {
            return (flags & KEEP_OFFSET) != 0;
        }

        /**
         * Whether it is building the effect the server runs ({@link Builder#authoritative()}), rather than a viewer's: a
         * factory serving both sides branches on it.
         *
         * <pre>{@code
         * if (cue.authoritative() && !cue.hasTarget()) wave.collide();   // the server finds the end; viewers home on it
         * }</pre>
         */
        public boolean authoritative() {
            return authoritative;
        }

        public int seed() {
            return seed;
        }

        /** Its seed as an effect's: 0 to 1. */
        public float seedUnit() {
            return (seed >>> 8) * (1f / (1 << 24));
        }

        /** Seconds it had played when this client built it: 0 for a one-shot, its age for a lasting one seen late. */
        public float age() {
            return age;
        }

        /** Whether {@code field} holds a value the server set, rather than its fallback. */
        public boolean has(CgNetField<?> field) {
            if (obj != null) return obj.type().lists(field) && obj.has(field);
            if (pending != null) for (int i = 0; i < pending.size(); i += 2) if (pending.get(i) == field) return true;
            return false;
        }

        /** One of its fields; the field's fallback where it was never set, or its cue does not list it. */
        @SuppressWarnings("unchecked")
        public <V> V get(CgNetField<V> field) {
            if (obj != null) return obj.type().lists(field) ? obj.get(field) : field.fallback();
            if (pending != null) {
                for (int i = pending.size() - 2; i >= 0; i -= 2) if (pending.get(i) == field) return (V) pending.get(i + 1);
            }
            return field.fallback();
        }

        public int getInt(CgNetField<Integer> field) {
            return obj != null && obj.type().lists(field) ? obj.getInt(field) : get(field);
        }

        public float getFloat(CgNetField<Float> field) {
            return obj != null && obj.type().lists(field) ? obj.getFloat(field) : get(field);
        }

        public double getDouble(CgNetField<Double> field) {
            return obj != null && obj.type().lists(field) ? obj.getDouble(field) : get(field);
        }

        public boolean getBool(CgNetField<Boolean> field) {
            return obj != null && obj.type().lists(field) ? obj.getBool(field) : get(field);
        }

        @Override
        public String toString() {
            return cue.id + " at " + (float) x + ", " + (float) y + ", " + (float) z;
        }
    }

    // ── A lasting play ──────────────────────────────────────────────────────────────────────────

    /**
     * A lasting cue playing on the server. Server thread.
     *
     * <pre>{@code
     * CgVfxCue.Playing aura = AURA.on(player, CgEntityAttachments.CENTRE).start();
     * aura.set(Aura.LEVEL, 3f);   // a look parameter: every viewer, at the end of the tick
     * aura.stop();
     * }</pre>
     *
     * Where the cue is {@linkplain Builder#authoritative() authoritative}, drive its effect instead, as a local one:
     *
     * <pre>{@code
     * CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
     * CgVfxSystemInstance<CgEnergyWave> beam = wave.effect();
     * beam.set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY).set(CgEnergyWave.AIM, look.x, look.y, look.z);
     * beam.set(CgEnergyWave.RADIUS, 1.2f);       // a number, colour or vector set on it reaches every viewer too
     * beam.set(CgEnergyWave.RELEASE, beam.age());   // fired, on every viewer
     * }</pre>
     */
    public static final class Playing {
        private final Play play;
        /** Its effect run on the server, or null when its cue has no authority. */
        @Nullable
        private final CgVfxCueAuthority authority;

        Playing(Play play, @Nullable CgVfxCueAuthority authority) {
            this.play = play;
            this.authority = authority;
        }

        /** The networked object it is: its fields, owner and age as the layer keeps them. */
        public CgNetObject object() {
            return play.obj;
        }

        /** Re-aims it at a point; viewers hear it at the end of the tick, and nothing when it is where it was. */
        public Playing target(double tx, double ty, double tz) {
            play.target(tx, ty, tz);
            return changed();
        }

        /** Re-aims it along a direction; with a target too, it faces this way and goes there. Unchanged sends nothing. */
        public Playing toward(double dx, double dy, double dz) {
            play.toward(dx, dy, dz);
            return changed();
        }

        /** One of its look's numbers; on an authoritative cue, set on the server's effect, which sends it on. */
        public Playing set(CgVfxParam.Scalar param, float value) {
            if (authority != null) authority.effect().set(param, value);
            else play.set(param, value);
            return this;
        }

        /** One of its look's vectors; on an authoritative cue, set on the server's effect, which sends it on. */
        public Playing set(CgVfxParam.Vector param, float x, float y, float z) {
            if (authority != null) authority.effect().set(param, x, y, z);
            else play.set(param, x, y, z);
            return this;
        }

        /** One of its look's colours, {@code 0xAARRGGBB}; on an authoritative cue, set on the server's effect. */
        public Playing set(CgVfxParam.Color param, int argb) {
            return set(param, argb, 1f);
        }

        /** One of its look's colours, {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
        public Playing set(CgVfxParam.Color param, int argb, float intensity) {
            if (authority != null) authority.effect().set(param, argb, intensity);
            else play.set(param, argb, intensity);
            return this;
        }

        /** Colour {@code entry} of one of its look's palettes, {@code 0xAARRGGBB}. */
        public Playing set(CgVfxParam.Palette param, int entry, int argb) {
            return set(param, entry, argb, 1f);
        }

        /** Colour {@code entry} of one of its look's palettes, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
        public Playing set(CgVfxParam.Palette param, int entry, int argb, float intensity) {
            if (authority != null) authority.effect().set(param, entry, argb, intensity);
            else play.set(param, entry, argb, intensity);
            return this;
        }

        /**
         * Eases one of its look's numbers to {@code to} over {@code seconds}, on every viewer at once. On an
         * authoritative cue, the server's effect eases it too, and its hits follow.
         *
         * <pre>{@code
         * wave.animate(CgEnergyWave.RADIUS, 1.4f, 0.5f, CgEasings.OUT_BACK);   // powered up mid-beam
         * }</pre>
         */
        public Playing animate(CgVfxParam.Scalar param, float to, float seconds, CgEasing easing) {
            if (authority != null) {
                authority.effect().animate(param, to, seconds, easing);
            } else {
                play.obj.emit(TWEEN, tween(play.cue.look, param, new float[] {to}, seconds, easing));
            }
            return this;
        }

        /**
         * Ends it by {@code ending} on every viewer, and on the server's copy of an authoritative cue: its values eased
         * where the ending puts them, then gone.
         *
         * <pre>{@code
         * wave.end(CgEnergyWave.FADE);
         * }</pre>
         */
        public Playing end(CgVfxEnding ending) {
            if (authority != null) {
                authority.effect().end(ending);
            } else {
                play.obj.set(ENDING, ending.name());
                play.obj.emit(END, ending.name());
            }
            return this;
        }

        /** One of its cue's fields. */
        public <V> Playing set(CgNetField<V> field, V value) {
            play.set(field, value);
            return changed();
        }

        private Playing changed() {
            if (authority != null) authority.cueChanged();
            return this;
        }

        /**
         * Hears what its effect strikes on the server, where its cue is {@linkplain Builder#authoritative()
         * authoritative}: damage from where the effect truly is. Server thread.
         *
         * <pre>{@code
         * wave.onHit(CgEnergyWave.PART_BEAM, h -> stagger(h.entity()))
         *     .onHit(CgEnergyWave.PART_BURST, h -> explode(h.x(), h.y(), h.z(), beam.blastRadius()))
         *     .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));
         * }</pre>
         *
         * It keeps hearing after {@link #stop()} while what is in flight plays out: a beam's blast comes then.
         */
        public Playing onHit(Consumer<? super CgHit> listener) {
            running().onHit(listener);
            return this;
        }

        /** As {@link #onHit(Consumer)}, hearing only what {@code part} strikes. */
        public Playing onHit(CgHitPart part, Consumer<? super CgHit> listener) {
            running().onHit(part, listener);
            return this;
        }

        private CgVfxEffect<?> running() {
            if (authority == null) {
                throw new IllegalStateException("cue '" + play.cue.id + "' runs nowhere on the server: declare it authoritative()");
            }
            return authority.effect();
        }

        /**
         * The effect the server runs for it, to drive as a local one and read (a wave's {@code fire()},
         * {@code hitEntity()}); null unless its cue is {@linkplain Builder#authoritative() authoritative}. Stopping it
         * ends the cue.
         */
        @Nullable
        @SuppressWarnings("unchecked")
        public <E extends CgVfxEffect<?>> E effect() {
            return authority == null ? null : (E) authority.effect();
        }

        /** Moves its point: where it plays, and who is in range of it. Not one that follows an entity. */
        public Playing moveTo(double x, double y, double z) {
            play.x = x;
            play.y = y;
            play.z = z;
            play.obj.moveTo(x, y, z);
            return changed();
        }

        /** Ends it gracefully on every viewer: emission stops, what is in flight plays out. */
        public void stop() {
            play.obj.despawn();
        }

        /** Ends it at once on every viewer, particles and all. */
        public void kill() {
            play.obj.kill();
        }

        /** Whether it still plays: not stopped, killed or past its lifetime. */
        public boolean playing() {
            return !play.obj.isRemoved() && !play.obj.isStopping();
        }

        /** Seconds since it started. */
        public float age() {
            return play.obj.age() / 20f;
        }

        @Override
        public String toString() {
            return "Playing[" + play + "]";
        }
    }

    // ── What the server struck ──────────────────────────────────────────────────────────────────

}
