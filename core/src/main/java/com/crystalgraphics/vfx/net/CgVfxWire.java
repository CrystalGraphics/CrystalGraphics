package com.crystalgraphics.vfx.net;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.CgReplicated;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.vfx.CgVfxAuthority;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgEntityAttachments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The cues' messages and replicated state, declared once on both sides; and the server's side of them: lifetimes, dead
 * follow targets, the effects cues' authorities run, and what clients ask to predict. Server-safe.
 * {@link CgVfxCueClient} is the client's side.
 */
final class CgVfxWire {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");

    /** Predictions a player may ask for in a burst, and how many a second refill it. */
    static final float BURST = 20f, PER_SECOND = 10f;
    /** Seconds of game time a server tick runs. */
    static final float SERVER_TICK = 1f / 20f;
    /** Seconds an ended cue's effect may play out on the server before it is killed. */
    static final float LINGER = 10f;

    /** The effects authorities run, in start order. Server thread. */
    private static final List<Running> RUNNING = new ArrayList<>();
    /** Cues whose authority ran against no level, warned once each. */
    private static final Set<String> LEVELLESS = ConcurrentHashMap.newKeySet();

    private static boolean declared;
    static CgMessage<CgVfxCue.Play<?>> play, predict;
    /** Lasting cues, and lasting cues saved with the world. */
    static CgReplicated<CgVfxCue.Play<?>> playing, kept;

    /** Each predicting player's tokens and the tick they were counted at. Server thread. */
    private static final Map<UUID, float[]> BUCKETS = new HashMap<>();

    private CgVfxWire() {
    }

