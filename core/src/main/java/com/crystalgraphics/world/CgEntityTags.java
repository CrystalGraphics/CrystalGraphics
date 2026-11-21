package com.crystalgraphics.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetCommand;
import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.tag.CgTagLink;
import com.crystalgraphics.tag.CgTagNet;

/**
 * Gameplay tags an entity owns, with counts: set on the server, seen by every client tracking the entity. Unreal's
 * ability system component's loose gameplay tags ({@code AddLooseGameplayTag}), on any of the host's entities. A graph's
 * {@code tags:} block reads them into variables, so a mod's states (a stance, a flight gear, a stun) reach the
 * animation with no code.
 *
 * <pre>{@code
 * static final CgGameplayTag FLYING = CgTagRegistry.global().register("mymod:state.flying");
 *
 * CgEntityTags.add(entity, FLYING);                 // server: owned, and sent to the clients that see it
 * CgEntityTags.remove(entity, FLYING);
 * CgEntityTags.count(entity, FLYING);               // server: its count, the tags under it included
 * CgEntityTags.client(entityId).has(FLYING);        // client: what the server last sent
 * }</pre>
 *
 * <ul>
 *   <li>A tag added twice is owned until removed twice: two sources may grant one tag.</li>
 *   <li>The server's side: its game thread. A client's: the client thread, after the server's change arrives.</li>
 *   <li>Tags cross the wire as the tag table's agreed indices ({@link CgTagNet}): declare a tag before setting it, as a
 *       tag registered late reaches the clients at the next tick's table.</li>
 *   <li>They end with the entity: its death, its leaving the level, its changing dimension.</li>
 * </ul>
 */
public final class CgEntityTags {

    /** One tag's count, as the object carries it. */
    record Owned(CgGameplayTag tag, int count) {
    }

    /** A tag as the table's agreed index: the server's link sending, the client's receiving. */
    private static final CgCodec<CgGameplayTag> WIRE_TAG = CgCodecs.xmap(CgCodecs.INT,
            index -> CgTagNet.fromServer().fromWire(index), tag -> serverLink().toWire(tag));
    private static final CgCodec<Owned> OWNED = CgCodecs.record(
            CgCodecs.field("t", WIRE_TAG, Owned::tag),
            CgCodecs.field("n", CgCodecs.INT, Owned::count), Owned::new);
    private static final CgNetField<List<Owned>> TAGS = CgNetField.of("tags", CgCodecs.listOf(OWNED));

    /** An owner's change asked of the server: a tag (the table's index), by how much, and the asking client's key. */
    record Change(int tag, int delta, int key) {
        static final CgCodec<Change> CODEC = CgCodecs.record(
                CgCodecs.field("t", CgCodecs.INT, Change::tag),
                CgCodecs.field("d", CgCodecs.INT, Change::delta),
                CgCodecs.field("k", CgCodecs.INT, Change::key),
                Change::new);
    }

    /** The server's answer to a change, to its asker alone. */
    record Verdict(int key, boolean accepted) {
        static final CgCodec<Verdict> CODEC = CgCodecs.record(
                CgCodecs.field("k", CgCodecs.INT, Verdict::key),
                CgCodecs.field("a", CgCodecs.BOOL, Verdict::accepted),
                Verdict::new);
    }

    private static final CgNetCommand<Change> CHANGE = CgNetCommand.of("change", Change.CODEC).perSecond(20);
    private static final CgNetEvent<Verdict> VERDICT = CgNetEvent.of("verdict", Verdict.CODEC);
    private static final CgNetType TYPE = CgNetType.define("crystalgraphics:entity_tags").fields(TAGS)
            .events(VERDICT).commands(CHANGE).perHolder().onEntities(type -> type.equals(CgNetEntities.PLAYER)).build();

    /** What the server takes of an owner's change to its own entity's tags. */
    @FunctionalInterface
    public interface Rule {
        /** Whether {@code entity} (the host's) may have {@code delta} more of {@code tag}; {@code delta} is negative to remove. */
        boolean accepts(Object entity, CgGameplayTag tag, int delta);
    }

    /** What {@link #watch} gave: call {@link #cancel} to stop hearing. */
    @FunctionalInterface
    public interface Watch {
        void cancel();
    }

    private static Rule rule = (entity, tag, delta) -> false;
    /** The client's predicted changes awaiting the server's verdict, by key id. */
    private static final Map<Integer, CgPredictionKey> KEYS = new HashMap<>();
    /** Who hears which tag change: by the entity (the server's host object) or its id (a client), a tag each. */
    private static final Map<Object, List<Watcher>> WATCHERS = new HashMap<>();

    private record Watcher(CgGameplayTag tag, BiConsumer<Integer, Integer> listener) {
    }

