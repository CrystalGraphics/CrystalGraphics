package com.crystalgraphics.serialization;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * {@link CgCodecs#mapOf}'s codec, which says its key and value codecs: for code that compiles a codec into something
 * else, as a networked type sends a map's keys and loads each entry when asked.
 *
 * <pre>{@code
 * CgMapCodec<String, Integer> scores = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT);
 * scores.keys();      // CgCodecs.STRING
 * scores.values();    // CgCodecs.INT
 * }</pre>
 */
public final class CgMapCodec<K, V> implements CgCodec<Map<K, V>> {

    private final CgCodec<K> keys;
    private final CgCodec<V> values;
    private final CgCodec<Map<K, V>> map;

    CgMapCodec(CgCodec<K> keys, CgCodec<V> values, CgCodec<Map<K, V>> map) {
        this.keys = keys;
        this.values = values;
        this.map = map;
    }

    /** The codec of each key, as given to {@code mapOf}. */
    public CgCodec<K> keys() {
        return keys;
    }

    /** The codec of each value. */
    public CgCodec<V> values() {
        return values;
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, Map<K, V> input) {
        return map.encode(ops, input);
    }

    @Override
    public <T> Map<K, V> decode(CgDynamicOps<T> ops, T input) {
        return map.decode(ops, input);
    }

    @Override
    public <T> Map<K, V> decode(CgDynamicOps<T> ops, T input, @Nullable Map<K, V> target) {
        return map.decode(ops, input, target);
    }
}