    /** Declares the messages, once: at the first cue's declaration, or the client's install. Both are at init. */
    static synchronized void ensure() {
        if (declared) return;
        declared = true;
        play = CgMessage.toClients("crystalgraphics:vfx/cue", CODEC);
        predict = CgMessage.toServer("crystalgraphics:vfx/predict", CODEC).onReceiveFrom(CgVfxWire::predicted);
        playing = CgReplicated.define("crystalgraphics:vfx/playing", CODEC).visibleTo(CgVfxWire::audience);
        kept = CgReplicated.define("crystalgraphics:vfx/kept", CODEC).persisted().visibleTo(CgVfxWire::audience);
        CgNetwork.onServerTick(CgVfxWire::tick);
        CgNetwork.onPeerClosed(peer -> BUCKETS.remove(peer.id()));
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    static void send(CgVfxCue.Play<?> p, CgAudience audience) {
        play.send(audience, p);
    }

    static CgReplicated.Handle<CgVfxCue.Play<?>> start(CgVfxCue.Play<?> p) {
        return (p.persist ? kept : playing).create(p.level, p.x, p.y, p.z, p);
    }

    /** Builds and runs {@code p}'s effect on the server, as its registry entry makes it, against its level. */
    static Running run(CgVfxCue.Play<?> p, CgReplicated.Handle<CgVfxCue.Play<?>> handle) {
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(p.id);
        if (entry == null) {
            handle.remove();
            throw new IllegalStateException("cue '" + p.id + "' is authoritative, but the server has nothing to run: declare"
                    + " it with what it plays, CgVfxOwner.cue(path, look, factory), in common code");
        }
        p.authoritative = true;
        CgVfxEffect effect;
        try {
            effect = build(p, () -> entry.spawn(p));
        } finally {
            p.authoritative = false;
        }
        CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
        // Where at() was given a handle no level answers for, what it follows is in the level it plays in.
        Object level = levels.world(p.level) == CgWorldQuery.NONE && p.followEntity != null ? p.followEntity : p.level;
        if (levels.world(level) == CgWorldQuery.NONE && LEVELLESS.add(p.id)) {
            LOGGER.warn("[CgVfx] {}'s authority tests no world: at() was given a {}, neither a level nor an entity in one,"
                    + " so it strikes nothing", p.id, p.level.getClass().getName());
        }
        Running running = new Running(p, handle, new CgVfxAuthority(effect, levels.world(level), levels.entities(level)));
        RUNNING.add(running);
        return running;
    }

    /**
     * {@code effect} takes a change to its cue, on either side: its parameters set by name, then the rest by the effect
     * itself ({@link CgVfxCue.Live}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void take(CgVfxCue.Play<?> p, CgVfxEffect effect) {
        if (p.params instanceof CgVfxParams) apply((CgVfxParams) p.params, effect);
        if (effect instanceof CgVfxCue.Live) ((CgVfxCue.Live) effect).cueChanged(p);
    }

    /**
     * {@code p}'s effect, as {@code spawn} makes it: seeded with its seed, following what it follows, its parameters set
     * by name and started at its age. Either side.
     */
    static CgVfxEffect build(CgVfxCue.Play<?> p, Supplier<CgVfxEffect> spawn) {
        CgVfxEffect effect = CgVfxEffect.seeded(p.seedUnit(), spawn);
        if (p.followId >= 0) {
            int point = p.point == null ? CgEntityAttachments.FEET
                    : CgEntityAttachments.declare(p.point, CgEntityAttachments.CENTRE);
            if (p.keepOffset) effect.followKeepingOffset(p.followId, point);
            else effect.follow(p.followId, point);
        }
        if (p.params instanceof CgVfxParams) apply((CgVfxParams) p.params, effect);
        if (p.age > 0f) effect.startAt(p.age);
        return effect;
    }

    /** Sets each number and colour onto the effect's look parameter of the same name. */
    static void apply(CgVfxParams params, CgVfxEffect effect) {
        if (params.isEmpty()) return;
        List<CgVfxParam> declared = effect.look().schema().params();
        for (int i = 0; i < declared.size(); i++) {
            CgVfxParam param = declared.get(i);
            float[] v = params.components(param.name());
            if (v == null || param.isCurve() || param.isShake()) continue;
            if (param.size() == 1) {
                effect.set(param, v[0]);
            } else if (v.length >= 4) {
                effect.set(param, v[0], v[1], v[2], v[3]);
            }
        }
    }

    static float ageOf(CgVfxCue.Play<?> p) {
        return p.lasting ? (CgNetwork.serverTicks() - p.startTick) / 20f : p.age;
    }

    private static CgAudience audience(CgReplicated.Handle<CgVfxCue.Play<?>> handle) {
        CgVfxCue.Play<?> p = handle.value();
        if (p.followEntity != null) return CgAudience.trackingAndSelf(p.followEntity);
        return CgAudience.near(handle.dimension(), handle.x(), handle.y(), handle.z(),
                p.cue != null ? p.cue.range() : CgVfxCue.DEFAULT_RANGE);
    }

    /** Before the tick's changes go out: what ran out, lost what it follows, or was killed last tick, ends. */
    private static void tick() {
        tick(playing);
        tick(kept);
        if (!RUNNING.isEmpty()) runAuthorities();
    }

    /**
     * Steps each authority's effect a server tick, publishing what its viewers must draw. An ended cue's effect plays
     * out, its blast heard, for {@link #LINGER} at most; an effect stopped on the server ends its cue.
     */
    private static void runAuthorities() {
        int kept = 0;
        for (int i = 0; i < RUNNING.size(); i++) {
            Running r = RUNNING.get(i);
            CgVfxEffect effect = r.authority.effect();
            boolean ended = r.handle.isRemoved();
            if (r.play.killed) effect.kill();
            else if (ended) effect.stop();
            try {
                r.authority.advance(SERVER_TICK);
            } catch (RuntimeException e) {
                LOGGER.error("[CgVfx] {} threw on the server, and is killed", r.play.id, e);
                effect.kill();
            }
            if (ended && (r.endedFor += SERVER_TICK) > LINGER) effect.kill();
            if (!ended && !r.play.killed && effect.state() != CgVfxEffect.State.PLAYING) {
                r.handle.remove();
                ended = true;
            }
            if (r.authority.done()) continue;
            if (!ended && publish(r, effect)) r.handle.set(r.handle.value());
            RUNNING.set(kept++, r);
        }
        for (int i = RUNNING.size() - 1; i >= kept; i--) RUNNING.remove(i);
    }

    /** Writes into {@code r}'s cue what its effect changed: the numbers and colours set on it, and its own state. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean publish(Running r, CgVfxEffect effect) {
        CgVfxCue.Play play = r.play;
        boolean changed = false;
        if (play.params == null || play.params instanceof CgVfxParams) {
            CgVfxParams params = r.authority.publishParams((CgVfxParams) play.params);
            if (params != null) {
                play.params = params;
                changed = true;
            }
        }
        if (effect instanceof CgVfxCue.Authoritative) changed |= ((CgVfxCue.Authoritative) effect).publish(play);
        return changed;
    }

    /** A lasting play's effect, run by its cue's authority. */
    static final class Running {
        final CgVfxCue.Play<?> play;
        final CgReplicated.Handle<CgVfxCue.Play<?>> handle;
        final CgVfxAuthority authority;
        /** Seconds since its cue ended. */
        float endedFor;

        Running(CgVfxCue.Play<?> play, CgReplicated.Handle<CgVfxCue.Play<?>> handle, CgVfxAuthority authority) {
            this.play = play;
            this.handle = handle;
            this.authority = authority;
        }
    }

    private static void tick(CgReplicated<CgVfxCue.Play<?>> definition) {
        if (definition.size() == 0) return;
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        definition.forEach(handle -> {
            CgVfxCue.Play<?> p = handle.value();
            if (p.killed) {
                // The kill went out as a change last tick; a removal now would have overtaken it.
                if (p.killSent) handle.remove();
                else p.killSent = true;
            } else if (p.lifetime > 0f && ageOf(p) >= p.lifetime) {
                handle.remove();
            } else if (p.followEntity != null && !players.alive(p.followEntity)) {
                handle.remove();
            }
        });
    }

    /** A client asked to show a play it made: the cue's policy, a rate, and its reach decide. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void predicted(CgPeer peer, CgVfxCue.Play<?> p) {
        CgVfxCue cue = p.cue;
        if (cue == null) return;
        Object player = peer.player();
        CgVfxCue.Policy policy = cue.policy();
        if (policy == null || player == null || !take(peer.id()) || !inReach(player, p, cue.range())
                || !policy.allow(player, p)) {
            cue.refused.incrementAndGet();
            return;
        }
        p.level = player;
        p.followEntity = null;
        p.except = peer.id();
        send(p, CgAudience.except(CgAudience.near(player, p.x, p.y, p.z, cue.range()), peer.id()));
    }

    private static boolean take(UUID id) {
        long now = CgNetwork.serverTicks();
        float[] bucket = BUCKETS.computeIfAbsent(id, k -> new float[]{BURST, now});
        bucket[0] = Math.min(BURST, bucket[0] + (now - (long) bucket[1]) * PER_SECOND / 20f);
        bucket[1] = now;
        if (bucket[0] < 1f) return false;
        bucket[0] -= 1f;
        return true;
    }

    private static boolean inReach(Object player, CgVfxCue.Play<?> p, float range) {
        double[] at = new double[3];
        if (!CgPlatform.get(CgServerPlayers.SERVICE).position(player, at)) return false;
        double dx = at[0] - p.x, dy = at[1] - p.y, dz = at[2] - p.z;
        return dx * dx + dy * dy + dz * dz <= (double) range * range;
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    static void predict(CgVfxCue.Play<?> p) {
        ensure();
        predict.sendToServer(p);
    }

    // ── The wire ────────────────────────────────────────────────────────────────────────────────

    /**
     * A play by its cue's id. A lasting one carries its age as it is at the encode, so each viewer, a late one too, starts
     * it where it is; decoded on the server (a save loaded), the age becomes its start tick again.
     */
    static final CgCodec<CgVfxCue.Play<?>> CODEC = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, CgVfxCue.Play<?> p) {
            CgCodecs.MapCodecBuilder<T> out = CgCodecs.map(ops)
                    .field("i", CgCodecs.STRING, p.id)
                    .field("s", CgCodecs.INT, p.seed)
                    .field("x", CgCodecs.DOUBLE, p.x)
                    .field("y", CgCodecs.DOUBLE, p.y)
                    .field("z", CgCodecs.DOUBLE, p.z)
                    .optional("m", CgCodecs.INT, p.aim, CgVfxCue.Play.NONE)
                    .optional("f", CgCodecs.INT, p.followId, -1)
                    .optional("pt", CgCodecs.STRING, p.point, null)
                    .optional("l", CgCodecs.FLOAT, p.lifetime, 0f)
                    .optional("k", CgCodecs.BOOL, p.killed, false)
                    .optional("ko", CgCodecs.BOOL, p.keepOffset, false)
                    .optional("se", CgCodecs.INT, p.struck, -1);
            if ((p.aim & CgVfxCue.Play.TOWARD) != 0) {
                out.field("ax", CgCodecs.DOUBLE, p.ax).field("ay", CgCodecs.DOUBLE, p.ay).field("az", CgCodecs.DOUBLE, p.az);
            }
            if ((p.aim & CgVfxCue.Play.TARGET) != 0) {
                out.field("tx", CgCodecs.DOUBLE, p.tgx).field("ty", CgCodecs.DOUBLE, p.tgy).field("tz", CgCodecs.DOUBLE, p.tgz);
            }
            if (p.lasting) out.field("a", CgCodecs.FLOAT, ageOf(p));
            if (p.params != null && p.cue != null) out.raw("p", (T) ((CgCodec) p.cue.codec()).encode(ops, p.params));
            return out.build();
        }

