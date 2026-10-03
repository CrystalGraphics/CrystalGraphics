package com.crystalgraphics.serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link CgDynamicOps} over plain JDK collections — {@code LinkedHashMap}, {@code ArrayList} and
 * boxed primitives. No Gson, no NBT, no dependency at all.
 *
 * <p>Two uses, both real:</p>
 * <ul>
 *   <li><b>Proof of format-agnosticism.</b> Running the same codec against this and against
 *       {@code JsonOps} demonstrates the codec doesn't secretly depend on JSON specifics. That was
 *       previously a private class inside one test; it belongs here now that more than one test
 *       wants it.</li>
 *   <li><b>An in-process wire format.</b> A transport that "sends" between two sessions in the same
 *       JVM still has to encode and decode, or it proves nothing about what a real one would do.</li>
 * </ul>
 *
 * <p>{@code LinkedHashMap} rather than {@code HashMap} is deliberate: descriptions are
 * content-addressed, so encoding order has to be stable.</p>
 */
public final class CgPlainOps implements CgDynamicOps<Object> {

    public static final CgPlainOps INSTANCE = new CgPlainOps();

    private CgPlainOps() {
    }

    @Override
    public Object empty() {
        return null;
    }

    @Override
    public Object createString(String value) {
        return value;
    }

    @Override
    public Object createNumber(Number value) {
        return value;
    }

    @Override
    public Object createBoolean(boolean value) {
        return value;
    }

    @Override
    public Object createList(List<Object> values) {
        return new ArrayList<>(values);
    }

    @Override
    public Object createMap(Map<Object, Object> entries) {
        return new LinkedHashMap<>(entries);
    }

    @Override
    public String getStringValue(Object value) {
        if (!(value instanceof String s)) throw new CgCodecException("Not a string: " + value);
        return s;
    }

    /**
     * Bytes, held as themselves.
     *
     * <p>Overridden because this ops is the in-process transport, and Base64 round-tripping a file
     * through a {@code String} inside one JVM is pure waste — it is a cost textual formats have no way
     * to avoid and this one has no reason to pay.</p>
     *
     * <p><b>The array is copied in and out.</b> Every other value this ops handles is immutable, and a
     * shared {@code byte[]} would let a caller mutate a tree it had already encoded — which for a
     * content-addressed value means the hash and the bytes silently disagree.</p>
     */
    @Override
    public Object createBytes(byte[] value) {
        return value.clone();
    }

    @Override
    public byte[] getBytesValue(Object value) {
        if (!(value instanceof byte[] bytes)) throw new CgCodecException("Not bytes: " + value);
        return bytes.clone();
    }

    @Override
    public Number getNumberValue(Object value) {
        if (!(value instanceof Number n)) throw new CgCodecException("Not a number: " + value);
        return n;
    }

    @Override
    public boolean getBooleanValue(Object value) {
        if (!(value instanceof Boolean b)) throw new CgCodecException("Not a boolean: " + value);
        return b;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> getListValue(Object value) {
        if (!(value instanceof List)) throw new CgCodecException("Not a list: " + value);
        return (List<Object>) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Object, Object> getMapValue(Object value) {
        if (!(value instanceof Map)) throw new CgCodecException("Not a map: " + value);
        return (Map<Object, Object>) value;
    }
}