    /** The server's: every entity given a tag, held weakly, its counts and the object carrying them. */
    private static final Map<Object, Served> SERVER = new WeakHashMap<>();
    /** A client's: each entity's mirror by network id, kept while its object lives or an actor holds it. */
    private static final Map<Integer, Mirror> CLIENT = new HashMap<>();
    private static final CgTagCounts NONE = new CgTagCounts();

    private static final class Served {
        final CgTagCounts counts = new CgTagCounts();
    }

    private static final class Mirror {
        final CgTagCounts counts = new CgTagCounts();
        boolean served;
        int holders;
    }

    static {
        TYPE.onCommand(CHANGE, (obj, sender, change) -> {
            Object entity = obj.entity();
            CgGameplayTag tag = change == null || entity == null ? null : serverLink().fromWire(change.tag());
            boolean ok = tag != null && change.delta() != 0 && rule.accepts(entity, tag, change.delta());
            if (ok) {
                if (change.delta() > 0) add(entity, tag, change.delta());
                else remove(entity, tag, -change.delta());
            }
            if (change != null) obj.emitToOwner(VERDICT, new Verdict(change.key(), ok));
        });
        TYPE.onEvent(VERDICT, (obj, verdict) -> {
            CgPredictionKey key = KEYS.remove(verdict.key());
            if (key == null) return;
            if (verdict.accepted()) key.accept();
            else key.refuse();
        });
        TYPE.onClient(obj -> new CgNetBehaviour() {
            @Override
            public void spawned(CgNetObject o, float age) {
                take(o);
            }

            @Override
            public void changed(CgNetObject o, CgNetField<?> field) {
                take(o);
            }

            @Override
            public void despawned(CgNetObject o, boolean killed) {
                Mirror m = CLIENT.get(o.entityId());
                if (m == null) return;
                m.counts.clear();
                m.served = false;
                if (m.holders == 0) CLIENT.remove(o.entityId());
            }
        });
    }

    private CgEntityTags() {
    }

    /** Declares the type: at network init, on both sides, before anyone connects. */
    public static void install() {
    }

    private static CgTagLink serverLink() {
        CgTagLink link = CgTagNet.server();
        if (link == null) throw new IllegalStateException("no tag table published yet: a server sends tags from its first tick");
        return link;
    }

    // ── the server ──────────────────────────────────────────────────────────────────────

    /** Server: {@code entity} owns {@code tag} once more. */
    public static void add(Object entity, CgGameplayTag tag) {
        add(entity, tag, 1);
    }

    /** Server: {@code count} more of {@code tag} on {@code entity}. */
    public static void add(Object entity, CgGameplayTag tag, int count) {
        if (count <= 0) throw new IllegalArgumentException("add " + count + " of " + tag + ": a count is positive");
        change(entity, tag, count);
    }

    /** Server: one fewer of {@code tag}; false where {@code entity} does not own it explicitly. */
    public static boolean remove(Object entity, CgGameplayTag tag) {
        return remove(entity, tag, 1);
    }

    /** Server: {@code count} fewer of {@code tag}, gone at 0; false where {@code entity} does not own it explicitly. */
    public static boolean remove(Object entity, CgGameplayTag tag, int count) {
        if (count <= 0) throw new IllegalArgumentException("remove " + count + " of " + tag + ": a count is positive");
        Served s = SERVER.get(Objects.requireNonNull(entity, "entity"));
        if (s == null || s.counts.explicitCount(tag) == 0) return false;
        change(entity, tag, -count);
        return true;
    }

    /** Server: {@code tag}'s count on {@code entity}, every tag under it included. */
    public static int count(Object entity, CgGameplayTag tag) {
        Served s = SERVER.get(entity);
        return s == null ? 0 : s.counts.count(tag);
    }

    /** Server: {@code entity}'s counts, to read (change them through {@link #add} and {@link #remove}). */
    public static CgTagCounts of(Object entity) {
        return served(entity).counts;
    }

    private static Served served(Object entity) {
        return SERVER.computeIfAbsent(Objects.requireNonNull(entity, "entity"), e -> new Served());
    }

    private static void change(Object entity, CgGameplayTag tag, int delta) {
        Served s = served(entity);
        int before = s.counts.count(tag);
        s.counts.update(tag, delta);
        fire(entity, tag, before, s.counts.count(tag));
        List<Owned> now = new ArrayList<>(s.counts.size());
        for (int i = 0; i < s.counts.size(); i++) now.add(new Owned(s.counts.tag(i), s.counts.countAt(i)));
        // the entity's own object of the type, made on its first tag, ending with it
        TYPE.of(entity).set(TAGS, Collections.unmodifiableList(now));
    }

    // ── a client ────────────────────────────────────────────────────────────────────────

