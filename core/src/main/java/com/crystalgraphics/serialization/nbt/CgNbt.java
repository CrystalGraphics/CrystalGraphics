package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgStateMap;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.ParsePosition;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiConsumer;

/**
 * Minecraft's NBT, the same on every version: build tags, read them, run codecs over them, print and parse them as
 * SNBT, store them as binary NBT, and move them to and from the game's own tags. The tags themselves are
 * {@link CgNbtTag}'s ({@link CgNbtCompound}, {@link CgNbtList}, {@link CgNbtValue}); this class is how you get one and where it goes.
 *
 * <pre>{@code
 * // the game's tags in, yours out, and back
 * CgNbtCompound data = CgNbt.of(entity.saveWithoutId(new CompoundTag()));     // typed by what you assign it to
 * data.getOrCreateCompound("ForgeData").put("mymod:charge", 3);
 * CgNbt.applyTo(compoundTag, data);                                         // into the game's compound, in place
 * CompoundTag fresh = CgNbt.toHost(data);                                   // or a new one
 *
 * // a codec's value inside the game's container, no NBT code of your own
 * tag.put("character", CgNbt.toHost(Character.CODEC, character));
 * Character character = CgNbt.fromHost(Character.CODEC, tag.get("character"));
 *
 * // with no game at all: a test, a tool, the harness
 * CgNbtCompound preset = CgNbt.parse("{style:3b,dyed:1b,colors:[I;16711680,255]}");
 * CgNbt.encode(Hair.CODEC, hair).toString();                                // SNBT, as /data get prints it
 * CgNbtCompound level = CgFiles.NBT.read(Path.of("level.dat"), CgNbtCompound.CODEC);   // binary NBT, gzip detected
 * }</pre>
 *
 * <ul>
 *   <li>Everything but the host calls ({@code of}, {@code toHost}, {@code applyTo}, {@code fromHost}) runs with no game:
 *       a unit test, the harness and a tool read, build, encode and print tags the same way. The host calls throw
 *       outside Minecraft, naming the slot.</li>
 *   <li>The host calls copy: a {@code CgNbtTag} never aliases a game tag, so reading one and editing it does not change
 *       the game's until you {@code applyTo} or {@code toHost}.</li>
 *   <li>A game tag's kind is as Minecraft has it on that version. A long array needs 1.12 or later; a list holds one kind of tag.</li>
 * </ul>
 */
public final class CgNbt {

    /** What a host answers, both sides: the tags of its version as primitives. One class per era: {@code NbtModern}, {@code NbtLegacy}, {@code Nbt1710}. */
    public interface Host {

        /** The tag's kind id ({@link CgNbtTag.Type#id}), or 0 if {@code tag} is not a tag. */
        int kind(Object tag);

        /** A new, empty compound. */
        Object compound();

        void put(Object compound, String key, Object value);

        /** A compound's keys, in its own order. */
        Collection<String> keys(Object compound);

        @Nullable
        Object get(Object compound, String key);

        /** A new, empty list. */
        Object list();

        void add(Object list, Object value);

        /** A list's tags in order, to read once and drop: it may be the list itself. */
        List<?> elements(Object list);

        Object string(String value);

        String stringOf(Object tag);

        /** A number's tag of the width {@code type} names ({@code BYTE} to {@code DOUBLE}). */
        Object number(CgNbtTag.Type type, Number value);

        /** A numeric tag's value, boxed as its own width. */
        Number numberOf(Object tag);

        Object bytes(byte[] value);

        byte[] bytesOf(Object tag);

        Object ints(int[] value);

        int[] intsOf(Object tag);

        /** A long array tag; an era without them (1.8.9 to 1.11.2, 1.7.10) throws, naming it. */
        Object longs(long[] value);

        long[] longsOf(Object tag);

