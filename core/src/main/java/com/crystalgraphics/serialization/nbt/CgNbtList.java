package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A list tag: an ordered run of tags that are all one kind, as Minecraft's lists are on every version.
 *
 * <pre>{@code
 * CgNbtList names = CgNbtList.of("goku", "vegeta");           // strings
 * names.add("gohan");
 * for (CgNbtTag tag : names) { ... }                        // or names.getString(i)
 *
 * CgNbtList spells = CgNbtList.of(Spell.CODEC, mySpells);      // a list of compounds from a codec
 * List<Spell> back = spells.toList(Spell.CODEC);
 * }</pre>
 *
 * <ul>
 *   <li>Adding a tag of another kind than the first throws {@link CgCodecException}: a list of compounds cannot take a string.</li>
 *   <li>An index out of range reads as the absent value ({@code ""}, 0, an empty compound), never throws; {@link #get} answers null.</li>
 * </ul>
 */
public final class CgNbtList extends CgNbtTag implements Iterable<CgNbtTag> {

    private final List<CgNbtTag> items = new ArrayList<>();

    public CgNbtList() {
    }

    /** A list of {@code tags}; they must all be one kind. */
    public static CgNbtList of(CgNbtTag... tags) {
        CgNbtList list = new CgNbtList();
        for (CgNbtTag tag : tags) list.add(tag);
        return list;
    }

    public static CgNbtList of(String... values) {
        CgNbtList list = new CgNbtList();
        for (String value : values) list.add(value);
        return list;
    }

    public static CgNbtList of(int... values) {
        CgNbtList list = new CgNbtList();
        for (int value : values) list.add(value);
        return list;
    }

    /** {@code values} each encoded by {@code codec}. */
    public static <V> CgNbtList of(CgCodec<V> codec, Collection<? extends V> values) {
        CgNbtList list = new CgNbtList();
        for (V value : values) list.add(CgNbt.encode(codec, value));
        return list;
    }

    @Override
    public Type type() {
        return Type.LIST;
    }

    /** The kind of every element, or null while the list is empty. */
    public Type elementType() {
        return items.isEmpty() ? null : items.get(0).type();
    }

    @Override
    public CgNbtList copy() {
        CgNbtList out = new CgNbtList();
        for (CgNbtTag item : items) out.items.add(item.copy());
        return out;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The tag at {@code index}, or null out of range. */
    public CgNbtTag get(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** Adds {@code tag}; throws if it is not the kind the list already holds. */
    public CgNbtList add(CgNbtTag tag) {
        if (!items.isEmpty() && items.get(0).type() != tag.type()) {
            throw new CgCodecException("a list holds one kind of tag: " + items.get(0).type() + ", not " + tag.type());
        }
        items.add(tag);
        return this;
    }

    public CgNbtList add(String value) {
        return add(CgNbtValue.ofString(value));
    }

    public CgNbtList add(int value) {
        return add(CgNbtValue.ofInt(value));
    }

    public CgNbtList add(long value) {
        return add(CgNbtValue.ofLong(value));
    }

    public CgNbtList add(float value) {
        return add(CgNbtValue.ofFloat(value));
    }

    public CgNbtList add(double value) {
        return add(CgNbtValue.ofDouble(value));
    }

    /** Replaces the tag at {@code index}; a one-element list may change its kind. */
    public CgNbtList set(int index, CgNbtTag tag) {
        if (items.size() > 1 && items.get(0).type() != tag.type()) {
            throw new CgCodecException("a list holds one kind of tag: " + items.get(0).type() + ", not " + tag.type());
        }
        items.set(index, tag);
        return this;
    }

    public CgNbtTag remove(int index) {
        return items.remove(index);
    }

    public CgNbtList clear() {
        items.clear();
        return this;
    }

    public String getString(int index) {
        CgNbtTag tag = get(index);
        return tag instanceof CgNbtValue v && v.isString() ? v.asString() : "";
    }

    public int getInt(int index) {
        return get(index) instanceof CgNbtValue v ? v.asInt() : 0;
    }

    public long getLong(int index) {
        return get(index) instanceof CgNbtValue v ? v.asLong() : 0L;
    }

    public double getDouble(int index) {
        return get(index) instanceof CgNbtValue v ? v.asDouble() : 0d;
    }

    /** The compound at {@code index}, or an empty one that is not in the list. */
    public CgNbtCompound getCompound(int index) {
        return get(index) instanceof CgNbtCompound c ? c : new CgNbtCompound();
    }

    /** The list at {@code index}, or an empty one that is not in this list. */
    public CgNbtList getList(int index) {
        return get(index) instanceof CgNbtList l ? l : new CgNbtList();
    }

    /** Every element read through {@code codec}. */
    public <V> List<V> toList(CgCodec<V> codec) {
        List<V> out = new ArrayList<>(items.size());
        for (CgNbtTag item : items) out.add(CgNbt.decode(codec, item));
        return out;
    }

    /** The elements without a view or a copy, for the library's own loops. */
    List<CgNbtTag> raw() {
        return items;
    }

    /** The elements, in order, not changeable through this view. */
    public List<CgNbtTag> view() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public Iterator<CgNbtTag> iterator() {
        return view().iterator();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CgNbtList that && items.equals(that.items);
    }

    @Override
    public int hashCode() {
        return 31 * Type.LIST.id + items.hashCode();
    }
}
