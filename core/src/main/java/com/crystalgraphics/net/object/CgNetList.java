package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgPlainOps;

/**
 * A field holding a list of keyed entries that sends what changed in it (an add, a remove, a change, a move) rather
 * than the whole list. A viewer who arrives later gets it whole. Listed in its type like any field.
 *
 * <pre>{@code
 * record Offer(String id, String item, int price) { static final CgCodec<Offer> CODEC = ...; }
 *
 * public static final CgNetList<Offer> OFFERS = CgNetList.of("offers", Offer.CODEC, Offer::id);
 * public static final CgNetType TRADER = CgNetType.define("mymod:trader").fields(OFFERS).watched().build();
 *
 * // server
 * CgNetList.Entries<Offer> offers = trader.list(OFFERS);
 * offers.add(new Offer("a", "minecraft:emerald", 3));
 * offers.set("a", new Offer("a", "minecraft:emerald", 4));    // one entry sent, however long the list
 * offers.move("a", 0);
 * offers.remove("a");
 *
 * // client
 * TRADER.onClient(obj -> new CgNetBehaviour() {
 *     @Override public void spawned(CgNetObject obj, float age) {
 *         obj.list(OFFERS).listen(new CgNetList.Listener<>() {
 *             @Override public void added(int index, Offer offer) { rows.add(index, offer); }
 *             @Override public void removed(int index, Offer offer) { rows.remove(index); }
 *         });
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A key is unique in its list: adding a second entry with a key already there throws.</li>
 *   <li>Changes made in a tick leave together at its end, in the order made; a client applies them in that order.</li>
 *   <li>Only the server changes it, through {@link CgNetObject#list}; {@code set(field, value)} refuses a list.</li>
 *   <li>Saved like any field, as the list of its entries.</li>
 * </ul>
 *
 * <p>Ported from Unity Netcode for GameObjects' {@code NetworkList} (MIT): an event per operation, applied in order,
 * a full copy for a new client. Unreal's {@code FFastArraySerializer} was read for shape only.</p>
 */
public final class CgNetList<E> extends CgNetField<CgNetList.Entries<E>> {

    /** On the wire, a list field's value is whole or the operations since the last send. */
    static final int WHOLE = 0, OPS = 1;
    static final int ADD = 0, REMOVE = 1, SET = 2, MOVE = 3, CLEAR = 4;

    final CgCodec<E> elements;
    final Function<E, ?> key;

    private CgNetList(String name, CgCodec<E> elements, Function<E, ?> key) {
        super(name, Kind.CODEC, entriesCodec(elements, key), null, 0);
        this.elements = elements;
        this.key = key;
    }

    /** A list of {@code elements}, each keyed by {@code key}: an id, unique in the list. */
    public static <E> CgNetList<E> of(String name, CgCodec<E> elements, Function<E, ?> key) {
        return new CgNetList<>(name, Objects.requireNonNull(elements, "elements"), Objects.requireNonNull(key, "key"));
    }

    private static <E> CgCodec<Entries<E>> entriesCodec(CgCodec<E> elements, Function<E, ?> key) {
        return CgCodecs.xmap(CgCodecs.listOf(elements), list -> {
            Entries<E> entries = new Entries<>(null, key, elements);
            for (E each : list) entries.put(entries.items.size(), each);
            return entries;
        }, Entries::snapshot);
    }

    // The field's rules, answering the list: a list declared with one is still a list.

    @Override
    public CgNetList<E> ownerOnly() {
        super.ownerOnly();
        return this;
    }

    @Override
    public CgNetList<E> skipOwner() {
        super.skipOwner();
        return this;
    }

    @Override
    public CgNetList<E> initialOnly() {
        super.initialOnly();
        return this;
    }

    @Override
    public CgNetList<E> persisted() {
        super.persisted();
        return this;
    }

    @Override
    public CgNetList<E> watched() {
        super.watched();
        return this;
    }

    /** Never predicted: a list is the server's to change. */
    @Override
    public CgNetField<Entries<E>> predicted() {
        throw new IllegalStateException(name + " is a list: the server changes it");
    }