    /**
     * Client: what the server last sent of entity {@code entityId}'s tags; empty for one it sent none of. A reader
     * holding it across frames asks {@link #hold} instead, so its copy stays the one updated.
     */
    public static CgTagCounts client(int entityId) {
        Mirror m = CLIENT.get(entityId);
        return m == null ? NONE : m.counts;
    }

    /** Client: entity {@code entityId}'s mirror, kept and updated until {@link #release}d: what its actor reads each frame. */
    public static CgTagCounts hold(int entityId) {
        Mirror m = CLIENT.computeIfAbsent(entityId, id -> new Mirror());
        m.holders++;
        return m.counts;
    }

    /** Client: lets go of what {@link #hold} gave. */
    public static void release(int entityId) {
        Mirror m = CLIENT.get(entityId);
        if (m == null) return;
        if (--m.holders <= 0 && !m.served) CLIENT.remove(entityId);
    }

    private static void take(CgNetObject o) {
        if (!o.has(TAGS)) return;
        Mirror m = CLIENT.computeIfAbsent(o.entityId(), id -> new Mirror());
        m.served = true;
        List<Watcher> watched = WATCHERS.get(o.entityId());
        int[] before = null;
        if (watched != null) {
            before = new int[watched.size()];
            for (int i = 0; i < before.length; i++) before[i] = m.counts.count(watched.get(i).tag());
        }
        m.counts.clear();
        // a tag this client lacks reads as null, and counts nothing here
        for (Owned owned : o.get(TAGS)) if (owned.tag() != null) m.counts.update(owned.tag(), owned.count());
        if (before != null) {
            for (int i = 0; i < before.length; i++) {
                fire(o.entityId(), watched.get(i).tag(), before[i], m.counts.count(watched.get(i).tag()));
            }
        }
    }

    // ── asking, and hearing ─────────────────────────────────────────────────────────────

    /** Server: which of an owner's changes to its own entity's tags it takes. None, until set. */
    public static void acceptTags(Rule accepting) {
        rule = Objects.requireNonNull(accepting, "rule");
    }

    /** Client: whether this client's own player is entity {@code entityId}: it owns that entity's tags. */
    public static boolean owns(int entityId) {
        CgNetObject obj = TYPE.find(entityId);
        return obj != null && obj.isMine();
    }

    /**
     * Client, the owner: {@code delta} more of {@code tag} on entity {@code entityId}, shown here now and asked of the
     * server under {@code key}. Refused, it goes back as everything else under the key does.
     *
     * <p>Reached through {@code CgEntity.addTag}, which is the call to make.
     *
     * @throws IllegalStateException where this client has no object of the entity's tags to ask through: it is not the owner
     */
    public static void ask(int entityId, CgGameplayTag tag, int delta, CgPredictionKey key) {
        CgNetObject obj = TYPE.find(entityId);
        if (obj == null || !obj.isMine()) {
            throw new IllegalStateException("only an entity's owner changes its tags from a client: entity " + entityId + " is not this client's");
        }
        Mirror m = CLIENT.computeIfAbsent(entityId, id -> new Mirror());
        int before = m.counts.count(tag);
        m.counts.update(tag, delta);
        fire(entityId, tag, before, m.counts.count(tag));
        KEYS.put(key.id(), key);
        key.onRefused(() -> {
            int was = m.counts.count(tag);
            m.counts.update(tag, -delta);
            fire(entityId, tag, was, m.counts.count(tag));
        });
        obj.send(CHANGE, new Change(CgTagNet.fromServer().toWire(tag), delta, key.id()));
    }

    /**
     * Hears {@code tag}'s count on {@code entity} change, on this side: {@code (before, now)}, the tags under it
     * counted. {@code entity} is the host's object on a server, its network id (an {@code Integer}) on a client.
     */
    public static Watch watch(Object entity, CgGameplayTag tag, BiConsumer<Integer, Integer> listener) {
        Watcher w = new Watcher(Objects.requireNonNull(tag, "tag"), Objects.requireNonNull(listener, "listener"));
        WATCHERS.computeIfAbsent(entity, k -> new ArrayList<>(2)).add(w);
        return () -> {
            List<Watcher> all = WATCHERS.get(entity);
            if (all != null && all.remove(w) && all.isEmpty()) WATCHERS.remove(entity);
        };
    }

    private static void fire(Object entity, CgGameplayTag tag, int before, int now) {
        if (before == now) return;
        List<Watcher> all = WATCHERS.get(entity);
        if (all == null) return;
        for (Watcher w : new ArrayList<>(all)) if (w.tag() == tag) w.listener().accept(before, now);
    }

    /** Forgets both sides. Tests only. */
    static void resetForTesting() {
        SERVER.clear();
        CLIENT.clear();
        KEYS.clear();
        WATCHERS.clear();
        rule = (entity, tag, delta) -> false;
    }
}
