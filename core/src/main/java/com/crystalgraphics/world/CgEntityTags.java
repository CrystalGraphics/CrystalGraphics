package com.crystalgraphics.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
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
    private static final CgNetType TYPE = CgNetType.define("crystalgraphics:entity_tags").fields(TAGS).perHolder().build();

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
        s.counts.update(tag, delta);
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
        m.counts.clear();
        // a tag this client lacks reads as null, and counts nothing here
        for (Owned owned : o.get(TAGS)) if (owned.tag() != null) m.counts.update(owned.tag(), owned.count());
    }

    /** Forgets both sides. Tests only. */
    static void resetForTesting() {
        SERVER.clear();
        CLIENT.clear();
    }
}