    /** Whole for a spawn or a baseline, else the operations since the last send. */
    @Override
    void write(NetBuffer out, long bits, Object ref, boolean whole) {
        @SuppressWarnings("unchecked") Entries<E> entries = (Entries<E>) ref;
        if (entries == null) {
            out.writeByte(WHOLE);
            out.writeVarInt(0);
            return;
        }
        if (whole || entries.ops == null) {
            out.writeByte(WHOLE);
            out.writeVarInt(entries.items.size());
            for (E each : entries.items) entries.writeElement(out, each);
        } else {
            out.writeByte(OPS);
            out.writeVarInt(entries.opCount);
            out.append(entries.ops, 0, entries.ops.size);
        }
    }

    /** A client: a whole list replaces what it held, operations apply to it, in place either way. */
    @Override
    Entries<E> readRef(NetBuffer in, int length, @Nullable Object current) {
        @SuppressWarnings("unchecked") Entries<E> held = current instanceof Entries ? (Entries<E>) current : null;
        if (held == null) held = new Entries<>(null, key, elements);
        int end = in.pos + length;
        int form = in.readByte();
        int count = in.readVarInt();
        if (form == WHOLE) {
            held.items.clear();
            held.keys.clear();
            for (int i = 0; i < count; i++) held.put(i, held.readElement(in));
            held.tellReset();
        } else {
            for (int i = 0; i < count; i++) held.apply(in);
        }
        in.pos = end;
        return held;
    }

    /** What a client hears of each change to a list, in order. Every method is optional. */
    public interface Listener<E> {
        default void added(int index, E entry) {
        }

        default void removed(int index, E entry) {
        }

        default void replaced(int index, E was, E now) {
        }

        default void moved(int from, int to) {
        }

        /** It was replaced whole: read it again. */
        default void reset() {
        }
    }

    /**
     * One object's list: on the server what changes it, on a client what it holds and who hears of its changes.
     *
     * <pre>{@code
     * Entries<Offer> offers = trader.list(OFFERS);
     * for (int i = 0; i < offers.size(); i++) show(offers.get(i));
     * Offer cheapest = offers.find("a");
     * }</pre>
     */
    public static final class Entries<E> {
        @Nullable
        CgNetObject owner;
        int index;
        final Function<E, ?> key;
        final CgCodec<E> elements;
        final ArrayList<E> items = new ArrayList<>();
        final ArrayList<Object> keys = new ArrayList<>();
        /** Server: this tick's operations, encoded as they happen; null until the first. */
        @Nullable
        NetBuffer ops;
        int opCount;
        private final List<Listener<E>> listeners = new ArrayList<>(1);

        Entries(@Nullable CgNetObject owner, Function<E, ?> key, CgCodec<E> elements) {
            this.owner = owner;
            this.key = key;
            this.elements = elements;
        }

        public int size() {
            return items.size();
        }

        public E get(int index) {
            return items.get(index);
        }

        /** The entry with {@code key}, or null. */
        @Nullable
        public E find(Object key) {
            int at = keys.indexOf(key);
            return at < 0 ? null : items.get(at);
        }

        public int indexOf(Object key) {
            return keys.indexOf(key);
        }

        /** Its entries now, in order: a copy. */
        public List<E> snapshot() {
            return new ArrayList<>(items);
        }

        /** Its entries, read-only and live. */
        public List<E> view() {
            return Collections.unmodifiableList(items);
        }

        // ── Server ──────────────────────────────────────────────────────────────────────────────

        /** Appends {@code entry}. */
        public Entries<E> add(E entry) {
            return add(items.size(), entry);
        }

        /** Inserts {@code entry} at {@code index}. */
        public Entries<E> add(int index, E entry) {
            writable();
            Object k = key.apply(Objects.requireNonNull(entry, "entry"));
            if (keys.contains(k)) throw new IllegalArgumentException("the key " + k + " is in the list already");
            put(index, entry);
            op(ADD).writeVarInt(index);
            writeElement(ops, entry);
            return this;
        }

        /** Replaces the entry with {@code key} by {@code entry}; nothing when it is not there. */
        public Entries<E> set(Object key, E entry) {
            writable();
            int at = keys.indexOf(key);
            if (at < 0) return this;
            items.set(at, Objects.requireNonNull(entry, "entry"));
            keys.set(at, this.key.apply(entry));
            op(SET).writeVarInt(at);
            writeElement(ops, entry);
            return this;
        }

