package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A compound tag: named tags in the order they were put. It reads the way a {@code CompoundTag} does and does not
 * change its spelling between Minecraft versions; it is also the dynamic state map of anything the game saves.
 *
 * <pre>{@code
 * CgNbtCompound tag = CgNbt.compound()
 *         .put("name", "Goku")                   // a string
 *         .put("level", 9)                       // an int; putByte/putShort for the others
 *         .put("alive", true)                    // a byte, as Minecraft stores it
 *         .put("spell", Spell.CODEC, spell);     // anything with a codec
 *
 * int level = tag.getInt("level");               // 0 when absent or not a number
 * int lives = tag.getInt("lives", 3);            // the default is named
 * Spell back = tag.get("spell", Spell.CODEC);    // null when absent
 * String lore = tag.at("display.Lore.0").asString();   // a path through compounds and lists
 * tag.getOrCreateCompound("display").put("Name", "Sword");
 * }</pre>
 *
 * <ul>
 *   <li>Reads never throw for a missing key or the wrong kind: they answer the absent value or the default you pass.
 *       A number reads as any width, so {@code getInt} works on a double tag.</li>
 *   <li>{@code getCompound}/{@code getList} answer an empty tag that is <i>not</i> in this compound when the key is
 *       absent; use {@code getOrCreate...} to edit in place.</li>
 *   <li>{@code put(key, 5)} is an int; for a byte, a short or a float say so: {@code putByte}, {@code putShort}, {@code 5f}.</li>
 * </ul>
 */
public final class CgNbtCompound extends CgNbtTag {

    /** A compound as a codec's value, carried whole through any format: {@link CgNbtTag#CODEC} for a value that is always one. */
    public static final CgCodec<CgNbtCompound> CODEC = CgCodecs.xmap(CgNbtTag.CODEC, CgNbtTag::asCompound, c -> c);

    private final Map<String, CgNbtTag> entries = new LinkedHashMap<>();

    public CgNbtCompound() {
    }

    @Override
    public Type type() {
        return Type.COMPOUND;
    }

    @Override
    public CgNbtCompound copy() {
        CgNbtCompound out = new CgNbtCompound();
        for (Map.Entry<String, CgNbtTag> e : entries.entrySet()) out.entries.put(e.getKey(), e.getValue().copy());
        return out;
    }

    /** The entries without a view or a copy, for the library's own loops. */
    Map<String, CgNbtTag> raw() {
        return entries;
    }

    // ── Write ───────────────────────────────────────────────────────────────

    /** Stores {@code tag} itself under {@code key}; a null tag removes the key. */
    public CgNbtCompound put(String key, @Nullable CgNbtTag tag) {
        if (tag == null) entries.remove(key);
        else entries.put(key, tag);
        return this;
    }

    public CgNbtCompound put(String key, String value) {
        return value == null ? remove(key) : put(key, CgNbtValue.ofString(value));
    }

    public CgNbtCompound put(String key, int value) {
        return put(key, CgNbtValue.ofInt(value));
    }

    public CgNbtCompound put(String key, long value) {
        return put(key, CgNbtValue.ofLong(value));
    }

    public CgNbtCompound put(String key, float value) {
        return put(key, CgNbtValue.ofFloat(value));
    }

    public CgNbtCompound put(String key, double value) {
        return put(key, CgNbtValue.ofDouble(value));
    }

    /** A byte, 1 or 0, as Minecraft stores a boolean. */
    public CgNbtCompound put(String key, boolean value) {
        return put(key, CgNbtValue.ofBool(value));
    }

    public CgNbtCompound put(String key, byte[] value) {
        return put(key, CgNbtValue.ofBytes(value));
    }

    public CgNbtCompound put(String key, int[] value) {
        return put(key, CgNbtValue.ofInts(value));
    }

    public CgNbtCompound put(String key, long[] value) {
        return put(key, CgNbtValue.ofLongs(value));
    }

    public CgNbtCompound putByte(String key, int value) {
        return put(key, CgNbtValue.ofByte((byte) value));
    }

    public CgNbtCompound putShort(String key, int value) {
        return put(key, CgNbtValue.ofShort((short) value));
    }

    /** {@code value} encoded by {@code codec}; a null value removes the key. */
    public <V> CgNbtCompound put(String key, CgCodec<V> codec, @Nullable V value) {
        return put(key, value == null ? null : CgNbt.encode(codec, value));
    }

    public CgNbtCompound remove(String key) {
        entries.remove(key);
        return this;
    }

    public CgNbtCompound clear() {
        entries.clear();
        return this;
    }

    /**
     * Puts every key of {@code other} into this one; where both hold a compound under a key they are merged in turn,
     * as Minecraft's {@code merge} does. {@code other}'s tags are copied.
     */
    public CgNbtCompound merge(CgNbtCompound other) {
        for (Map.Entry<String, CgNbtTag> e : other.entries.entrySet()) {
            CgNbtTag mine = entries.get(e.getKey());
            if (mine instanceof CgNbtCompound a && e.getValue() instanceof CgNbtCompound b) a.merge(b);
            else entries.put(e.getKey(), e.getValue().copy());
        }
        return this;
    }

