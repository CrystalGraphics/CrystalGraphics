package com.crystalgraphics.collision;

import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.world.CgEntityAttachments;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 *         .hitbox(obj -> CgHitbox.of(CgRing.front(1.2, 0.6)).radius(0, obj.getFloat(RADIUS), 12).living())
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

    private final CgNetType type;
    private final Function<CgNetObject, CgHitbox> factory;
    private final boolean outlivesSource;
    private final List<BiConsumer<CgNetObject, CgHit>> hits = new CopyOnWriteArrayList<>(),
            predicted = new CopyOnWriteArrayList<>(), confirmed = new CopyOnWriteArrayList<>();
    /** The confirmed hit handed to listeners, read back from the wire; client thread. */
    private final CgHit arrived = new CgHit();

    private CgHitboxType(Builder b, CgNetType type) {
        this.type = type;
        this.factory = b.factory;
        this.outlivesSource = b.outlivesSource;
        type.onServer(obj -> new Side(obj, true));
        type.onClient(obj -> new Side(obj, false));
        type.onEvent(HIT, this::confirmed);
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

        public CgHitboxType build() {
            if (factory == null) throw new IllegalStateException(id + " needs hitbox(factory)");
            CgNetType.Builder t = CgNetType.define(id).fields(CORE).fields(fields.toArray(new CgNetField<?>[0]))
                    .events(HIT);
            if (!Double.isNaN(range)) t.range(range);
            return new CgHitboxType(this, t.build());
        }
    }

    // ---- listeners

    /** Server: each hit of the server's hitbox, the authoritative one. */
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

    // ---- starting one

    /** Server: one at {@code anchor}, to configure then {@link Spawn#start()}. */
    public Spawn spawn(CgAnchor anchor) {
        return new Spawn(type.spawn(anchor));
    }

    /** Client: one shown here now, and asked of the server ({@link #acceptPredictionsFrom}). */
    public Spawn predict(CgAnchor anchor) {
        return new Spawn(type.predict(anchor));
    }

    /** Server, at init: which of the clients' predictions to make real. */
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

    public CgNetType type() {
        return type;
    }

    public String id() {
        return type.name();
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

        private Spawn(CgNetObject obj) {
            this.obj = obj;
        }

        /** Server: whose it is, an entity: never hit, with its vehicle and passengers, and it ends with them. */
        public Spawn source(Object entity) {
            return source(CgPlatform.get(CgServerPlayers.SERVICE).entityId(entity));
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

        public Spawn rotation(Quaterniondc q) {
            obj.setFloat(QX, (float) q.x()).setFloat(QY, (float) q.y()).setFloat(QZ, (float) q.z())
                    .setFloat(QW, (float) q.w());
            return facing(CgHitbox.Facing.FIXED);
        }

        public Spawn owner(Object playerOrId) {
            obj.owner(playerOrId);
            return this;
        }

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

        Side(CgNetObject obj, boolean server) {
            this.obj = obj;
            this.server = server;
            hitbox = Objects.requireNonNull(factory.apply(obj), "the factory built no hitbox");
            if (hitbox.hasListeners()) {
                throw new IllegalStateException(
                        type.name() + "'s hitbox hears hits through the type, not its own listeners");
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
            if (server) hitbox.onHit(this::serverHit);
            else hitbox.onHit(this::predictedHit);
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
            if (hitbox.stopped() || obj.isStopping()) return;
            CgCollisionWorld w = world();
            if (server && source >= 0 && !outlivesSource && !w.entities().pose(source, 1f, pose)) {
                hitbox.stop();
                obj.despawn();
                return;
            }
            place();
            hitbox.tick(w, 1);
            if (server && hitbox.stopped()) obj.despawn();
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            hitbox.stop();
        }

        private void serverHit(CgHit h) {
            for (BiConsumer<CgNetObject, CgHit> listener : hits) listener.accept(obj, h);
            obj.emit(HIT, sent.set(h, hitbox.partIndex(h.part())));
        }

        private void predictedHit(CgHit h) {
            for (BiConsumer<CgNetObject, CgHit> listener : predicted) listener.accept(obj, h);
        }
    }
}
