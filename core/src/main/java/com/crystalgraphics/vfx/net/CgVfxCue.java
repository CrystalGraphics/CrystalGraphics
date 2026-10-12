package com.crystalgraphics.vfx.net;

import com.crystalgraphics.collision.CgPart;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.vfx.CgVfxCollision;
import com.crystalgraphics.vfx.CgVfxCollisionListener;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.world.CgEntityAttachments;

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
import java.util.concurrent.ThreadLocalRandom;

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

    /** What an authoritative cue's server copy struck. */
    static final CgNetEvent<Confirmed> COLLIDED = CgNetEvent.of("cue/collided", Confirmed.CODEC);

    private static final Map<String, CgVfxCue> CUES = new ConcurrentHashMap<>();
    private static final Map<CgVfxSchema, LookFields> LOOK_FIELDS = new IdentityHashMap<>();

    private final String id;
    final CgNetType type;
    @Nullable
    final LookFields look;
    private final float range;
    final boolean authoritative;
    private volatile Policy policy;
    private final List<Confirmation> confirmations = new CopyOnWriteArrayList<>();

    private CgVfxCue(Builder b, CgNetType type) {
        this.id = b.id;
        this.type = type;
        this.look = b.look == null ? null : lookFields(b.look.schema());
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
         * and what it strikes; the server hears its collisions ({@link Playing#onCollide}) and viewers hear them
         * confirmed ({@link #onConfirmedCollide}); and it publishes what its viewers draw ({@link Authoritative}).
         *
         * <pre>{@code
         * public static final CgVfxCue WAVE = VFX.cue("kamehameha", LOOK, CgEnergyWave::fromCue).authoritative().build();
         *
         * CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();   // server
         * wave.onCollide(c -> damage(c.entity()));
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
            CgNetType.Builder type = CgNetType.define(id).fields(CORE).fields(fields.toArray(new CgNetField<?>[0]))
                    .events(COLLIDED).range(range).keepRefused();
            if (look != null) {
                type.fields(look.schema().networked().toArray(new CgNetField<?>[0]));
                type.fields(lookFields(look.schema()).all);
            }
            if (persisted) type.persisted();
            CgVfxCue cue = new CgVfxCue(this, type.build());
            cue.type.onEvent(COLLIDED, cue::confirmed);
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
        if (!CgPlatform.get(CgServerPlayers.SERVICE).position(player, at)) return false;
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
        if (!CgPlatform.get(CgServerPlayers.SERVICE).position(entity, at)) {
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
     * server heard it: the hit that counted, where every viewer's copy agrees. The collision's effect is this client's
     * copy, or null where it builds none. Client thread.
     *
     * <pre>{@code
     * WAVE.onConfirmedCollide(CgEnergyWave.PART_BLAST, c -> decals.scorch(c.x(), c.y(), c.z(), c.radius()));
     * }</pre>
     */
    public CgVfxCue onConfirmedCollide(CgVfxCollisionListener listener) {
        confirmations.add(new Confirmation(null, Objects.requireNonNull(listener, "listener")));
        return this;
    }

    /** As {@link #onConfirmedCollide(CgVfxCollisionListener)}, hearing only what {@code part} strikes. */
    public CgVfxCue onConfirmedCollide(CgPart part, CgVfxCollisionListener listener) {
        confirmations.add(new Confirmation(Objects.requireNonNull(part, "part"), Objects.requireNonNull(listener, "listener")));
        return this;
    }

    private void confirmed(CgNetObject obj, Confirmed c) {
        if (confirmations.isEmpty()) return;
        CgPart part = null;
        for (Confirmation each : confirmations) {
            if (each.part != null && each.part.name().equals(c.part)) part = each.part;
        }
        CgVfxCueClient.View view = obj.behaviour();
        CgVfxCollision.Hit[] hits = CgVfxCollision.Hit.values();
        CgVfxCollision collision = CgVfxCollision.confirmed(view == null ? null : view.effect, part,
                c.hit < hits.length ? hits[c.hit] : CgVfxCollision.Hit.OTHER, c.entity, c.x, c.y, c.z, c.nx, c.ny, c.nz,
                c.radius);
        for (Confirmation each : confirmations) {
            if (each.part == null || each.part == part) each.listener.collided(collision);
        }
    }

    private static final class Confirmation {
        @Nullable
        final CgPart part;
        final CgVfxCollisionListener listener;

        Confirmation(@Nullable CgPart part, CgVfxCollisionListener listener) {
            this.part = part;
            this.listener = listener;
        }
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

    /** A schema's numbers and colours as float fields, one a component; shared by every cue playing it. */
    static final class LookFields {
        final CgVfxSchema schema;
        final CgVfxParam[] params;
        /** By parameter: its components' fields. */
        final CgNetField<Float>[][] fields;
        final CgNetField<?>[] all;
        /** A field's parameter, by identity. */
        final Map<CgNetField<?>, Integer> byField = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        LookFields(CgVfxSchema schema) {
            this.schema = schema;
            List<CgVfxParam> values = new ArrayList<>();
            for (CgVfxParam param : schema.params()) if (!param.isCurve() && !param.isShake()) values.add(param);
            params = values.toArray(new CgVfxParam[0]);
            fields = new CgNetField[params.length][];
            List<CgNetField<?>> all = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                String name = "look/" + fieldName(params[i].name());
                int size = params[i].size();
                fields[i] = new CgNetField[size];
                for (int k = 0; k < size; k++) {
                    fields[i][k] = CgNetField.ofFloat(size == 1 ? name : name + "/" + "rgba".charAt(k));
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
        void apply(int i, Play play, CgVfxEffect effect) {
            CgNetField<Float>[] f = fields[i];
            if (!play.has(f[0])) return;
            if (f.length == 1) {
                effect.set(params[i], play.getFloat(f[0]));
            } else {
                effect.set(params[i], play.getFloat(f[0]), play.getFloat(f[1]), play.getFloat(f[2]), play.getFloat(f[3]));
            }
        }
    }

    private static LookFields lookFields(CgVfxSchema schema) {
        synchronized (LOOK_FIELDS) {
            return LOOK_FIELDS.computeIfAbsent(schema, LookFields::new);
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
    boolean applyLook(Play play, CgVfxEffect effect, CgNetField<?> field) {
        if (look == null) return false;
        Integer i = look.byField.get(field);
        if (i == null) return false;
        if (effect.look().schema() == look.schema) look.apply(i, play, effect);
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
        int seed = ThreadLocalRandom.current().nextInt();
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
            int entityId = CgPlatform.get(CgServerPlayers.SERVICE).entityId(entity);
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
        public Play set(CgVfxParam param, float value) {
            CgNetField<Float>[] f = lookField(param, 1);
            return setFloat(f[0], value);
        }

        /** One of its look's colours. */
        public Play set(CgVfxParam param, float r, float g, float b, float a) {
            CgNetField<Float>[] f = lookField(param, 4);
            return setFloat(f[0], r).setFloat(f[1], g).setFloat(f[2], b).setFloat(f[3], a);
        }

        private CgNetField<Float>[] lookField(CgVfxParam param, int size) {
            CgNetField<Float>[] f = cue.look == null ? null : cue.look.of(param);
            if (f == null) throw new IllegalArgumentException(param + " is not a number or colour of cue '" + cue.id + "''s look");
            if (f.length != size) throw new IllegalArgumentException(param + " has " + f.length + " components, not " + size);
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
        public CgVfxEffect play() {
            return CgVfxCueClient.play(this);
        }

        /**
         * On a client: plays it here now, and asks the server to show everyone else ({@link CgVfxCue#fromClients}).
         * Refused, it plays on here alone. Null where nothing is registered under its id.
         */
        @Nullable
        public CgVfxEffect predict() {
            if (obj != null) throw new IllegalStateException("a play is sent once");
            CgNetObject predicted = cue.type.predict(followId >= 0 ? CgAnchor.onEntity(followId) : CgAnchor.at(x, y, z));
            attach(predicted);
            predicted.fire();
            CgVfxCueClient.View view = predicted.behaviour();
            return view == null ? null : view.effect;
        }

        /** Its effect, as {@code entry} makes it: seeded, following what it follows, its look's values set, at its age. */
        CgVfxEffect build(CgVfxRegistry.Entry entry) {
            CgVfxEffect effect = CgVfxEffect.seeded(seedUnit(), () -> entry.spawn(this));
            if (followId >= 0) {
                int at = point == null ? CgEntityAttachments.FEET : CgEntityAttachments.declare(point, CgEntityAttachments.CENTRE);
                if ((flags & KEEP_OFFSET) != 0) effect.followKeepingOffset(followId, at);
                else effect.follow(followId, at);
            }
            LookFields look = cue.look;
            if (look != null && effect.look().schema() == look.schema) {
                for (int i = 0; i < look.params.length; i++) look.apply(i, this, effect);
            }
            if (age > 0f) effect.startAt(age);
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
        public int following() {
            return followId;
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
     * CgEnergyWave beam = wave.effect();
     * beam.hold().aim(look.x, look.y, look.z);   // before its first tick, as on a client
     * beam.set(CgEnergyWave.RADIUS, 1.2f);       // a number or colour set on it reaches every viewer too
     * beam.fire();                               // what it publishes reaches every viewer
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
        public Playing set(CgVfxParam param, float value) {
            if (authority != null) authority.effect().set(param, value);
            else play.set(param, value);
            return this;
        }

        /** One of its look's colours. */
        public Playing set(CgVfxParam param, float r, float g, float b, float a) {
            if (authority != null) authority.effect().set(param, r, g, b, a);
            else play.set(param, r, g, b, a);
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
         * wave.onCollide(CgEnergyWave.PART_BEAM, c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) stagger(c.entity()); })
         *     .onCollide(CgEnergyWave.PART_BLAST, c -> explode(c.x(), c.y(), c.z(), c.radius()));
         * }</pre>
         *
         * It keeps hearing after {@link #stop()} while what is in flight plays out: a beam's blast comes then.
         */
        public Playing onCollide(CgVfxCollisionListener listener) {
            running().onCollide(listener);
            return this;
        }

        /** As {@link #onCollide(CgVfxCollisionListener)}, hearing only what {@code part} strikes. */
        public Playing onCollide(CgPart part, CgVfxCollisionListener listener) {
            running().onCollide(part, listener);
            return this;
        }

        private CgVfxEffect running() {
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
        public <E extends CgVfxEffect> E effect() {
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

    /** A collision of an authoritative cue's server copy, as it travels. */
    static final class Confirmed {
        final String part;
        final int hit, entity;
        final double x, y, z;
        final float nx, ny, nz, radius;

        Confirmed(String part, int hit, int entity, double x, double y, double z, float nx, float ny, float nz, float radius) {
            this.part = part;
            this.hit = hit;
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.radius = radius;
        }

        static Confirmed of(CgVfxCollision c) {
            return new Confirmed(c.part() == null ? "" : c.part().name(), c.hit().ordinal(), c.entity(), c.x(), c.y(), c.z(),
                    c.nx(), c.ny(), c.nz(), c.radius());
        }

        static final CgCodec<Confirmed> CODEC = new CgCodec<Confirmed>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, Confirmed c) {
                return CgCodecs.map(ops)
                        .optional("p", CgCodecs.STRING, c.part, "")
                        .field("h", CgCodecs.INT, c.hit)
                        .optional("e", CgCodecs.INT, c.entity, -1)
                        .field("x", CgCodecs.DOUBLE, c.x).field("y", CgCodecs.DOUBLE, c.y).field("z", CgCodecs.DOUBLE, c.z)
                        .field("nx", CgCodecs.FLOAT, c.nx).field("ny", CgCodecs.FLOAT, c.ny).field("nz", CgCodecs.FLOAT, c.nz)
                        .optional("r", CgCodecs.FLOAT, c.radius, 0f)
                        .build();
            }

            @Override
            public <T> Confirmed decode(CgDynamicOps<T> ops, T input) {
                CgCodecs.MapCodecReader<T> in = CgCodecs.read(ops, input);
                return new Confirmed(in.optional("p", CgCodecs.STRING, ""), in.field("h", CgCodecs.INT),
                        in.optional("e", CgCodecs.INT, -1), in.field("x", CgCodecs.DOUBLE), in.field("y", CgCodecs.DOUBLE),
                        in.field("z", CgCodecs.DOUBLE), in.field("nx", CgCodecs.FLOAT), in.field("ny", CgCodecs.FLOAT),
                        in.field("nz", CgCodecs.FLOAT), in.optional("r", CgCodecs.FLOAT, 0f));
            }
        };
    }
}