    /** Stores {@code tag} at a dotted {@code path}, making the compounds on the way: {@code putAt("display.Name", tag)}. */
    public CgNbtCompound putAt(String path, CgNbtTag tag) {
        CgNbtCompound at = this;
        int dot;
        String rest = path;
        while ((dot = rest.indexOf('.')) >= 0) {
            at = at.getOrCreateCompound(rest.substring(0, dot));
            rest = rest.substring(dot + 1);
        }
        at.put(rest, tag);
        return this;
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The keys, in order, not changeable through this view. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public void forEach(BiConsumer<String, CgNbtTag> action) {
        entries.forEach(action);
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    /** Whether {@code key} holds a tag of {@code type}. */
    public boolean has(String key, Type type) {
        CgNbtTag tag = entries.get(key);
        return tag != null && tag.type() == type;
    }

    /** The tag under {@code key}, or null. */
    @Nullable
    public CgNbtTag get(String key) {
        return entries.get(key);
    }

    /** The tag at a dotted {@code path} through compounds, and through lists by index ({@code "Items.0.id"}), or null. */
    @Nullable
    public CgNbtTag at(String path) {
        CgNbtTag at = this;
        for (String part : path.split("\\.")) {
            if (at instanceof CgNbtCompound c) at = c.entries.get(part);
            else if (at instanceof CgNbtList l && !part.isEmpty() && Character.isDigit(part.charAt(0))) at = l.get(Integer.parseInt(part));
            else return null;
            if (at == null) return null;
        }
        return at;
    }

    /** The value under {@code key} read through {@code codec}, or null when the key is absent. */
    @Nullable
    public <V> V get(String key, CgCodec<V> codec) {
        CgNbtTag tag = entries.get(key);
        return tag == null ? null : CgNbt.decode(codec, tag);
    }

    /** As {@link #get(String, CgCodec)}, {@code fallback} when the key is absent. */
    public <V> V get(String key, CgCodec<V> codec, V fallback) {
        V value = get(key, codec);
        return value == null ? fallback : value;
    }

    private CgNbtValue number(String key) {
        CgNbtTag tag = entries.get(key);
        return tag instanceof CgNbtValue v && v.isNumber() ? v : null;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    /** The string under {@code key}; {@code fallback} when it is absent or not a string. */
    public String getString(String key, String fallback) {
        CgNbtTag tag = entries.get(key);
        return tag instanceof CgNbtValue v && v.isString() ? v.asString() : fallback;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int fallback) {
        CgNbtValue v = number(key);
        return v == null ? fallback : v.asInt();
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long fallback) {
        CgNbtValue v = number(key);
        return v == null ? fallback : v.asLong();
    }

    public byte getByte(String key) {
        CgNbtValue v = number(key);
        return v == null ? 0 : v.asByte();
    }

    public short getShort(String key) {
        CgNbtValue v = number(key);
        return v == null ? 0 : v.asShort();
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public float getFloat(String key, float fallback) {
        CgNbtValue v = number(key);
        return v == null ? fallback : v.asFloat();
    }

    public double getDouble(String key) {
        return getDouble(key, 0d);
    }

    public double getDouble(String key, double fallback) {
        CgNbtValue v = number(key);
        return v == null ? fallback : v.asDouble();
    }

    public boolean getBool(String key) {
        return getBool(key, false);
    }

    /** A number other than zero is true; {@code fallback} when the key is absent or not a number. */
    public boolean getBool(String key, boolean fallback) {
        CgNbtValue v = number(key);
        return v == null ? fallback : v.asBool();
    }

    /** The bytes of a byte array under {@code key}; empty when absent. */
    public byte[] getBytes(String key) {
        return entries.get(key) instanceof CgNbtValue v ? v.bytes() : new byte[0];
    }

    public int[] getInts(String key) {
        return entries.get(key) instanceof CgNbtValue v ? v.ints() : new int[0];
    }

    public long[] getLongs(String key) {
        return entries.get(key) instanceof CgNbtValue v ? v.longs() : new long[0];
    }

    /** The compound under {@code key}, or an empty one that is not stored here. */
    public CgNbtCompound getCompound(String key) {
        return entries.get(key) instanceof CgNbtCompound c ? c : new CgNbtCompound();
    }

    /** The compound under {@code key}, made and stored if it is absent or another kind. */
    public CgNbtCompound getOrCreateCompound(String key) {
        if (entries.get(key) instanceof CgNbtCompound c) return c;
        CgNbtCompound made = new CgNbtCompound();
        entries.put(key, made);
        return made;
    }

    /** The list under {@code key}, or an empty one that is not stored here. */
    public CgNbtList getList(String key) {
        return entries.get(key) instanceof CgNbtList l ? l : new CgNbtList();
    }

    /** The list under {@code key}, made and stored if it is absent or another kind. */
    public CgNbtList getOrCreateList(String key) {
        if (entries.get(key) instanceof CgNbtList l) return l;
        CgNbtList made = new CgNbtList();
        entries.put(key, made);
        return made;
    }

    /** Throws unless {@code key} is present: for a reader that must not carry on without it. */
    public CgNbtTag require(String key) {
        CgNbtTag tag = entries.get(key);
        if (tag == null) throw new CgCodecException("Missing required tag '" + key + "' in " + this);
        return tag;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CgNbtCompound that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return 31 * Type.COMPOUND.id + entries.hashCode();
    }
}