        /** No tags: every operation throws. */
        Host NONE = new Host() {
            @Override public int kind(Object tag) { throw absent(); }
            @Override public Object compound() { throw absent(); }
            @Override public void put(Object compound, String key, Object value) { throw absent(); }
            @Override public Collection<String> keys(Object compound) { throw absent(); }
            @Override public Object get(Object compound, String key) { throw absent(); }
            @Override public Object list() { throw absent(); }
            @Override public void add(Object list, Object value) { throw absent(); }
            @Override public List<?> elements(Object list) { throw absent(); }
            @Override public Object string(String value) { throw absent(); }
            @Override public String stringOf(Object tag) { throw absent(); }
            @Override public Object number(CgNbtTag.Type type, Number value) { throw absent(); }
            @Override public Number numberOf(Object tag) { throw absent(); }
            @Override public Object bytes(byte[] value) { throw absent(); }
            @Override public byte[] bytesOf(Object tag) { throw absent(); }
            @Override public Object ints(int[] value) { throw absent(); }
            @Override public int[] intsOf(Object tag) { throw absent(); }
            @Override public Object longs(long[] value) { throw absent(); }
            @Override public long[] longsOf(Object tag) { throw absent(); }
        };
    }

    /** The slot a host fills, once, on both sides: {@code CgPlatform.provide(CgNbt.HOST, new NbtModern())}. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:nbt", Host.NONE);

    /**
     * A codec over a class's own NBT methods: what a class that already saves itself is declared with, inside a codec of
     * yours or as a networked type's key. Decodes into a held object only.
     *
     * <pre>{@code
     * .into("inventory", CgNbt.methods(Inventory::save, Inventory::load), npc -> npc.inventory)
     * }</pre>
     */
    public static <A> CgNbtMethods<A> methods(Function<? super A, CgNbtCompound> save, BiConsumer<? super A, CgNbtCompound> load) {
        return new CgNbtMethods<>(save, load);
    }

    private CgNbt() {
    }

    // ── Make ────────────────────────────────────────────────────────────────

    /** A new, empty compound. */
    public static CgNbtCompound compound() {
        return new CgNbtCompound();
    }

    /** A new, empty list. */
    public static CgNbtList list() {
        return new CgNbtList();
    }

    /**
     * A tag from SNBT, Minecraft's text form: {@code CgNbt.parse("{Count:1b,id:\"minecraft:stone\"}")}. Typed by what you
     * assign it to.
     *
     * @throws CgCodecException where it is not SNBT, naming the position
     */
    @SuppressWarnings("unchecked")
    public static <T extends CgNbtTag> T parse(String snbt) {
        return (T) Snbt.parse(snbt);
    }

    /**
     * One tag from the middle of a text, as a command's argument holds it: read from {@code position}'s index, which is
     * left just after the tag.
     *
     * <pre>{@code
     * ParsePosition at = new ParsePosition("minecraft:sheep".length());
     * CgNbtCompound data = CgNbt.parse("minecraft:sheep{Color:2} ~ ~ ~", at);   // {Color:2}; at is 24
     * }</pre>
     *
     * @throws CgCodecException where no tag starts there, naming the position
     */
    @SuppressWarnings("unchecked")
    public static <T extends CgNbtTag> T parse(String text, ParsePosition position) {
        return (T) Snbt.read(text, position);
    }

    // ── Codecs ──────────────────────────────────────────────────────────────

    /** {@code value} as tags through {@code codec}: a compound for a record, a string tag for a string. */
    public static <V> CgNbtTag encode(CgCodec<V> codec, V value) {
        return codec.encode(CgNbtOps.INSTANCE, value);
    }

    /** What {@link #encode} wrote, back through {@code codec}. */
    public static <V> V decode(CgCodec<V> codec, CgNbtTag tag) {
        return codec.decode(CgNbtOps.INSTANCE, tag);
    }

    /** What {@link #encode} wrote, decoded into {@code target}: a live object refreshed from its tag. */
    public static <V> V decode(CgCodec<V> codec, CgNbtTag tag, V target) {
        return codec.decode(CgNbtOps.INSTANCE, tag, target);
    }

    /** A state map to fill and then {@link CgStateMap#encode()} into a tag: the notepad a method is handed to write itself. */
    public static CgStateMap<CgNbtTag> state() {
        return new CgStateMap<>(CgNbtOps.INSTANCE);
    }

    /** A state map to read {@code compound} through, each read with a default. */
    public static CgStateMap<CgNbtTag> state(CgNbtCompound compound) {
        return new CgStateMap<>(CgNbtOps.INSTANCE, compound);
    }

    // ── The game's tags ─────────────────────────────────────────────────────

    /** Whether the host has tags: false in a test and in the harness. */
    public static boolean present() {
        return CgPlatform.get(HOST) != Host.NONE;
    }