        @Override
        public <T> CgVfxCue.Play<?> decode(CgDynamicOps<T> ops, T input) {
            CgCodecs.MapCodecReader<T> in = CgCodecs.read(ops, input);
            String id = in.field("i", CgCodecs.STRING);
            CgVfxCue cue = CgVfxCue.get(id);
            CgVfxCue.Play p = new CgVfxCue.Play(cue, id, in.field("x", CgCodecs.DOUBLE), in.field("y", CgCodecs.DOUBLE),
                    in.field("z", CgCodecs.DOUBLE));
            p.seed = in.field("s", CgCodecs.INT);
            p.aim = in.optional("m", CgCodecs.INT, CgVfxCue.Play.NONE);
            if ((p.aim & CgVfxCue.Play.TOWARD) != 0) {
                p.ax = in.field("ax", CgCodecs.DOUBLE);
                p.ay = in.field("ay", CgCodecs.DOUBLE);
                p.az = in.field("az", CgCodecs.DOUBLE);
            }
            if ((p.aim & CgVfxCue.Play.TARGET) != 0) {
                p.tgx = in.field("tx", CgCodecs.DOUBLE);
                p.tgy = in.field("ty", CgCodecs.DOUBLE);
                p.tgz = in.field("tz", CgCodecs.DOUBLE);
            }
            p.followId = in.optional("f", CgCodecs.INT, -1);
            p.point = in.optional("pt", CgCodecs.STRING, null);
            p.lifetime = in.optional("l", CgCodecs.FLOAT, 0f);
            p.killed = in.optional("k", CgCodecs.BOOL, false);
            p.keepOffset = in.optional("ko", CgCodecs.BOOL, false);
            p.struck = in.optional("se", CgCodecs.INT, -1);
            if (in.has("a")) {
                p.age = in.field("a", CgCodecs.FLOAT);
                p.lasting = true;
                p.startTick = CgNetwork.serverTicks() - Math.round(p.age * 20f);
            }
            T raw = in.raw("p");
            if (raw != null) p.params = decodeParams(ops, cue, id, raw);
            return p;
        }
    };

    /** By its cue's codec; where the cue is not declared on this side, as {@link CgVfxParams} if it reads as them. */
    private static <T> Object decodeParams(CgDynamicOps<T> ops, CgVfxCue<?> cue, String id, T raw) {
        try {
            return (cue != null ? cue.codec() : CgVfxParams.CODEC).decode(ops, raw);
        } catch (RuntimeException unreadable) {
            if (cue != null) LOGGER.warn("[CgVfx] {}: unreadable parameters dropped: {}", id, unreadable.getMessage());
            return null;
        }
    }

    /** Back to undeclared, for a test that reset the network's declarations. */
    static synchronized void resetForTesting() {
        declared = false;
        play = predict = null;
        playing = kept = null;
        BUCKETS.clear();
        RUNNING.clear();
        CgVfxCue.resetForTesting();
    }
}
