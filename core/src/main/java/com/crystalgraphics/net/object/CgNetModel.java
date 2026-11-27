package com.crystalgraphics.net.object;

import java.util.ArrayList;
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
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.serialization.CgPlainOps;

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
    enum Kind { PRIMITIVE, VALUE, PART, COMPUTED }

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
        /** A part's type, whose objects are this key's values. */
        @Nullable
        final CgNetType<Object> part;
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

    private CgNetModel(CgObjectCodec<T> codec, Key[] keys, boolean comparesOnTouch) {
        this.codec = codec;
        this.keys = keys;
        this.comparesOnTouch = comparesOnTouch;
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
            if (c instanceof CgObjectCodec<?> nested) {
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
            else if (kind != Kind.PART && !policy.readOnly()) field.predicted();
            keys.add(new Key(k.key(), kind, field, k.getter(), k.setter(), c, policy, part));
        }
        for (CgNetType.Computed<T, ?> made : computed) {
            keys.add(new Key(made.field().name, Kind.COMPUTED, made.field(), made.value(), null, null,
                    new CgObjectCodec.Policy(false, true, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), null));
        }
        for (int k = 0; k < keys.size(); k++) keys.get(k).slot = k;
        return new CgNetModel<>(codec, keys.toArray(new Key[0]), comparesOnTouch);
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
    void bind(CgNetType<T> type) {
        this.type = type;
        for (Key k : keys) k.index = type.require(k.field);
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
                case VALUE, COMPUTED -> {
                    if (!values) continue;
                    Object value = key.get.apply(bound.model);
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

    /** Whether {@code peer} may change {@code key} of {@code obj} to {@code value}: its guard, its range. */
    boolean mayEdit(CgNetObject obj, Key key, @Nullable Object value, CgPeer peer) {
        if (key.policy.readOnly() || key.policy.serverOnly() || value == null || !key.inRange(value)) return false;
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
        if (value == null) return new byte[0];
        return CgBinaryFormat.encode(((CgCodec<Object>) field.codec).encode(CgPlainOps.INSTANCE, value));
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
            try {
                store(model, key, value);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] {}'s '{}' could not be written into its instance", type.name, key.name, failed);
            }
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
