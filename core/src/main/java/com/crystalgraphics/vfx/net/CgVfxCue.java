package com.crystalgraphics.vfx.net;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgReplicated;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.vfx.CgVfxCollisionListener;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.world.CgEntityAttachments;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An effect named for the network: what the server sends, by id, with a seed and parameters, for each client to build
 * and play. Declared once in common code with what it plays ({@code CgVfxOwner.cue(path, look, factory)}).
 * Server-safe: nothing here draws.
 *
 * <pre>{@code
 * // common, at init, both sides
 * public static final CgVfxCue<CgVfxParams> EXPLOSION = VFX.cue("explosion", Blast.LOOK,
 *         (look, cue) -> new Blast(look, cue.x(), cue.y(), cue.z()));
 * public static final CgVfxCue<CgVfxParams> WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(),
 *         CgEnergyWave::fromCue).range(96).authoritative();   // the server runs it too
 * }</pre>
 *
 * <p>From the server, a one-shot, gone once played, never seen by a player who joins later:</p>
 *
 * <pre>{@code
 * HIT_SPARK.at(level, x, y, z).toward(dx, dy, dz).follow(target, "rpgcore:body").params(p).send();
 * HIT_SPARK.at(level, x, y, z).params(p).except(attacker).send();       // the attacker already played it
 * HIT_SPARK.at(level, x, y, z).params(p).send(CgAudience.all());        // an audience of its own
 * }</pre>
 *
 * <p>Or a lasting effect, replicated with its start, so a player who comes into view starts it at its age:</p>
 *
 * <pre>{@code
 * CgVfxCue.Playing<CgVfxParams> beam = WAVE.at(level, x, y, z).follow(caster, "crystalgraphics:hands_forward")
 *         .target(tx, ty, tz).start();
 * beam.target(nx, ny, nz);    // every viewer, once a tick
 * beam.stop();                // gracefully: what is in flight plays out
 * CRATER.at(level, x, y, z).lifetime(60f).persist().start();   // saved with the world, gone after a minute
 * }</pre>
 *
 * <p>From a client, playing it now and asking the server to show everyone else (the server must allow it):</p>
 *
 * <pre>{@code
 * HIT_SPARK.at(x, y, z).params(p).predict();                                   // client
 * HIT_SPARK.fromClients((player, cue) -> reach(player, cue) < 6);              // server, at init
 * }</pre>
 *
 * <ul>
 *   <li><b>Declare at init on both sides</b>, before any player joins: a cue's messages ride a namespace the
 *       connection's hello announces.</li>
 *   <li>A send goes to the players tracking what it follows, else those within {@link #range} of its point, unless
 *       given an audience. It leaves at once, in order with the game's own packets that tick.</li>
 *   <li>Effects are cosmetic: what a player must not miss (damage, a state) is the game's to replicate. A cue shows
 *       it.</li>
 *   <li>Server calls on the server thread; {@link Play#play()} and {@link Play#predict()} on the client's.</li>
 * </ul>
 */
public final class CgVfxCue<P> {

    /** Blocks a send reaches round its point when it follows nothing: vanilla's level-event radius. */
    public static final float DEFAULT_RANGE = 64f;

    private static final Map<String, CgVfxCue<?>> CUES = new ConcurrentHashMap<>();

    private final String id;
    private final CgCodec<P> codec;
    private volatile float range = DEFAULT_RANGE;
    private volatile Policy<P> policy;
    private volatile boolean authoritative;
    final AtomicLong refused = new AtomicLong();

    private CgVfxCue(String id, CgCodec<P> codec) {
        this.id = id;
        this.codec = codec;
    }

    /** Declares {@code id} ({@code "namespace:path"}) with parameters {@code codec} encodes. Once per id. */
    public static <P> CgVfxCue<P> declare(String id, CgCodec<P> codec) {
        Objects.requireNonNull(codec, "codec");
        if (id.indexOf(':') < 0) throw new IllegalArgumentException("a cue id is namespace:path: '" + id + "'");
        CgVfxWire.ensure();
        CgVfxCue<P> cue = new CgVfxCue<>(id, codec);
        if (CUES.putIfAbsent(id, cue) != null) throw new IllegalStateException("cue '" + id + "' is already declared");
        return cue;
    }

    /** Declares {@code id} with {@link CgVfxParams}. */
    public static CgVfxCue<CgVfxParams> declare(String id) {
        return declare(id, CgVfxParams.CODEC);
    }

    /** The cue declared as {@code id}, or null. */
    public static CgVfxCue<?> get(String id) {
        return CUES.get(id);
    }

    public String id() {
        return id;
    }

    public CgCodec<P> codec() {
        return codec;
    }

    /** How far round its point a send reaches when it follows nothing, and a lasting one is seen. */
    public CgVfxCue<P> range(float blocks) {
        if (!(blocks > 0)) throw new IllegalArgumentException("range must be positive: " + blocks);
        range = blocks;
        return this;
    }

    public float range() {
        return range;
    }

    /**
     * Lets clients {@linkplain Play#predict() predict} it: each play a client asks for is passed to {@code policy}, after
     * a rate limit and a check that its point is within {@link #range} of the player, and shown to everyone else when it
     * says yes. Nobody may until this is called. Server, at init.
     */
    public CgVfxCue<P> fromClients(Policy<P> policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    Policy<P> policy() {
        return policy;
    }

    /**
     * Runs its effect on the server too, for as long as a {@linkplain Play#start() lasting} play lasts: the same class its
     * viewers play, headless against the server's level ({@code CgVfxAuthority}). It decides where the effect goes and
     * what it strikes; the server hears its collisions ({@link Playing#onCollide}); and, through {@link Authoritative},
     * it publishes what its viewers draw. Declared with what it plays, so the server can build it.
     *
     * <pre>{@code
     * public static final CgVfxCue<CgVfxParams> WAVE = VFX.cue("kamehameha", LOOK, CgEnergyWave::fromCue).authoritative();
     *
     * CgVfxCue.Playing<CgVfxParams> wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();   // server
     * wave.onCollide(c -> damage(c.entity()));
     * }</pre>
     *
     * <ul>
     *   <li>Lasting plays only: a one-shot runs nowhere but on its viewers.</li>
     *   <li>Its {@code at(level, ...)} is a level or an entity in one, whose world it tests ({@code CgLevelQueries}).</li>
     *   <li>Not with {@code persist()}.</li>
     * </ul>
     */
    public CgVfxCue<P> authoritative() {
        authoritative = true;
        return this;
    }

    /** Whether the server runs its effect too ({@link #authoritative()}). */
    public boolean hasAuthority() {
        return authoritative;
    }

    /** Plays clients asked for and were refused: no policy, over the rate, out of reach, or the policy said no. */
    public long refused() {
        return refused.get();
    }

    /** On the server: a play at a point in a level ({@code level} a level, an entity in it, or a dimension id). */
    public Play<P> at(Object level, double x, double y, double z) {
        Objects.requireNonNull(level, "level");
        Play<P> play = new Play<>(this, x, y, z);
        play.level = level;
        play.seed = ThreadLocalRandom.current().nextInt();
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
    public Play<P> on(Object entity, int point) {
        double[] at = new double[3];
        if (!CgPlatform.get(CgServerPlayers.SERVICE).position(entity, at)) {
            throw new IllegalArgumentException("not an entity: " + entity);
        }
        return at(entity, at[0], at[1], at[2]).follow(entity, point);
    }

    /** On a client: a play at a point, to {@link Play#play()} here or {@link Play#predict()}. */
    public Play<P> at(double x, double y, double z) {
        Play<P> play = new Play<>(this, x, y, z);
        play.seed = ThreadLocalRandom.current().nextInt();
        return play;
    }

    /** Who may predict a cue, and where. */
    @FunctionalInterface
    public interface Policy<P> {
        /** Whether {@code player} (the loader's player) may show {@code play} to everyone else. */
        boolean allow(Object player, Play<P> play);
    }

    /**
     * An effect that takes a lasting cue's changes itself: a new target, new parameters. Without it, a change sets
     * {@link CgVfxParams} by name and nothing else.
     *
     * <pre>{@code
     * public final class Beam extends CgVfxEffect implements CgVfxCue.Live<CgVfxParams> {
     *     public void cueChanged(CgVfxCue.Play<CgVfxParams> cue) {
     *         if (cue.hasTarget()) target(cue.tx(), cue.ty(), cue.tz());
     *     }
     * }
     * }</pre>
     */
    public interface Live<P> {
        void cueChanged(Play<P> cue);
    }

    /**
     * An effect whose server half decides what its viewers draw ({@link CgVfxCue#authoritative()}): after each server
     * tick it writes that into its cue, which reaches every viewer as a change, where {@link Live} takes it. Driven on the
     * server like a local effect ({@link Playing#effect()}), it publishes what that changed too.
     *
     * <pre>{@code
     * public boolean publish(CgVfxCue.Play<Object> cue) {
     *     if (endX == cue.tx() && endY == cue.ty() && endZ == cue.tz()) return false;
     *     cue.target(endX, endY, endZ);                // where the beam truly ends
     *     return true;
     * }
     * }</pre>
     *
     * Return false when nothing changed: a true sends the cue to every viewer.
     */
    public interface Authoritative<P> {
        boolean publish(Play<P> cue);
    }

    @Override
    public String toString() {
        return id;
    }

    static void resetForTesting() {
        CUES.clear();
    }

    // ── One play ────────────────────────────────────────────────────────────────────────────────

    /**
     * One play of a cue: built on the server and sent, or received by a client and handed to its factory. Read on the
     * client with the getters; build on the server with the rest.
     */
    public static final class Play<P> {
        /** {@link #aim}'s bits: a direction, a target, or both. */
        static final int NONE = 0, TOWARD = 1, TARGET = 2;

        final CgVfxCue<P> cue;
        /** On a client, the id it arrived with: its cue may not be declared there. */
        final String id;
        double x, y, z;
        int aim;
        /** Its direction and its target, each valid while its bit of {@link #aim} is set. */
        double ax, ay, az, tgx, tgy, tgz;
        int followId = -1;
        String point;
        P params;
        int seed;
        float age, lifetime;
        boolean persist, killed, keepOffset;
        /** The entity its target is on, or -1: {@link #struck(int)}. */
        int struck = -1;

        // Server only, never sent.
        Object level;
        Object followEntity;
        UUID except;
        /** Started as lasting state, at server tick {@code startTick}. */
        boolean lasting;
        long startTick;
        /** A kill has gone out to its viewers: the next tick removes it. */
        boolean killSent;
        /** Built for the server's run of its effect ({@link #authoritative()}). */
        boolean authoritative;

        Play(CgVfxCue<P> cue, double x, double y, double z) {
            this(cue, cue.id, x, y, z);
        }

        Play(CgVfxCue<P> cue, String id, double x, double y, double z) {
            this.cue = cue;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        // ── Building ──

        /** Facing a direction. With a {@link #target} too, it faces this way and goes there: a beam aimed by a player. */
        public Play<P> toward(double dx, double dy, double dz) {
            aim |= TOWARD;
            ax = dx;
            ay = dy;
            az = dz;
            return this;
        }

        /** Aimed at a point: where a beam homes and blasts. */
        public Play<P> target(double tx, double ty, double tz) {
            aim |= TARGET;
            tgx = tx;
            tgy = ty;
            tgz = tz;
            return this;
        }

        /**
         * Following an entity (the loader's own, server side) at the attachment point named {@code point}
         * ({@code "crystalgraphics:hands_forward"}, or one a mod declared; null for its feet). The point is resolved on
         * each client from the entity as drawn. A send then defaults to the players tracking it.
         */
        public Play<P> follow(Object entity, String point) {
            CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
            int entityId = players.entityId(entity);
            if (entityId < 0) throw new IllegalArgumentException("not an entity: " + entity);
            followEntity = entity;
            followId = entityId;
            this.point = point;
            return this;
        }

        /** As {@link #follow(Object, String)}, at a point by id ({@code CgEntityAttachments.HANDS_FORWARD}). */
        public Play<P> follow(Object entity, int point) {
            return follow(entity, CgEntityAttachments.name(point));
        }

        /** Following entity {@code entityId} by its network id: on a client, or where the server has no entity object. */
        public Play<P> follow(int entityId, String point) {
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
        public Play<P> keepOffset() {
            keepOffset = true;
            return this;
        }

        public Play<P> params(P params) {
            this.params = params;
            return this;
        }

        /**
         * The entity its target is on (its network id), or -1 for a block or nothing: what an authority publishes with
         * the target ({@link Authoritative}), so a viewer's feedback knows what was struck.
         */
        public Play<P> struck(int entity) {
            struck = entity;
            return this;
        }

        /** Its random seed: chosen for it, set only to replay one exactly. */
        public Play<P> seed(int seed) {
            this.seed = seed;
            return this;
        }

        /** Sent to everyone in its audience but {@code player}, who has already shown it (the loader's player). */
        public Play<P> except(Object player) {
            UUID id = CgPlatform.get(CgServerPlayers.SERVICE).playerId(player);
            if (id == null) throw new IllegalArgumentException("not a player: " + player);
            except = id;
            return this;
        }

        /** As {@link #except(Object)}, by profile id. */
        public Play<P> except(UUID playerId) {
            except = Objects.requireNonNull(playerId, "playerId");
            return this;
        }

        /** A lasting one stops by itself this long after it started. */
        public Play<P> lifetime(float seconds) {
            lifetime = Math.max(0f, seconds);
            return this;
        }

        /** A lasting one is saved with the world, and resumes at its age when the server starts again. Not with follow. */
        public Play<P> persist() {
            persist = true;
            return this;
        }

        // ── Sending ──

        /** On the server: to the players tracking what it follows, else within its cue's range of its point. */
        public void send() {
            send(defaultAudience());
        }

        /** On the server: to {@code audience}, less any {@link #except}. */
        public void send(CgAudience audience) {
            requireServer();
            CgVfxWire.send(this, except == null ? audience : CgAudience.except(audience, except));
        }

        /** On the server: plays it as lasting state, which every viewer sees from now on, a late one at its age. */
        public Playing<P> start() {
            requireServer();
            if (persist && followEntity != null) throw new IllegalStateException("a followed cue cannot persist");
            if (persist && cue.authoritative) throw new IllegalStateException("an authoritative cue cannot persist");
            lasting = true;
            startTick = CgNetwork.serverTicks();
            CgReplicated.Handle<Play<?>> handle = CgVfxWire.start(this);
            return new Playing<>(handle, cue.authoritative ? CgVfxWire.run(this, handle) : null);
        }

        /** On a client: plays it here alone. Null where nothing is registered under its id. */
        public CgVfxEffect play() {
            return CgVfxCueClient.play(this);
        }

        /** On a client: plays it here, and asks the server to show everyone else ({@link CgVfxCue#fromClients}). */
        public CgVfxEffect predict() {
            CgVfxEffect effect = CgVfxCueClient.play(this);
            CgVfxWire.predict(this);
            return effect;
        }

        CgAudience defaultAudience() {
            if (followEntity != null) return CgAudience.trackingAndSelf(followEntity);
            return CgAudience.near(level, x, y, z, cue != null ? cue.range : DEFAULT_RANGE);
        }

        private void requireServer() {
            if (level == null) throw new IllegalStateException("send and start a play made by at(level, x, y, z)");
        }

        // ── Reading ──

        public String id() {
            return id;
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
            return aim != NONE;
        }

        public boolean hasTarget() {
            return (aim & TARGET) != 0;
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
            if (aim == NONE) return 0;
            boolean given = (aim & TOWARD) != 0;
            double vx = given ? ax : tgx - x, vy = given ? ay : tgy - y, vz = given ? az : tgz - z;
            double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (length < 1e-9) return 0;
            return (axis == 0 ? vx : axis == 1 ? vy : vz) / length;
        }

        /**
         * Whether it is building the effect the server runs ({@link CgVfxCue#authoritative()}), rather than a viewer's: a
         * factory serving both sides branches on it.
         *
         * <pre>{@code
         * if (cue.authoritative() && !cue.hasTarget()) wave.collide();   // the server finds the end; viewers home on it
         * }</pre>
         */
        public boolean authoritative() {
            return authoritative;
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
        public String point() {
            return point;
        }

        /** Whether it keeps its offset from the point it follows ({@link #keepOffset}). */
        public boolean keepsOffset() {
            return keepOffset;
        }

        /** Its parameters; null when the sender gave none, or its cue is not declared on this side. */
        public P params() {
            return params;
        }

        public int seed() {
            return seed;
        }

        /** Its seed as an effect's: 0 to 1. */
        public float seedUnit() {
            return (seed >>> 8) * (1f / (1 << 24));
        }

        /** Seconds it had played when sent: 0 for a one-shot, the age now for a lasting one a viewer starts late. */
        public float age() {
            return age;
        }

        public float lifetime() {
            return lifetime;
        }

        @Override
        public String toString() {
            return id + " at " + (float) x + ", " + (float) y + ", " + (float) z + (params == null ? "" : " " + params);
        }
    }

    // ── A lasting play ──────────────────────────────────────────────────────────────────────────

    /**
     * A lasting cue playing on the server. Server thread.
     *
     * <pre>{@code
     * CgVfxCue.Playing<CgVfxParams> aura = AURA.on(player, CgEntityAttachments.CENTRE).start();
     * aura.params(CgVfxParams.of().set("level", 3f));   // every viewer, at the end of the tick
     * aura.stop();
     * }</pre>
     *
     * Where the cue is {@linkplain CgVfxCue#authoritative() authoritative}, drive its effect instead, as a local one:
     *
     * <pre>{@code
     * CgVfxCue.Playing<CgVfxParams> wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
     * CgEnergyWave beam = wave.effect();
     * beam.hold().aim(look.x, look.y, look.z);   // before its first tick, as on a client
     * beam.fire();                               // what it publishes reaches every viewer
     * }</pre>
     */
    public static final class Playing<P> {
        private final CgReplicated.Handle<Play<?>> handle;
        /** Its effect run on the server, or null when its cue has no authority. */
        private final CgVfxWire.Running running;

        Playing(CgReplicated.Handle<Play<?>> handle, CgVfxWire.Running running) {
            this.handle = handle;
            this.running = running;
        }

        @SuppressWarnings("unchecked")
        private Play<P> play() {
            return (Play<P>) handle.value();
        }

        /** Re-aims it at a point; viewers hear it at the end of the tick, and nothing when it is where it was. */
        public Playing<P> target(double tx, double ty, double tz) {
            Play<P> play = play();
            if ((play.aim & Play.TARGET) != 0 && play.tgx == tx && play.tgy == ty && play.tgz == tz) return this;
            play.target(tx, ty, tz);
            return changed();
        }

        /** Re-aims it along a direction; with a target too, it faces this way and goes there. Unchanged sends nothing. */
        public Playing<P> toward(double dx, double dy, double dz) {
            Play<P> play = play();
            if ((play.aim & Play.TOWARD) != 0 && play.ax == dx && play.ay == dy && play.az == dz) return this;
            play.toward(dx, dy, dz);
            return changed();
        }

        public Playing<P> params(P params) {
            play().params(params);
            return changed();
        }

        private Playing<P> changed() {
            handle.set(handle.value());
            if (running != null) CgVfxWire.changed(running);
            return this;
        }

        /**
         * Hears what its effect strikes on the server, where its cue is {@linkplain CgVfxCue#authoritative()
         * authoritative}: damage from where the effect truly is. Server thread.
         *
         * <pre>{@code
         * wave.onCollide(c -> {
         *     if (CgEnergyWave.PART_BEAM.equals(c.part()) && c.hit() == CgVfxCollision.Hit.ENTITY) stagger(c.entity());
         *     if (CgEnergyWave.PART_BLAST.equals(c.part())) explode(c.x(), c.y(), c.z(), c.radius());
         * });
         * }</pre>
         *
         * It keeps hearing after {@link #stop()} while what is in flight plays out: a beam's blast comes then.
         */
        public Playing<P> onCollide(CgVfxCollisionListener listener) {
            if (running == null) {
                throw new IllegalStateException("cue '" + play().id + "' runs nowhere on the server: declare it authoritative()");
            }
            running.authority.effect().onCollide(listener);
            return this;
        }

        /**
         * The effect the server runs for it, to drive as a local one and read (a wave's {@code fire()},
         * {@code hitEntity()}); null unless its cue is {@linkplain CgVfxCue#authoritative() authoritative}. Stopping it
         * ends the cue.
         */
        @SuppressWarnings("unchecked")
        public <E extends CgVfxEffect> E effect() {
            return running == null ? null : (E) running.authority.effect();
        }

        /** Moves its point: where it plays, and who is in range of it. */
        public Playing<P> moveTo(double x, double y, double z) {
            Play<P> play = play();
            play.x = x;
            play.y = y;
            play.z = z;
            handle.moveTo(x, y, z);
            handle.set(handle.value());
            return this;
        }

        /** Ends it gracefully on every viewer: emission stops, what is in flight plays out. */
        public void stop() {
            handle.remove();
            if (running != null) running.authority.effect().stop();
        }

        /** Ends it at once on every viewer, particles and all. */
        public void kill() {
            if (handle.isRemoved()) return;
            play().killed = true;
            handle.set(handle.value());
            if (running != null) running.authority.effect().kill();
        }

        /** Whether it still plays: not stopped, killed or past its lifetime. */
        public boolean playing() {
            return !handle.isRemoved() && !play().killed;
        }

        /** Seconds since it started. */
        public float age() {
            return CgVfxWire.ageOf(play());
        }

        @Override
        public String toString() {
            return "Playing[" + play() + "]";
        }
    }
}
