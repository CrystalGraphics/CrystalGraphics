package com.crystalgraphics.serialization;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A widget's own serializable state, as a small string-keyed bag.
 *
 * <p>Exists so {@code UIElement.writeState}/{@code readState} implementations never see a
 * {@link CgDynamicOps}. A widget author writes {@code out.putBool("checked", checked)}, and stays
 * unaware of whether the result becomes JSON, NBT or bytes — which is the same separation
 * {@link CgCodec} gives, one level up.</p>
 *
 * <p><b>Insertion-ordered.</b> Descriptions are content-addressed, so encoding the same widget twice
 * must produce identical bytes; a {@code HashMap} here would make the hash vary per JVM run.</p>
 *
 * <p>Reads take a default rather than throwing. A widget gaining a new state key must still be able
 * to read a description written before that key existed — the alternative is that every added field
 * is a breaking protocol change.</p>
 */
public final class CgStateMap<T> {

    private final CgDynamicOps<T> ops;
    private final Map<String, T> entries;

    /** An empty map, for writing. */
    public CgStateMap(CgDynamicOps<T> ops) {
        this.ops = ops;
        this.entries = new LinkedHashMap<>();
    }

    /** Wraps an already-encoded map, for reading. */
    public CgStateMap(CgDynamicOps<T> ops, T encoded) {
        this.ops = ops;
        this.entries = new LinkedHashMap<>();
        for (Map.Entry<T, T> entry : ops.getMapValue(encoded).entrySet()) {
            entries.put(ops.getStringValue(entry.getKey()), entry.getValue());
        }
    }

    // ── Write ───────────────────────────────────────────────────────────────

    public CgStateMap<T> putString(String key, String value) {
        entries.put(key, ops.createString(value == null ? "" : value));
        return this;
    }

    public CgStateMap<T> putInt(String key, int value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public CgStateMap<T> putFloat(String key, float value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public CgStateMap<T> putDouble(String key, double value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public CgStateMap<T> putBool(String key, boolean value) {
        entries.put(key, ops.createBoolean(value));
        return this;
    }

    public CgStateMap<T> putEnum(String key, Enum<?> value) {
        if (value == null) return this;
        entries.put(key, ops.createString(value.name()));
        return this;
    }

    /** Writes only when {@code value} differs from {@code omitWhen} — keeps a default-valued widget
     * from carrying its own defaults over the wire. */
    public CgStateMap<T> putStringIfNot(String key, String value, String omitWhen) {
        return java.util.Objects.equals(value, omitWhen) ? this : putString(key, value);
    }

    public CgStateMap<T> putBoolIfNot(String key, boolean value, boolean omitWhen) {
        return value == omitWhen ? this : putBool(key, value);
    }

    /**
     * Stores an already-encoded value untouched — another codec's whole output, nested under a key.
     *
     * <p>The escape hatch for composition, and the only one: a session record carries a dock layout that
     * {@code DockLayoutCodec} produced, and re-describing that tree here would be a second encoder for the
     * same thing. Everything else should use a typed put, because a raw value is opaque to this map and
     * cannot be read back as anything but itself.</p>
     */
    public CgStateMap<T> putRaw(String key, T value) {
        entries.put(key, value);
        return this;
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    /** The encoded value under {@code key}, untouched — the inverse of {@link #putRaw}. */
    @javax.annotation.Nullable
    public T getRaw(String key) {
        return entries.get(key);
    }

    public String getString(String key, String fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getStringValue(raw);
    }

    public int getInt(String key, int fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).intValue();
    }

    public float getFloat(String key, float fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).floatValue();
    }

    public double getDouble(String key, double fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).doubleValue();
    }

    public boolean getBool(String key, boolean fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getBooleanValue(raw);
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
        T raw = entries.get(key);
        if (raw == null) return fallback;
        String name = ops.getStringValue(raw);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new CgCodecException("No " + type.getSimpleName() + " constant named '" + name
                    + "' for state key '" + key + "'", e);
        }
    }

    /**
     * Raw bytes — file content, and anything else that is not text.
     *
     * <p>Goes through {@link CgDynamicOps#createBytes}, so a format with a native byte type pays nothing
     * and a textual one base64s. Added with the remote workspace; before it, nothing on the wire was
     * binary.</p>
     */
    public CgStateMap<T> putBytes(String key, byte[] value) {
        entries.put(key, ops.createBytes(value == null ? new byte[0] : value));
        return this;
    }

    /**
     * A list of sub-maps — a directory listing, a set of results.
     *
     * <p>The one shape this needed beyond scalars. Each element is written by {@code element}, which gets
     * a fresh {@link CgStateMap} to fill, so a caller never touches {@link CgDynamicOps} directly.</p>
     */
    public <E> CgStateMap<T> putList(String key, java.util.List<E> values,
                                   java.util.function.BiConsumer<CgStateMap<T>, E> element) {
        java.util.List<T> encoded = new java.util.ArrayList<>(values.size());
        for (E value : values) {
            CgStateMap<T> entry = new CgStateMap<>(ops);
            element.accept(entry, value);
            encoded.add(entry.encode());
        }
        entries.put(key, ops.createList(encoded));
        return this;
    }

    public byte[] getBytes(String key) {
        T value = entries.get(key);
        return value == null ? new byte[0] : ops.getBytesValue(value);
    }

    /** Reads back what {@link #putList} wrote. Absent or empty both give an empty list. */
    public <E> java.util.List<E> getList(String key, java.util.function.Function<CgStateMap<T>, E> element) {
        T value = entries.get(key);
        if (value == null) return java.util.List.of();
        java.util.List<E> out = new java.util.ArrayList<>();
        for (T encoded : ops.getListValue(value)) {
            out.add(element.apply(new CgStateMap<>(ops, encoded)));
        }
        return out;
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public CgDynamicOps<T> ops() {
        return ops;
    }

    public T encode() {
        Map<T, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries.entrySet()) {
            out.put(ops.createString(entry.getKey()), entry.getValue());
        }
        return ops.createMap(out);
    }
}
