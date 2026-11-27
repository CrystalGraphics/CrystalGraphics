package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgKeyedListCodec;
import com.crystalgraphics.serialization.CgMapCodec;
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtMethods;

/**
 * What {@link CgNetType#define(String, CgObjectCodec)} compiles a codec into: each key a field of the type, read from
 * the server's instances while they are watched and written into each client's own instance as fields arrive.
 *
 * <p>Reading a watched instance's getters once a tick and sending what differs from the last send is Unreal's
 * property replication without its push model, which compares each property against a shadow copy; a type's
 * {@code comparesOnTouch()} is the push model, where only what the game marks is compared.</p>
 */
final class CgNetModel<T> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** How a key is carried. */
    enum Kind { PRIMITIVE, VALUE, PART, COMPUTED, MAP, LIST }

    /** One entry of a map key, the instance an entry's object stands for. */
    static final class Entry {
        @Nullable
        Map<Object, Object> map;
        @Nullable
        Object key;
        @Nullable
        Object value;
        int parent;

        @Nullable
        Object value() {
            return map == null ? value : map.get(key);
        }

        void value(@Nullable Object v) {
            value = v;
            if (map != null) {
                if (v == null) map.remove(key);
                else map.put(key, v);
            }
        }
    }

    /** A put's argument: an entry's key and value. */
    static final class Put {
        Object key;
        Object value;
    }

    static final class Key {
        final String name;
        final Kind kind;
        final CgNetField<?> field;
        @Nullable
        final Function<Object, Object> get;
        @Nullable
        final BiConsumer<Object, Object> set;
        @Nullable
        final CgCodec<Object> codec;
        final CgObjectCodec.Policy policy;
        /** A part's type, whose objects are this key's values; a map key's entry type. */
        @Nullable
        final CgNetType<Object> part;
        /** A map key's calls: an entry opened for its caller, put, removed. */
        @Nullable
        CgNetCall<Object, Boolean> open, put, remove;
        /** Its field's index in the type; its own position is {@link #slot}. */
        int index;
        int slot;

        @SuppressWarnings("unchecked")
        Key(String name, Kind kind, CgNetField<?> field, @Nullable Function<?, ?> get, @Nullable BiConsumer<?, ?> set,
            @Nullable CgCodec<?> codec, CgObjectCodec.Policy policy, @Nullable CgNetType<?> part) {
            this.name = name;
            this.kind = kind;
            this.field = field;
            this.get = (Function<Object, Object>) get;
            this.set = (BiConsumer<Object, Object>) set;
            this.codec = (CgCodec<Object>) codec;
            this.policy = policy;
            this.part = (CgNetType<Object>) part;
        }

        boolean inRange(Object value) {
            if (!(value instanceof Number n)) return true;
            double d = n.doubleValue();
            return d >= policy.min() && d <= policy.max();
        }
    }

    /** An object's binding to its instance: the server's model or the client's own copy. */
    static final class Bound {
        Object model;
        /** The root instance an edit's guard is asked about: the model itself, or the one holding this part. */
        Object root;
        /** Server: each value key's encoding as last sent; null for a key not yet read. */
        final byte[][] sent;
        /** Server: {@code touch} was called since the last read. */
        boolean touched = true;
        boolean everWatched;
        /** Server: each map key's entry wrappers, by key slot, then by map key; made at the first open. */
        @Nullable
        Map<Object, Entry>[] entries;

        /** Server: each keyed list's elements' encodings as last sent, by key slot, then by element key. */
        @Nullable
        Map<Object, byte[]>[] elements;

        @SuppressWarnings("unchecked")
        Map<Object, byte[]> elements(int slot) {
            if (elements == null) elements = new Map[sent.length];
            if (elements[slot] == null) elements[slot] = new HashMap<>();
            return elements[slot];
        }

        @SuppressWarnings("unchecked")
        Map<Object, Entry> entries(int slot) {
            if (entries == null) entries = new Map[sent.length];
            if (entries[slot] == null) entries[slot] = new HashMap<>();
            return entries[slot];
        }

        Bound(Object model, Object root, int keys) {
            this.model = model;
            this.root = root;
            this.sent = new byte[keys][];
        }
    }

    final CgObjectCodec<T> codec;
    final Key[] keys;
    final boolean comparesOnTouch;
    CgNetType<T> type;
    /** Server: each instance's object, by identity. Server thread. */
    final Map<Object, CgNetObject> live = new IdentityHashMap<>();
    @Nullable
    volatile BiPredicate<Object, CgPeer> mayEdit;
    final List<BiConsumer<Object, CgPeer>> afterEdit = new CopyOnWriteArrayList<>();
    /** Several keys edited in one request, all or none: a partial encoding of the instance, by key. */
    final CgNetCall<Object, Boolean> edit;
    /** A map key's entry type's: its instances are {@link Entry} wrappers over the parent's map. */
    boolean entry;

    private CgNetModel(CgObjectCodec<T> codec, Key[] keys, boolean comparesOnTouch) {
        this.codec = codec;
        this.keys = keys;
        this.comparesOnTouch = comparesOnTouch;
        this.edit = CgNetCall.of("cg/edit", CgCodecs.PLAIN, CgCodecs.BOOL);
    }

    /** Each key of {@code codec} as a field, then each computed one; the parts' types declared as {@code name/key}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> CgNetModel<T> compile(String name, CgObjectCodec<T> codec, List<CgNetType.Computed<T, ?>> computed,
                                     boolean comparesOnTouch) {
        List<Key> keys = new ArrayList<>();
        for (CgObjectCodec.Key<T, ?> k : codec.describe()) {
            CgObjectCodec.Policy policy = k.policy();
            CgCodec<?> c = k.codec();
            Kind kind;
            CgNetField<?> field;
            CgNetType<?> part = null;
            if (c instanceof CgNbtMethods<?> nbt) {
                // its own save and load: carried as the tag, loaded into the held object on each side
                CgNbtMethods<Object> methods = (CgNbtMethods<Object>) nbt;
                Function<Object, Object> held = (Function<Object, Object>) k.getter();
                field = CgNetField.of(k.key(), CgNbtCompound.CODEC);
                keys.add(new Key(k.key(), Kind.VALUE, field, m -> methods.save(held.apply(m)),
                        (Object m, Object tag) -> methods.load(held.apply(m), (CgNbtCompound) tag), CgNbtCompound.CODEC, policy, null));
                if (policy.serverOnly()) field.serverOnly();
                else if (!policy.readOnly()) field.predicted();
                continue;
            } else if (c instanceof CgKeyedListCodec<?> list) {
                kind = Kind.LIST;
                field = CgNetList.of(k.key(), (CgCodec) list.elements(), (Function) list.key());
            } else if (c instanceof CgMapCodec<?, ?> map) {
                kind = Kind.MAP;
                part = entryType(name + "/" + k.key().toLowerCase(Locale.ROOT), (CgMapCodec<Object, Object>) map,
                        comparesOnTouch);
                field = CgNetField.of(k.key(), (CgCodec) CgCodecs.listOf(map.keys()));
            } else if (c instanceof CgObjectCodec<?> nested) {
                kind = Kind.PART;
                CgNetType.Builder<?> partType = CgNetType.define(name + "/" + k.key().toLowerCase(Locale.ROOT), (CgObjectCodec) nested);
                if (comparesOnTouch) partType.comparesOnTouch();
                part = partType.build();
                field = CgNetField.ofInt(k.key());
            } else if ((field = primitive(k.key(), c)) != null) {
                kind = Kind.PRIMITIVE;
            } else {
                kind = Kind.VALUE;
                field = CgNetField.of(k.key(), (CgCodec) c);
            }
            if (policy.serverOnly()) field.serverOnly();
            else if (kind != Kind.PART && kind != Kind.MAP && kind != Kind.LIST && !policy.readOnly()) field.predicted();
            Key key = new Key(k.key(), kind, field, k.getter(), k.setter(), c, policy, part);
            if (kind == Kind.MAP) {
                CgMapCodec<Object, Object> map = (CgMapCodec<Object, Object>) c;
                CgObjectCodec<Put> put = CgCodecs.object(Put::new)
                        .field("k", map.keys(), p -> p.key, (p, v) -> p.key = v)
                        .field("v", map.values(), p -> p.value, (p, v) -> p.value = v).build();
                key.open = CgNetCall.of("open/" + k.key(), map.keys(), CgCodecs.BOOL);
                key.put = (CgNetCall) CgNetCall.of("put/" + k.key(), put, CgCodecs.BOOL);
                key.remove = CgNetCall.of("remove/" + k.key(), map.keys(), CgCodecs.BOOL);
            }
            keys.add(key);
        }
        for (CgNetType.Computed<T, ?> made : computed) {
            keys.add(new Key(made.field().name, Kind.COMPUTED, made.field(), made.value(), null, null,
                    new CgObjectCodec.Policy(false, true, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), null));
        }
        for (int k = 0; k < keys.size(); k++) keys.get(k).slot = k;
        return new CgNetModel<>(codec, keys.toArray(new Key[0]), comparesOnTouch);
    }

    /** A map key's entry type: each entry an object of its key, its parent's id and its value, made when opened. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CgNetType<Object> entryType(String name, CgMapCodec<Object, Object> map, boolean comparesOnTouch) {
        CgObjectCodec<Entry> entry = CgCodecs.object(Entry::new)
                .field("key", map.keys(), e -> e.key, (e, v) -> e.key = v)
                .field("parent", CgCodecs.INT, e -> e.parent, (e, v) -> e.parent = v)
                .field("value", map.values(), Entry::value, Entry::value)
                .readOnly("key").readOnly("parent").build();
        CgNetType.Builder<Entry> b = CgNetType.define(name, entry);
        if (comparesOnTouch) b.comparesOnTouch();
        CgNetType<Entry> type = b.build();
        type.model.entry = true;
        return (CgNetType) type;
    }

    /** The calls the compiled keys bring, for the type to list. */
    List<CgNetCall<?, ?>> calls() {
        List<CgNetCall<?, ?>> out = new ArrayList<>();
        out.add(edit);
        for (Key k : keys) {
            if (k.kind != Kind.MAP) continue;
            out.add(k.open);
            out.add(k.put);
            out.add(k.remove);
        }
        return out;
    }

    @Nullable
    private static CgNetField<?> primitive(String key, CgCodec<?> codec) {
        if (codec == CgCodecs.INT) return CgNetField.ofInt(key);
        if (codec == CgCodecs.LONG) return CgNetField.ofLong(key);
        if (codec == CgCodecs.FLOAT) return CgNetField.ofFloat(key);
        if (codec == CgCodecs.DOUBLE) return CgNetField.ofDouble(key);
        if (codec == CgCodecs.BOOL) return CgNetField.ofBool(key);
        return null;
    }

    /** The compiled fields, codec keys first. */
    List<CgNetField<?>> fields() {
        List<CgNetField<?>> out = new ArrayList<>(keys.length);
        for (Key k : keys) out.add(k.field);
        return out;
    }

    /** Called once the type exists: each key's field index. */
    @SuppressWarnings("unchecked")
    void bind(CgNetType<T> type) {
        this.type = type;
        type.onCall(edit, (subject, peer, partial, reply) -> editAll(subject, peer, partial, reply));
        for (Key k : keys) {
            k.index = type.require(k.field);
            if (k.kind != Kind.MAP) continue;
            type.onCall(k.open, (subject, peer, mapKey, reply) -> {
                CgNetObject obj = live.get(subject);
                if (obj == null) {
                    reply.fail(CgNetCommand.REFUSED_GONE);
                    return;
                }
                entryObject(obj, k, mapKey).watch(peer.id());
                reply.ok(true);
            });
            type.onCall(k.put, (subject, peer, put, reply) -> mapEdit(subject, peer, k, put, reply));
            type.onCall(k.remove, (subject, peer, mapKey, reply) -> {
                Put removal = new Put();
                removal.key = mapKey;
                mapEdit(subject, peer, k, removal, reply);
            });
        }
    }

    /** Several keys at once: each checked first, then all stored, and {@code afterEdit} once. */
    private void editAll(Object subject, CgPeer peer, Object partial, CgNetCall.Reply<Boolean> reply) {
        CgNetObject obj = live.get(subject);
        if (obj == null || !(partial instanceof Map<?, ?> values)) {
            reply.fail(obj == null ? CgNetCommand.REFUSED_GONE : CgNetCommand.REFUSED_MALFORMED);
            return;
        }
        List<Key> taken = new ArrayList<>(values.size());
        List<Object> decoded = new ArrayList<>(values.size());
        for (Map.Entry<?, ?> e : values.entrySet()) {
            Key key = null;
            for (Key k : keys) if (k.name.equals(String.valueOf(e.getKey()))) key = k;
            if (key == null || key.codec == null || key.kind == Kind.PART || key.kind == Kind.MAP || key.kind == Kind.LIST
                    || key.kind == Kind.COMPUTED) {
                reply.fail(key == null ? CgNetCommand.REFUSED_UNKNOWN : CgNetCommand.REFUSED_READ_ONLY);
                return;
            }
            Object value;
            try {
                value = key.codec.decode(CgPlainOps.INSTANCE, e.getValue());
            } catch (RuntimeException bad) {
                reply.fail(CgNetCommand.REFUSED_MALFORMED);
                return;
            }
            String refusal = refusal(obj, key, value, peer);
            if (refusal != null) {
                reply.fail(refusal);
                return;
            }
            taken.add(key);
            decoded.add(value);
        }
        Bound bound = obj.bound;
        for (int i = 0; i < taken.size(); i++) {
            Key key = taken.get(i);
            Object value = decoded.get(i);
            store(bound.model, key, value);
            if (key.field.kind == CgNetField.Kind.CODEC) {
                bound.sent[key.slot] = encode(key.field, value);
                obj.pulled(key.index, value);
            } else {
                obj.setUnchecked(key.index, value);
            }
        }
        for (BiConsumer<Object, CgPeer> after : afterEdit) {
            try {
                after.accept(bound.root, peer);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] an afterEdit of {} failed", type.name, failed);
            }
        }
        reply.ok(true);
    }

    /** An entry's object, made now if it has none: one per map, key and entry, by identity. */
    CgNetObject entryObject(CgNetObject parent, Key key, Object mapKey) {
        Bound bound = parent.bound;
        Map<Object, Entry> entries = bound.entries(key.slot);
        Entry entry = entries.computeIfAbsent(mapKey, k -> new Entry());
        entry.map = (Map<Object, Object>) key.get.apply(bound.model);
        entry.key = mapKey;
        entry.parent = parent.id();
        return key.part.model.over(entry, bound.root);
    }

    @SuppressWarnings("unchecked")
    private void mapEdit(Object subject, CgPeer peer, Key key, Object put, CgNetCall.Reply<Boolean> reply) {
        CgNetObject obj = live.get(subject);
        if (obj == null) {
            reply.fail(CgNetCommand.REFUSED_GONE);
            return;
        }
        if (key.policy.readOnly() || key.policy.serverOnly()) {
            reply.fail(CgNetCommand.REFUSED_READ_ONLY);
            return;
        }
        if (!editableBy(obj, peer)) {
            reply.fail(CgNetCommand.REFUSED_NOT_ALLOWED);
            return;
        }
        Put edit = (Put) put;
        Map<Object, Object> map = (Map<Object, Object>) key.get.apply(obj.bound.model);
        if (edit.value == null) map.remove(edit.key);
        else map.put(edit.key, edit.value);
        // comparesOnTouch reads the keys again only when touched: an edit is one
        obj.bound.touched = true;
        for (BiConsumer<Object, CgPeer> after : afterEdit) {
            try {
                after.accept(obj.bound.root, peer);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] an afterEdit of {} failed", type.name, failed);
            }
        }
        reply.ok(true);
    }

    @Nullable
    Key keyAt(int fieldIndex) {
        for (Key k : keys) if (k.index == fieldIndex) return k;
        return null;
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    /** {@code model}'s object, made now if it has none. */
    CgNetObject over(Object model, Object root) {
        CgNetObject obj = live.get(model);
        if (obj != null && (obj.isLive() || obj.state == CgNetObject.PENDING)) return obj;
        obj = type.spawn(CgAnchor.global());
        obj.bound = new Bound(model, root, keys.length);
        live.put(model, obj);
        obj.start();
        return obj;
    }

    /**
     * Reads each getter into its field, a primitive by value and anything else by its encoding against the last sent.
     * Server thread, once a tick, for a live object; nothing is read while nobody watches.
     */
    void tick(CgNetObject obj, long tick) {
        Bound bound = obj.bound;
        if (bound == null) return;
        // a watch asked for this tick is read now, so its spawn carries values and never fallbacks
        if (!CgNetObjects.any(obj.watchedBy) && !CgNetObjects.any(obj.watchWanted)) {
            // ended at its last unwatch; one that was never watched gets a tick's grace for the watch to land
            if (bound.everWatched || tick - obj.spawnTick >= 2) end(obj);
            return;
        }
        bound.everWatched = true;
        read(obj, bound);
    }

    private void read(CgNetObject obj, Bound bound) {
        boolean values = !comparesOnTouch || bound.touched;
        for (int k = 0; k < keys.length; k++) {
            Key key = keys[k];
            switch (key.kind) {
                case PRIMITIVE -> obj.setUnchecked(key.index, key.get.apply(bound.model));
                case PART -> {
                    CgNetObject child = key.part.model.live.get(key.get.apply(bound.model));
                    obj.setUnchecked(key.index, child != null && child.isLive() ? child.id() : 0);
                }
                case LIST -> {
                    if (!values) continue;
                    List<Object> want = (List<Object>) key.get.apply(bound.model);
                    byte[] now = encode(key.codec, want);
                    if (Arrays.equals(now, bound.sent[k])) continue;
                    bound.sent[k] = now;
                    diff((CgNetList<Object>) key.field, obj.list((CgNetList<Object>) key.field), want, bound.elements(k));
                }
                case VALUE, COMPUTED, MAP -> {
                    if (!values) continue;
                    Object value = key.kind == Kind.MAP ? new ArrayList<>(((Map<?, ?>) key.get.apply(bound.model)).keySet())
                            : key.get.apply(bound.model);
                    if (key.field.kind != CgNetField.Kind.CODEC) {
                        obj.setUnchecked(key.index, value);
                        continue;
                    }
                    byte[] now = encode(key.field, value);
                    if (!Arrays.equals(now, bound.sent[k])) {
                        bound.sent[k] = now;
                        obj.pulled(key.index, value);
                    }
                }
            }
        }
        bound.touched = false;
    }

    /**
     * Brings {@code have} to {@code want} by key: what is gone removed, what is new added at its place, what moved
     * moved, and an element whose encoding changed set.
     */
    private static void diff(CgNetList<Object> field, CgNetList.Entries<Object> have, List<Object> want, Map<Object, byte[]> sent) {
        Function<Object, ?> keyOf = field.key;
        Map<Object, Object> wanted = new HashMap<>(want.size() * 2);
        for (Object e : want) wanted.put(keyOf.apply(e), e);
        for (int i = have.size() - 1; i >= 0; i--) {
            Object k = keyOf.apply(have.get(i));
            if (!wanted.containsKey(k)) {
                have.remove(k);
                sent.remove(k);
            }
        }
        for (int i = 0; i < want.size(); i++) {
            Object e = want.get(i);
            Object k = keyOf.apply(e);
            byte[] bytes = CgBinaryFormat.encode(field.elements.encode(CgPlainOps.INSTANCE, e));
            int at = have.indexOf(k);
            if (at < 0) {
                have.add(i, e);
            } else {
                if (at != i) have.move(k, i);
                if (!Arrays.equals(bytes, sent.get(k))) have.set(k, e);
            }
            sent.put(k, bytes);
        }
    }

    /** Marks {@code model}'s values to be compared at the next tick: a type that {@code comparesOnTouch}. */
    void touch(Object model) {
        CgNetObject obj = live.get(model);
        if (obj != null && obj.bound != null) obj.bound.touched = true;
    }

    private void end(CgNetObject obj) {
        Bound bound = obj.bound;
        if (comparesOnTouch && CgNetType.DEVELOPMENT && bound != null) untouchedCheck(obj, bound);
        live.remove(bound.model);
        if (obj.isLive()) obj.despawn();
    }

    /** A development run's check that nothing changed without a {@code touch}. */
    private void untouchedCheck(CgNetObject obj, Bound bound) {
        for (int k = 0; k < keys.length; k++) {
            Key key = keys[k];
            if (key.kind != Kind.VALUE || key.field.kind != CgNetField.Kind.CODEC || bound.sent[k] == null) continue;
            if (!Arrays.equals(encode(key.field, key.get.apply(bound.model)), bound.sent[k])) {
                LOGGER.warn("[cg-net] {}'s '{}' changed without touch(model): its watchers never saw it", type.name, key.name);
            }
        }
    }

    /** Why {@code peer} may not change {@code key} of {@code obj} to {@code value}, or null when it may. */
    @Nullable
    String refusal(CgNetObject obj, Key key, @Nullable Object value, CgPeer peer) {
        if (key.policy.readOnly() || key.policy.serverOnly()) return CgNetCommand.REFUSED_READ_ONLY;
        if (value == null || !key.inRange(value)) return CgNetCommand.REFUSED_OUT_OF_RANGE;
        return editableBy(obj, peer) ? null : CgNetCommand.REFUSED_NOT_ALLOWED;
    }

    /** Whether {@code peer} passes this type's edit guard for {@code obj}: what a watcher is told as it arrives. */
    boolean editableBy(CgNetObject obj, CgPeer peer) {
        BiPredicate<Object, CgPeer> guard = mayEdit;
        Bound bound = obj.bound;
        try {
            return guard == null || bound != null && guard.test(bound.root, peer);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the edit guard of {} failed", type.name, failed);
            return false;
        }
    }

    /** An edit taken: the model's setter, or its held value decoded into, then each {@code afterEdit}. */
    @SuppressWarnings("unchecked")
    void edited(CgNetObject obj, Key key, Object value, CgPeer peer) {
        Bound bound = obj.bound;
        if (bound == null) return;
        store(bound.model, key, value);
        if (key.field.kind == CgNetField.Kind.CODEC) bound.sent[key.slot] = encode(key.field, value);
        for (BiConsumer<Object, CgPeer> after : afterEdit) {
            try {
                after.accept(bound.root, peer);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] an afterEdit of {} failed", type.name, failed);
            }
        }
    }

    private static void store(Object model, Key key, Object value) {
        if (key.set != null) {
            key.set.accept(model, value);
            return;
        }
        Object held = key.get.apply(model);
        key.codec.decode(CgPlainOps.INSTANCE, key.codec.encode(CgPlainOps.INSTANCE, value), held);
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(CgNetField<?> field, @Nullable Object value) {
        return encode((CgCodec<Object>) field.codec, value);
    }

    private static byte[] encode(CgCodec<Object> codec, @Nullable Object value) {
        if (value == null) return new byte[0];
        return CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, value));
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    /** The client's own instance of {@code obj}, made by the codec's factory at its first field. */
    @Nullable
    Object instance(CgNetObject obj) {
        if (obj.bound == null) {
            if (codec.factory() == null) return null;
            Object made = codec.factory().get();
            obj.bound = new Bound(made, made, 0);
        }
        return obj.bound.model;
    }

    /** Fields that arrived, written into the client's instance through the codec's setters. */
    void applied(CgNetObject obj, int[] changed, int count) {
        Object model = instance(obj);
        if (model == null) return;
        // a part whose parent arrived first: linked now, which fills it whole
        Runnable waiting = obj.client == null ? null : obj.client.partLinks.remove(obj.id());
        if (waiting != null) {
            waiting.run();
            return;
        }
        for (int c = 0; c < count; c++) {
            Key key = keyAt(changed[c]);
            if (key == null || key.kind == Kind.COMPUTED || key.get == null) continue;
            if (key.kind == Kind.PART) {
                link(obj, key, model);
                continue;
            }
            Object value = obj.valueAt(key.index);
            if (value == null) continue;
            if (key.kind == Kind.LIST) {
                List<Object> entries = ((CgNetList.Entries<Object>) value).snapshot();
                List<Object> held = (List<Object>) key.get.apply(model);
                if (key.set == null && held != null) {
                    held.clear();
                    held.addAll(entries);
                } else if (key.set != null) {
                    key.set.accept(model, entries);
                }
                continue;
            }
            if (key.kind == Kind.MAP) {
                // the keys arrive alone: a key gone takes its entry with it, and an entry arrives when opened
                Map<?, ?> held = (Map<?, ?>) key.get.apply(model);
                if (held != null) held.keySet().retainAll(new HashSet<>((List<?>) value));
                continue;
            }
            try {
                store(model, key, value);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] {}'s '{}' could not be written into its instance", type.name, key.name, failed);
            }
        }
        if (entry) linkEntry(obj);
    }

    /** An entry's fields arrived: its value goes into the map its parent's instance holds. */
    @SuppressWarnings("unchecked")
    void linkEntry(CgNetObject obj) {
        Entry entry = (Entry) instance(obj);
        if (entry == null || entry.map != null || obj.client == null) return;
        CgNetObject parent = obj.client.object(entry.parent);
        if (parent == null || parent.type.model == null) return;
        Object parentModel = parent.type.model.instance(parent);
        for (Key k : parent.type.model.keys) {
            if (k.kind != Kind.MAP || k.part.model != this) continue;
            entry.map = (Map<Object, Object>) k.get.apply(parentModel);
            entry.value(entry.value);
            return;
        }
    }

    /** A part arrived or changed: its object's instance is the one the parent holds, or is set into it. */
    private void link(CgNetObject obj, Key key, Object model) {
        int id = (Integer) obj.valueAt(key.index);
        if (obj.client == null || id == 0) return;
        CgNetObject child = obj.client.object(id);
        if (child == null) {
            obj.client.partLinks.put(id, () -> link(obj, key, model));
            return;
        }
        if (key.set == null) {
            Object held = key.get.apply(model);
            Object made = key.part.model.instance(child);
            if (held != null && made != held) {
                // the parent holds its part for good: the child's fields are written into that one
                child.bound = new Bound(held, model, 0);
                key.part.model.refill(child);
            }
        } else {
            key.set.accept(model, key.part.model.instance(child));
        }
    }

    /** Every field held so far, written into the instance again. */
    private void refill(CgNetObject obj) {
        int[] all = new int[keys.length];
        for (int k = 0; k < keys.length; k++) all[k] = keys[k].index;
        applied(obj, all, all.length);
    }
}