    /**
     * A copy of the game's {@code tag} as ours. Typed by what you assign it to ({@code CgNbtCompound}, {@code CgNbtList}):
     * another kind is a {@code ClassCastException} there. Null in, null out; a {@link CgNbtTag} is answered as it is.
     *
     * @throws IllegalStateException outside Minecraft
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends CgNbtTag> T of(@Nullable Object tag) {
        if (tag == null) return null;
        if (tag instanceof CgNbtTag ours) return (T) ours;
        return (T) fromHost(host(), tag);
    }

    /**
     * A new tag of the game's, built from ours: typed as the caller takes it ({@code CompoundTag}, {@code NBTTagCompound},
     * {@code Tag}). Asking for a kind it did not make is a {@code ClassCastException} there.
     *
     * @throws IllegalStateException outside Minecraft
     */
    @SuppressWarnings("unchecked")
    public static <N> N toHost(CgNbtTag tag) {
        return (N) toHost(host(), tag);
    }

    /**
     * Puts every key of {@code tag} into the game's {@code hostCompound}, replacing a key it already has, in place: an
     * entity's or item's live data edited without making a new one. Keys not in {@code tag} are left as they are.
     */
    public static void applyTo(Object hostCompound, CgNbtCompound tag) {
        Host host = host();
        if (host.kind(hostCompound) != CgNbtTag.Type.COMPOUND.id) throw new CgCodecException("not a compound tag: " + hostCompound);
        for (Map.Entry<String, CgNbtTag> e : tag.raw().entrySet()) host.put(hostCompound, e.getKey(), toHost(host, e.getValue()));
    }

    /** {@code value} through {@code codec} as the game's tags: {@code tag.put("character", CgNbt.toHost(Character.CODEC, c))}. */
    public static <V, N> N toHost(CgCodec<V> codec, V value) {
        return toHost(encode(codec, value));
    }

    /** What {@link #toHost(CgCodec, Object)} wrote, back through {@code codec}. */
    public static <V> V fromHost(CgCodec<V> codec, Object hostTag) {
        return decode(codec, CgNbt.<CgNbtTag>of(hostTag));
    }

    /** The game's tag decoded into {@code target}: an entity's live data refreshed from its tag. */
    public static <V> V fromHost(CgCodec<V> codec, Object hostTag, V target) {
        return decode(codec, CgNbt.<CgNbtTag>of(hostTag), target);
    }

    private static IllegalStateException absent() {
        return new IllegalStateException("no game tags here: crystalgraphics:nbt has no host (a test, the harness)");
    }

    private static Host host() {
        Host host = CgPlatform.get(HOST);
        if (host == Host.NONE) throw absent();
        return host;
    }

    private static CgNbtTag fromHost(Host host, Object tag) {
        CgNbtTag.Type type = CgNbtTag.Type.of(host.kind(tag));
        if (type == null) throw new CgCodecException("not a tag: " + tag);
        switch (type) {
            case COMPOUND: {
                CgNbtCompound out = new CgNbtCompound();
                for (String key : host.keys(tag)) {
                    Object child = host.get(tag, key);
                    if (child != null) out.put(key, fromHost(host, child));
                }
                return out;
            }
            case LIST: {
                CgNbtList out = new CgNbtList();
                for (Object element : host.elements(tag)) out.add(fromHost(host, element));
                return out;
            }
            case STRING: return CgNbtValue.ofString(host.stringOf(tag));
            case BYTE_ARRAY: return CgNbtValue.ofBytes(host.bytesOf(tag));
            case INT_ARRAY: return CgNbtValue.ofInts(host.intsOf(tag));
            case LONG_ARRAY: return CgNbtValue.ofLongs(host.longsOf(tag));
            default: return CgNbtValue.ofNumber(host.numberOf(tag));
        }
    }

    private static Object toHost(Host host, CgNbtTag tag) {
        if (tag instanceof CgNbtCompound compound) {
            Object out = host.compound();
            for (Map.Entry<String, CgNbtTag> e : compound.raw().entrySet()) host.put(out, e.getKey(), toHost(host, e.getValue()));
            return out;
        }
        if (tag instanceof CgNbtList list) {
            Object out = host.list();
            for (CgNbtTag element : list.raw()) host.add(out, toHost(host, element));
            return out;
        }
        CgNbtValue value = (CgNbtValue) tag;
        switch (value.type()) {
            case STRING: return host.string(value.asString());
            case BYTE_ARRAY: return host.bytes(value.bytes());
            case INT_ARRAY: return host.ints(value.ints());
            case LONG_ARRAY: return host.longs(value.longs());
            default: return host.number(value.type(), value.number());
        }
    }

