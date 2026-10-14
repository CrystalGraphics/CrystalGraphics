package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitClaim;
import com.crystalgraphics.collision.hit.CgSentHit;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetCommand;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.CgEntityAttachments;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A hitbox the server starts and every client that sees it runs too: the server's hits are the real ones, each viewer's
 * own copy hits at once for what must feel instant, and the server's hits reach the viewers for what must be true. A
 * networked object type ({@link CgNetType}) whose objects are hitboxes, built on each side from the same factory.
 *
 * <pre>{@code
 * // common, at init, both sides
 * public static final CgNetField<Float> RADIUS = CgNetField.ofFloat("radius");
 * public static final CgHitboxType SLAM = CgHitboxType.define("mymod:slam").fields(RADIUS)
 *         .hitbox(obj -> CgHitbox.of(CgRing.front(1.2, 0.6)).radius(0, obj.getFloat(RADIUS), 12)
 *                 .query(CgCollisionQuery::living))
 *         .build();
 *
 * SLAM.onHit((obj, h) -> damage(h.entity()));                // server, at init: the only place damage happens
 * SLAM.onPredictedHit((obj, h) -> sparks(h.x(), h.y(), h.z()));   // client: its own copy's hits, at once
 * SLAM.onConfirmedHit((obj, h) -> hitMarker(h.entity()));    // client: the server's hits, as they arrive
 *
 * SLAM.spawn(CgAnchor.at(level, x, y, z)).source(caster).setFloat(RADIUS, 12f).start();   // server
 * }</pre>
 *
 * On an entity, at an attachment point, turned with its look:
 *
 * <pre>{@code
 * SWING.spawn(CgAnchor.on(player)).point(CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK)
 *         .source(player).start();
 * }</pre>
 *
 * The attacker's own client shows it before the server answers, when the server allows it:
 *
 * <pre>{@code
 * SWING.acceptPredictionsFrom((peer, obj) -> obj.entityId() == entityOf(peer));   // server, at init
 * SWING.predict(CgAnchor.onEntity(myId)).point(CgEntityAttachments.RIGHT_HAND_FORWARD).source(myId).start();
 * }</pre>
 *
 * Melee that feels instant at any ping: {@link Builder#claimed()}, where the owner's client decides what it hit and the
 * server checks each claim, as Minecraft checks an attack packet:
 *
 * <pre>{@code
 * public static final CgHitboxType PUNCH = CgHitboxType.define("mymod:punch")
 *         .hitbox(obj -> CgHitbox.of(CgSphere.of(0.45)).activeFor(6).once().query(CgCollisionQuery::living))
 *         .claimed()
 *         .build();
 *
 * PUNCH.acceptPredictionsFrom((peer, obj) -> obj.entityId() == CgEntityQueries.idOf(peer.player()));  // server
 * PUNCH.onHit((obj, h) -> damage(obj.owner(), h.entity()));                    // server: each claim it accepts
 * PUNCH.onPredictedHit((obj, h) -> sparks(h.x(), h.y(), h.z()));               // client: at once
 * PUNCH.onRefusedHere((obj, claim) -> HitStop.cancel());                       // the owner: one the server refused
 *
 * PUNCH.predict(CgAnchor.onEntity(me)).point(CgEntityAttachments.RIGHT_HAND_FORWARD).source(me).start();   // client
 * }</pre>
 *
 * <ul>
 *   <li>The factory runs on every side and must build the same hitbox from the object's fields: shape, sizes, rules.
 *       Placement, facing and source come from the spawn. Hits are heard here, never through the hitbox's own
 *       listeners.</li>
 *   <li>It ends when its hitbox does ({@code activeFor}), when its anchor's entity goes, when its source goes
 *       (unless {@link Builder#outlivesSource()}), or on {@code despawn()}: gracefully, so the last tick's hits
 *       still arrive.</li>
 *   <li>A viewer arriving late catches up silently: its copy re-runs the last two seconds without a listener. One
 *       arriving within half a second of the spawn (the delivery's own delay) replays it aloud instead.</li>
 *   <li>Install {@link #onPredictedHit} and {@link #onConfirmedHit} from client code. Time is server ticks.</li>
 * </ul>
 */
public final class CgHitboxType {

    /** A late joiner re-runs at most this many ticks of a hitbox's past; earlier ticks are skipped. */
    static final int CATCH_UP = 40;
    /** A copy arriving at most this old replays its past aloud, its hits late but shown: the spawn's own delay. */
    static final int QUIET_AFTER = 10;

    static final CgNetField<Integer> SOURCE = CgNetField.ofInt("hitbox/source").fallback(-1).initialOnly();
    static final CgNetField<String> POINT = CgNetField.of("hitbox/point", CgCodecs.STRING).initialOnly();
    static final CgNetField<Integer> FACING = CgNetField.ofInt("hitbox/facing").fallback(-1).initialOnly();
    /** A fixed rotation, set at the spawn or by {@link #pose}. */
    static final CgNetField<Float> QX = CgNetField.ofFloat("hitbox/qx"), QY = CgNetField.ofFloat("hitbox/qy"),
            QZ = CgNetField.ofFloat("hitbox/qz"), QW = CgNetField.ofFloat("hitbox/qw").fallback(1f);
    private static final CgNetField<?>[] CORE = {SOURCE, POINT, FACING, QX, QY, QZ, QW};

    static final CgNetEvent<CgSentHit> HIT = CgNetEvent.of("hitbox/hit", CgSentHit.wire());
    /** A claimed type's owner's hit; a sweep may claim several targets in one tick. */
    static final CgNetCommand<CgHitClaim> CLAIM = CgNetCommand.of("hitbox/claim", CgHitClaim.wire()).perSecond(40).burst(40);
    /** A claim the server refused, to its owner alone. */
    static final CgNetEvent<CgHitClaim> REFUSED = CgNetEvent.of("hitbox/refused", CgHitClaim.wire());
    /** Claims a prediction holds until the server makes it real. */
    private static final int QUEUE = 8;
    /** Doubles per tick in a claimed server copy's ring: box, eyes, the hitbox's age. */
    private static final int PLACE = 10;

    private final CgNetType type;
    private final Function<CgNetObject, CgHitbox> factory;
    private final boolean outlivesSource;
    private final boolean claimed;
    private final int claimWindow;
    private final double claimPadding, claimPaddingPerSpeed;
    private final List<BiConsumer<CgNetObject, CgHit>> hits = new CopyOnWriteArrayList<>(),
            predicted = new CopyOnWriteArrayList<>(), confirmed = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<CgNetObject, CgHitClaim>> refusedClaim = new CopyOnWriteArrayList<>(),
            refusedHere = new CopyOnWriteArrayList<>();
    private final long[] refusals = new long[CgHitClaim.Refusal.values().length];
    /** The confirmed hit handed to listeners, read back from the wire; client thread. */
    private final CgHit arrived = new CgHit();
    /** A claimed target's pose, for its speed; server thread. */
    private final double[] speedPose = new double[CgEntityQuery.POSE_LENGTH];

    private CgHitboxType(Builder b, CgNetType type) {
        this.type = type;
        this.factory = b.factory;
        this.outlivesSource = b.outlivesSource;
        this.claimed = b.claimed;
        this.claimWindow = b.claimWindow;
        this.claimPadding = b.claimPadding;
        this.claimPaddingPerSpeed = b.claimPaddingPerSpeed;
        type.onServer(obj -> new Side(obj, true));
        type.onClient(obj -> new Side(obj, false));
        type.onEvent(HIT, this::confirmed);
        if (claimed) {
            type.onCommand(CLAIM, (obj, sender, claim) -> {
                Side side = side(obj);
                if (side != null && side.server) side.judge(claim);
            });
            type.onEvent(REFUSED, (obj, claim) -> {
                for (BiConsumer<CgNetObject, CgHitClaim> listener : refusedHere) listener.accept(obj, claim);
            });
        }
    }

    /** Starts declaring {@code id}, {@code "namespace:path"}. */
    public static Builder define(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private final List<CgNetField<?>> fields = new ArrayList<>();
        private Function<CgNetObject, CgHitbox> factory;
        private double range = Double.NaN;
        private boolean outlivesSource;
        private boolean claimed;
        private int claimWindow = 20;
        private double claimPadding = 3, claimPaddingPerSpeed;

        private Builder(String id) {
            this.id = id;
        }

        /** Its own fields, read by the factory. */
        public Builder fields(CgNetField<?>... fields) {
            for (CgNetField<?> f : fields) this.fields.add(Objects.requireNonNull(f, "field"));
            return this;
        }

        /** What it is on every side: a hitbox built from the object, not yet started. */
        public Builder hitbox(Function<CgNetObject, CgHitbox> factory) {
            this.factory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /** Seen by players within {@code blocks} of a point anchor; the whole dimension without one. */
        public Builder range(double blocks) {
            range = blocks;
            return this;
        }

        /** Keeps running when its source is gone: a trap that outlives its maker. */
        public Builder outlivesSource() {
            outlivesSource = true;
            return this;
        }

        /**
         * Its owner's client decides what it hit, and the server checks each claim: melee that lands where the attacker
         * saw it at any ping. The server's own copy hits nothing; it keeps where the hitbox was, to check against.
         *
         * <pre>{@code
         * STRIKE.spawn(CgAnchor.on(attacker)).source(attacker).start();   // a player's claims; a mob's hits the server's
         * }</pre>
         *
         * <ul>
         *   <li>The owner is the predicting player for a {@link CgHitboxType#predict prediction}, else the spawn's
         *       {@link Spawn#owner}, else a player given as {@link Spawn#source(Object)}. One with none is the server's,
         *       hitting as an unclaimed type does: players and mobs share one type.</li>
         *   <li>A claim counts when its target is alive, its age is within {@link #claimWindow} of the server's copy,
         *       the target's box grown by {@link #claimPadding} touched where the hitbox was over that window, and the
         *       hitbox's own rules and query allow it there. Each refusal is counted: {@link #refusedClaims}.</li>
         *   <li>On the server it outlives its hitbox by the window, so a slow connection's claim still finds it.</li>
         * </ul>
         */
        public Builder claimed() {
            claimed = true;
            return this;
        }

        /** How many ticks of latency a claim may lag the server by, 20 unless said: the second vanilla tolerates. */
        public Builder claimWindow(int ticks) {
            if (ticks < 1) throw new IllegalArgumentException("claimWindow(" + ticks + ")");
            claimWindow = ticks;
            return this;
        }

        /**
         * How far, in blocks, a claimed target may stand from where the server saw the hitbox: 3 unless said, the
         * padding Forge gives an attack's reach.
         */
        public Builder claimPadding(double blocks) {
            if (!(blocks >= 0) || Double.isInfinite(blocks)) throw new IllegalArgumentException("claimPadding(" + blocks + ")");
            claimPadding = blocks;
            return this;
        }

        /**
         * Grows the padding by the target's speed over {@code ticks}, its distance moved in the last tick: a flier's
         * honest claim still lands where a fixed padding tight enough to mean something would refuse it.
         *
         * <pre>{@code
         * .claimed().claimPadding(2).claimPaddingPerSpeed(4)   // a target moving 3 blocks a tick: 2 + 3 * 4
         * }</pre>
         */
        public Builder claimPaddingPerSpeed(double ticks) {
            if (!(ticks >= 0) || Double.isInfinite(ticks)) throw new IllegalArgumentException("claimPaddingPerSpeed(" + ticks + ")");
            claimPaddingPerSpeed = ticks;
            return this;
        }

        public CgHitboxType build() {
            if (factory == null) throw new IllegalStateException(id + " needs hitbox(factory)");
            CgNetType.Builder t = CgNetType.define(id).fields(CORE).fields(fields.toArray(new CgNetField<?>[0]))
                    .events(HIT);
            if (claimed) t.events(REFUSED).commands(CLAIM);
            if (!Double.isNaN(range)) t.range(range);
            return new CgHitboxType(this, t.build());
        }
    }

    // ---- listeners

    /**
     * Server: each hit of the server's hitbox, the authoritative one; on a {@link Builder#claimed claimed} type each
     * claim it accepts, {@link #ticksLate} after it was made.
     */
    public CgHitboxType onHit(BiConsumer<CgNetObject, CgHit> listener) {
        hits.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Client: each hit of this client's own copy, at once, against the targets where it sees them. Cosmetic. */
    public CgHitboxType onPredictedHit(BiConsumer<CgNetObject, CgHit> listener) {
        predicted.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Client: each of the server's hits, as it arrives, with its entity, part, point and exposure. */
    public CgHitboxType onConfirmedHit(BiConsumer<CgNetObject, CgHit> listener) {
        confirmed.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Server, a {@link Builder#claimed claimed} type: each claim it refused, with its {@link CgHitClaim#reason()}. */
    public CgHitboxType onRefusedClaim(BiConsumer<CgNetObject, CgHitClaim> listener) {
        claimedOnly("onRefusedClaim");
        refusedClaim.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Client, a {@link Builder#claimed claimed} type: each of this client's claims the server refused, to undo what its
     * prediction showed (a hit-stop, a damage number). Its owner alone hears it.
     */
    public CgHitboxType onRefusedHere(BiConsumer<CgNetObject, CgHitClaim> listener) {
        claimedOnly("onRefusedHere");
        refusedHere.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Server, inside {@link #onHit}: how many ticks ago the hit being dealt was made, to judge a defence as of the
     * swing. 0 for one the server found itself.
     *
     * <pre>{@code
     * STRIKE.onHit((obj, h) -> {
     *     if (guardHistory.wasGuarding(h.entity(), now - STRIKE.ticksLate(obj))) guarded(h); else land(h);
     * });
     * }</pre>
     */
    public int ticksLate(CgNetObject obj) {
        Side side = side(obj);
        return side == null ? 0 : side.late;
    }

    /** Server: claims refused for {@code reason} since the type was declared. */
    public long refusedClaims(CgHitClaim.Refusal reason) {
        return refusals[reason.ordinal()];
    }

    private void claimedOnly(String call) {
        if (!claimed) throw new IllegalStateException(call + " is a claimed type's: " + type.name() + " is not claimed()");
    }

    // ---- starting one

    /** Server: one at {@code anchor}, to configure then {@link Spawn#start()}. */
    public Spawn spawn(CgAnchor anchor) {
        return new Spawn(type.spawn(anchor), claimed);
    }

    /** Client: one shown here now, and asked of the server ({@link #acceptPredictionsFrom}). */
    public Spawn predict(CgAnchor anchor) {
        return new Spawn(type.predict(anchor), claimed);
    }

    /**
     * Server, at init: which of the clients' predictions to make real. The rule runs before the factory, with the
     * client's fields written: set a field there and the server's hitbox is built from the server's value.
     *
     * <pre>{@code
     * STRIKE.acceptPredictionsFrom((peer, obj) -> {
     *     obj.setFloat(REACH, reachOf(peer.player())).local(swingOf(peer));   // the server's reach, not the client's
     *     return true;
     * });
     * }</pre>
     */
    public CgHitboxType acceptPredictionsFrom(BiPredicate<CgPeer, CgNetObject> rule) {
        type.acceptPredictionsFrom(rule);
        return this;
    }

    /**
     * Server: places a point-anchored hitbox from code only the server runs, each tick it changes; the position and
     * rotation reach its viewers as that tick's change.
     */
    public static void pose(CgNetObject obj, CgPose pose) {
        obj.moveTo(pose.x, pose.y, pose.z);
        Side side = side(obj);
        Quaterniond q = side == null ? new Quaterniond() : side.q;
        pose.getRotation(q);
        obj.setFloat(QX, (float) q.x).setFloat(QY, (float) q.y).setFloat(QZ, (float) q.z).setFloat(QW, (float) q.w);
    }

    /** This side's hitbox of {@code obj}; null before its first tick here, or for another type's object. */
    @Nullable
    public static CgHitbox hitbox(CgNetObject obj) {
        Side side = side(obj);
        return side == null ? null : side.hitbox;
    }

    /** The networked object type underneath: what a client's visible objects and the test rig are asked by. */
    public CgNetType type() {
        return type;
    }

    @Nullable
    private static Side side(CgNetObject obj) {
        Object behaviour = obj.behaviour();
        return behaviour instanceof Side ? (Side) behaviour : null;
    }

    private void confirmed(CgNetObject obj, CgSentHit sent) {
        Side side = side(obj);
        sent.read(arrived, side == null ? null : side.hitbox.partName(sent.part()));
        for (BiConsumer<CgNetObject, CgHit> listener : confirmed) listener.accept(obj, arrived);
    }

    @Override
    public String toString() {
        return "CgHitboxType[" + type.name() + "]";
    }

    /** A hitbox before it starts: where it follows, which way it faces, whose it is, and its fields. */
    public static final class Spawn {
        private final CgNetObject obj;
        private final boolean claimed;

        private Spawn(CgNetObject obj, boolean claimed) {
            this.obj = obj;
            this.claimed = claimed;
        }

        /**
         * Server: whose it is, an entity: never hit, with its vehicle and passengers, and it ends with them. On a
         * {@link Builder#claimed claimed} type a player source is its owner too, unless one was given.
         */
        public Spawn source(Object entity) {
            if (claimed && obj.isServer() && obj.owner() == null) {
                UUID player = CgPlayers.idOf(entity);
                if (player != null) obj.owner(player);
            }
            return source(CgEntityQueries.idOf(entity));
        }

        /** Server: the player it belongs to, a player entity or a profile id: a claimed type's claims are theirs. */
        public Spawn owner(Object playerOrId) {
            obj.owner(playerOrId);
            return this;
        }

        /** Whose it is, by network id. */
        public Spawn source(int networkId) {
            obj.setInt(SOURCE, networkId);
            return this;
        }

        /** On an entity anchor: the {@link CgEntityAttachments} point it sits at; its feet by default. */
        public Spawn point(int attachmentPoint) {
            obj.set(POINT, CgEntityAttachments.name(attachmentPoint));
            return this;
        }

        /** Which way it turns, over the factory's choice. */
        public Spawn facing(CgHitbox.Facing facing) {
            obj.setInt(FACING, facing.ordinal());
            return this;
        }

        /** A fixed turn, as {@link CgPose#facing}: yaw and pitch in radians. */
        public Spawn facing(double yaw, double pitch) {
            return rotation(new CgPose().facing(yaw, pitch).getRotation(new Quaterniond()));
        }

        /** A fixed turn. */
        public Spawn rotation(Quaterniondc q) {
            obj.setFloat(QX, (float) q.x()).setFloat(QY, (float) q.y()).setFloat(QZ, (float) q.z())
                    .setFloat(QW, (float) q.w());
            return facing(CgHitbox.Facing.FIXED);
        }

        /** Kept with it on this side, never sent: {@link CgNetObject#local(Object)}. */
        public Spawn local(@Nullable Object value) {
            obj.local(value);
            return this;
        }

        /** One of its own fields, read by the factory on every side. */
        public <V> Spawn set(CgNetField<V> field, V value) {
            obj.set(field, value);
            return this;
        }

        public Spawn setInt(CgNetField<Integer> field, int value) {
            obj.setInt(field, value);
            return this;
        }

        public Spawn setFloat(CgNetField<Float> field, float value) {
            obj.setFloat(field, value);
            return this;
        }

        public Spawn setDouble(CgNetField<Double> field, double value) {
            obj.setDouble(field, value);
            return this;
        }

        public Spawn setBool(CgNetField<Boolean> field, boolean value) {
            obj.setBool(field, value);
            return this;
        }

        /** Live: running here, and on every viewer from the end of this tick. */
        public CgNetObject start() {
            return obj.start();
        }
    }

    /** One side's hitbox of an object, ticked with it. */
    private final class Side implements CgNetBehaviour {
        final CgNetObject obj;
        final boolean server;
        final CgHitbox hitbox;
        final Quaterniond q = new Quaterniond();
        private final CgSentHit sent = new CgSentHit();
        private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        private final int source;
        private CgCollisionWorld world;
        // A claimed type's owner's copy: the claim being sent, and those a prediction holds until it is made real.
        private final CgHitClaim claim = new CgHitClaim();
        private CgHitClaim[] queued;
        private int queuedCount;
        // A claimed type's server copy: each of the last claimWindow ticks, by the object's age, the hitbox's box, the
        // follower's eyes (NaN for none) and the hitbox's age.
        private final double[] places;
        private final long[] placedAt;
        private final CgAABB box = new CgAABB(), window = new CgAABB(), targetBox = new CgAABB();
        private final CgHit judged = new CgHit();
        private final double[] eyes = new double[3];
        private long stoppedAt = -1;
        /** The claim being dealt: ticks since it was made. */
        int late;

        Side(CgNetObject obj, boolean server) {
            this.obj = obj;
            this.server = server;
            hitbox = Objects.requireNonNull(factory.apply(obj), "the factory built no hitbox");
            if (hitbox.hasListeners()) {
                throw new IllegalStateException(
                        type.name() + "'s hitbox hears hits through the type, not its own listeners");
            }
            if (claimed && server && obj.owner() != null) {
                hitbox.passive();
                places = new double[claimWindow * PLACE];
                placedAt = new long[claimWindow];
                Arrays.fill(placedAt, Long.MIN_VALUE);
            } else {
                places = null;
                placedAt = null;
            }
            source = obj.getInt(SOURCE);
            if (source >= 0) hitbox.source(source);
            if (obj.anchor() == CgAnchor.Kind.ENTITY) {
                String name = obj.get(POINT);
                int point = name == null ? CgEntityAttachments.FEET : CgEntityAttachments.find(name);
                hitbox.on(obj.entityId(), point < 0 ? CgEntityAttachments.FEET : point);
            }
            int facing = obj.getInt(FACING);
            if (facing >= 0) hitbox.facing(CgHitbox.Facing.values()[facing]);
            place();
            if (server) {
                hitbox.onHit(this::serverHit);
            } else {
                hitbox.onHit(this::predictedHit);
                // Silence is what the player sees, not who decides: a quiet catch-up's hits are claims still.
                if (claimed) hitbox.silentHits(this::claim);
            }
        }

        /** A point anchor's position and a set rotation, which may change each tick. */
        private void place() {
            if (obj.anchor() == CgAnchor.Kind.POINT) hitbox.at(obj.x(), obj.y(), obj.z());
            if (obj.has(QW) || obj.has(QX)) {
                q.set(obj.getFloat(QX), obj.getFloat(QY), obj.getFloat(QZ), obj.getFloat(QW));
                hitbox.rotation(q);
            }
        }

        private CgCollisionWorld world() {
            if (world == null) world = server ? CgCollisionWorld.of(obj.level()) : CgCollisionWorld.client(1f);
            return world;
        }

        @Override
        public void spawned(CgNetObject obj, float age) {
            if (age <= 0) return;
            double skipped = Math.max(0, Math.floor(age) - CATCH_UP);
            if (skipped > 0) hitbox.skip(skipped);
            hitbox.silent(age > QUIET_AFTER);
            for (double t = skipped; t < Math.floor(age) && !hitbox.stopped(); t++) hitbox.tick(world(), 1);
            hitbox.silent(false);
        }

        @Override
        public void tick(CgNetObject obj) {
            if (queuedCount > 0 && !obj.isPredicted()) sendQueued();
            if (hitbox.stopped() || obj.isStopping()) return;
            CgCollisionWorld w = world();
            if (server && source >= 0 && !outlivesSource && !w.entities().pose(source, 1f, pose)) {
                hitbox.stop();
                end();
                return;
            }
            place();
            hitbox.tick(w, 1);
            if (places != null && hitbox.bounds(box)) placed();
            if (server && hitbox.stopped()) end();
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            hitbox.stop();
            if (stoppedAt < 0) stoppedAt = obj.age();
            // ended while still a prediction: the server refused it, and its claims never left
            if (!server && obj.isPredicted()) {
                for (int i = 0; i < queuedCount; i++) {
                    queued[i].refused(CgHitClaim.Refusal.PREDICTION_REFUSED);
                    for (BiConsumer<CgNetObject, CgHitClaim> listener : refusedHere) listener.accept(obj, queued[i]);
                }
            }
            queuedCount = 0;
        }

        /** A claimed server copy waits out the window, so a slow connection's claim still finds it. */
        @Override
        public boolean done() {
            return places == null || stoppedAt >= 0 && obj.age() - stoppedAt >= claimWindow;
        }

        private void end() {
            if (stoppedAt < 0) stoppedAt = obj.age();
            obj.despawn();
        }

        private void serverHit(CgHit h) {
            for (BiConsumer<CgNetObject, CgHit> listener : hits) listener.accept(obj, h);
            obj.emit(HIT, sent.set(h, hitbox.partIndex(h.part())));
        }

        private void predictedHit(CgHit h) {
            for (BiConsumer<CgNetObject, CgHit> listener : predicted) listener.accept(obj, h);
            if (claimed) claim(h);
        }

        // ---- claims, the owner's side

        private void claim(CgHit h) {
            if (!obj.isMine()) return;
            claim.set(h, hitbox.partIndex(h.part()), (int) hitbox.age());
            if (!obj.isPredicted()) {
                obj.send(CLAIM, claim);
                return;
            }
            if (queued == null) queued = new CgHitClaim[QUEUE];
            if (queuedCount == QUEUE) return;
            if (queued[queuedCount] == null) queued[queuedCount] = new CgHitClaim();
            queued[queuedCount++].set(claim);
        }

        private void sendQueued() {
            for (int i = 0; i < queuedCount; i++) obj.send(CLAIM, queued[i]);
            queuedCount = 0;
        }

        // ---- claims, the server's side

        /** Where the hitbox is this tick, kept for the window. */
        private void placed() {
            int i = (int) (obj.age() % claimWindow), at = i * PLACE;
            places[at] = box.minX();
            places[at + 1] = box.minY();
            places[at + 2] = box.minZ();
            places[at + 3] = box.maxX();
            places[at + 4] = box.maxY();
            places[at + 5] = box.maxZ();
            if (!hitbox.followerEyes(eyes)) eyes[0] = Double.NaN;
            places[at + 6] = eyes[0];
            places[at + 7] = eyes[1];
            places[at + 8] = eyes[2];
            places[at + 9] = hitbox.age();
            placedAt[i] = obj.age();
        }

        void judge(CgHitClaim c) {
            CgHitClaim.Refusal reason = check(c);
            if (reason == null) {
                serverHit(judged);
                late = 0;
                return;
            }
            refusals[reason.ordinal()]++;
            c.refused(reason);
            for (BiConsumer<CgNetObject, CgHitClaim> listener : refusedClaim) listener.accept(obj, c);
            obj.emitToOwner(REFUSED, c);
        }

        @Nullable
        private CgHitClaim.Refusal check(CgHitClaim c) {
            if (obj.owner() == null) return CgHitClaim.Refusal.RULE;
            CgCollisionWorld w = world();
            CgEntityQuery entities = w.entities();
            if ((entities.flags(c.target()) & CgEntityQuery.ALIVE) == 0 || !entities.box(c.target(), 1f, targetBox)) {
                return CgHitClaim.Refusal.NO_TARGET;
            }
            if (Math.abs(c.age() - hitbox.age()) > claimWindow) return CgHitClaim.Refusal.OUT_OF_WINDOW;
            // Every viewer's spark lands on the body they see: the claimed point, onto the server's box.
            double px = clamp(c.x(), targetBox.minX(), targetBox.maxX());
            double py = clamp(c.y(), targetBox.minY(), targetBox.maxY());
            double pz = clamp(c.z(), targetBox.minZ(), targetBox.maxZ());
            double padding = claimPadding + claimPaddingPerSpeed * speed(entities, c.target());
            targetBox.grow(padding);
            boolean near = false, any = false;
            long oldest = obj.age() - claimWindow;
            // the tick the claim was made in: the place whose hitbox age is nearest the claim's
            int made = -1;
            double nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < claimWindow; i++) {
                if (placedAt[i] < oldest) continue;
                int at = i * PLACE;
                box.set(places[at], places[at + 1], places[at + 2], places[at + 3], places[at + 4], places[at + 5]);
                if (any) window.union(box);
                else window.set(box);
                any = true;
                near |= box.intersects(targetBox);
                double off = Math.abs(places[at + 9] - c.age());
                if (off < nearest) {
                    nearest = off;
                    made = i;
                }
            }
            if (!near) return CgHitClaim.Refusal.OUT_OF_RANGE;
            boolean seenThen = made >= 0 && !Double.isNaN(places[made * PLACE + 6]);
            if (seenThen) System.arraycopy(places, made * PLACE + 6, eyes, 0, 3);
            CgHitClaim.Refusal rule = hitbox.judge(w, c.target(), c.part(), window, padding, seenThen ? eyes : null,
                    judged);
            if (rule != null) return rule;
            late = made < 0 ? 0 : (int) Math.max(0, obj.age() - placedAt[made]);
            judged.setPlace(px, py, pz, judged.normalX(), judged.normalY(), judged.normalZ(), judged.distance(),
                    judged.fraction());
            return null;
        }
    }

    /** How far {@code id} moved in the last tick: a player's velocity on the server is not its movement. */
    private double speed(CgEntityQuery entities, int id) {
        if (claimPaddingPerSpeed == 0 || !entities.pose(id, 0f, speedPose)) return 0;
        double x = speedPose[CgEntityQuery.X], y = speedPose[CgEntityQuery.Y], z = speedPose[CgEntityQuery.Z];
        if (!entities.pose(id, 1f, speedPose)) return 0;
        double dx = speedPose[CgEntityQuery.X] - x, dy = speedPose[CgEntityQuery.Y] - y, dz = speedPose[CgEntityQuery.Z] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : v > max ? max : v;
    }
}