        /** Removes the entry with {@code key}; nothing when it is not there. */
        public Entries<E> remove(Object key) {
            writable();
            int at = keys.indexOf(key);
            if (at < 0) return this;
            items.remove(at);
            keys.remove(at);
            op(REMOVE).writeVarInt(at);
            return this;
        }

        /** Moves the entry with {@code key} to {@code index}, clamped to the list; nothing when it is not there. */
        public Entries<E> move(Object key, int index) {
            writable();
            int from = keys.indexOf(key);
            int to = Math.max(0, Math.min(index, items.size() - 1));
            if (from < 0 || from == to) return this;
            E entry = items.remove(from);
            Object k = keys.remove(from);
            items.add(to, entry);
            keys.add(to, k);
            op(MOVE).writeVarInt(from);
            ops.writeVarInt(to);
            return this;
        }

        public Entries<E> clear() {
            writable();
            if (items.isEmpty()) return this;
            items.clear();
            keys.clear();
            op(CLEAR);
            return this;
        }

        // ── Client ──────────────────────────────────────────────────────────────────────────────

        /** Hears each change, in order, from now. On a client, on its thread. */
        public Entries<E> listen(Listener<E> listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public Entries<E> unlisten(Listener<E> listener) {
            listeners.remove(listener);
            return this;
        }

        // ── Internals ───────────────────────────────────────────────────────────────────────────

        void put(int index, E entry) {
            items.add(index, entry);
            keys.add(index, key.apply(entry));
        }

        private void writable() {
            if (owner == null || !owner.server) throw new IllegalStateException("a list is changed on the server, through obj.list(field)");
        }

        private NetBuffer op(int kind) {
            if (ops == null) ops = new NetBuffer(64);
            ops.writeByte(kind);
            opCount++;
            owner.listChanged(index);
            return ops;
        }

        /** The operations were sent: the next tick starts empty. */
        void sent() {
            if (ops != null) ops.clear();
            opCount = 0;
        }

        void writeElement(NetBuffer out, E entry) {
            byte[] encoded = CgBinaryFormat.encode(elements.encode(CgPlainOps.INSTANCE, entry));
            out.writeVarInt(encoded.length);
            out.writeBytes(encoded, 0, encoded.length);
        }

        E readElement(NetBuffer in) {
            int length = in.readVarInt();
            byte[] encoded = new byte[length];
            System.arraycopy(in.bytes, in.pos, encoded, 0, length);
            in.skip(length);
            return elements.decode(CgPlainOps.INSTANCE, CgBinaryFormat.decode(encoded), null);
        }

        /** A client: one operation, applied and told. */
        void apply(NetBuffer in) {
            int kind = in.readByte();
            switch (kind) {
                case ADD: {
                    int at = Math.min(in.readVarInt(), items.size());
                    E entry = readElement(in);
                    put(at, entry);
                    for (Listener<E> l : listeners) l.added(at, entry);
                    return;
                }
                case REMOVE: {
                    int at = in.readVarInt();
                    if (at >= items.size()) return;
                    E was = items.remove(at);
                    keys.remove(at);
                    for (Listener<E> l : listeners) l.removed(at, was);
                    return;
                }
                case SET: {
                    int at = in.readVarInt();
                    E entry = readElement(in);
                    if (at >= items.size()) return;
                    E was = items.set(at, entry);
                    keys.set(at, key.apply(entry));
                    for (Listener<E> l : listeners) l.replaced(at, was, entry);
                    return;
                }
                case MOVE: {
                    int from = in.readVarInt(), to = in.readVarInt();
                    if (from >= items.size()) return;
                    E entry = items.remove(from);
                    Object k = keys.remove(from);
                    int at = Math.min(to, items.size());
                    items.add(at, entry);
                    keys.add(at, k);
                    for (Listener<E> l : listeners) l.moved(from, at);
                    return;
                }
                case CLEAR:
                    items.clear();
                    keys.clear();
                    tellReset();
                    return;
                default:
                    throw new CgCodecException("unknown list operation " + kind);
            }
        }

        void tellReset() {
            for (Listener<E> l : listeners) l.reset();
        }
    }
}