    // ── Binary NBT ──────────────────────────────────────────────────────────

    /** A compound from binary NBT, gzip detected: {@code level.dat}, a structure's {@code .nbt}. */
    public static CgNbtCompound readBinary(InputStream in) throws IOException {
        return NbtBinary.read(in, true);
    }

    /** {@code tag} as binary NBT with an empty root name, gzipped as Minecraft's files are. */
    public static void writeBinary(OutputStream out, CgNbtCompound tag) throws IOException {
        NbtBinary.write(out, tag, true);
    }

    /** {@code tag} as binary NBT, gzipped or not. */
    public static void writeBinary(OutputStream out, CgNbtCompound tag, boolean gzip) throws IOException {
        NbtBinary.write(out, tag, gzip);
    }

    /** {@code tag} as the bytes of {@link #writeBinary(OutputStream, CgNbtCompound)}. */
    public static byte[] toBytes(CgNbtCompound tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeBinary(bytes, tag);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A compound from {@link #toBytes} or any binary NBT bytes. */
    public static CgNbtCompound fromBytes(byte[] bytes) {
        try {
            return readBinary(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new CgCodecException("NBT: " + e.getMessage(), e);
        }
    }

    // ── Through other formats ───────────────────────────────────────────────

    static <T> T walk(CgDynamicOps<T> ops, CgNbtTag tag) {
        if (tag instanceof CgNbtCompound compound) {
            Map<T, T> out = new LinkedHashMap<>(compound.size() * 2);
            for (Map.Entry<String, CgNbtTag> e : compound.raw().entrySet()) out.put(ops.createString(e.getKey()), walk(ops, e.getValue()));
            return ops.createMap(out);
        }
        if (tag instanceof CgNbtList list) {
            List<T> out = new ArrayList<>(list.size());
            for (CgNbtTag element : list.raw()) out.add(walk(ops, element));
            return ops.createList(out);
        }
        CgNbtValue value = (CgNbtValue) tag;
        switch (value.type()) {
            case STRING: return ops.createString(value.asString());
            case BYTE_ARRAY: return ops.createBytes(value.bytes());
            case INT_ARRAY: {
                List<T> out = new ArrayList<>();
                for (int i : value.ints()) out.add(ops.createNumber(i));
                return ops.createList(out);
            }
            case LONG_ARRAY: {
                List<T> out = new ArrayList<>();
                for (long l : value.longs()) out.add(ops.createNumber(l));
                return ops.createList(out);
            }
            default: return ops.createNumber(value.number());
        }
    }

    /** A plain tree ({@link CgDynamicOps#toPlain}'s) as tags: the widths a text format kept, a mixed list of numbers as doubles. */
    static CgNbtTag fromPlain(@Nullable Object plain) {
        if (plain == null) return new CgNbtCompound();
        if (plain instanceof CgNbtTag tag) return tag;
        if (plain instanceof String s) return CgNbtValue.ofString(s);
        if (plain instanceof Boolean b) return CgNbtValue.ofBool(b);
        if (plain instanceof Number n) return CgNbtValue.ofNumber(n);
        if (plain instanceof byte[] bytes) return CgNbtValue.ofBytes(bytes);
        if (plain instanceof Map<?, ?> map) {
            CgNbtCompound out = new CgNbtCompound();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), fromPlain(e.getValue()));
            return out;
        }
        if (plain instanceof List<?> list) {
            List<CgNbtTag> items = new ArrayList<>(list.size());
            boolean allNumbers = true;
            boolean mixedNumbers = false;
            for (Object element : list) {
                CgNbtTag item = fromPlain(element);
                if (!items.isEmpty() && items.get(0).type() != item.type()) mixedNumbers = true;
                if (!(item instanceof CgNbtValue v && v.isNumber())) allNumbers = false;
                items.add(item);
            }
            CgNbtList out = new CgNbtList();
            for (CgNbtTag item : items) {
                out.add(allNumbers && mixedNumbers ? CgNbtValue.ofDouble(((CgNbtValue) item).asDouble()) : item);
            }
            return out;
        }
        throw new CgCodecException("not a plain tree: a " + plain.getClass().getName());
    }
}
