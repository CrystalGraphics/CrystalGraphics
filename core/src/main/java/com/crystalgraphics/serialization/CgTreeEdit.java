package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * An old save's tree as a migration step edits it, in whatever format it was read from: what a
 * {@link CgVersionedCodec}'s steps are handed. Paths are keys, dotted for a nested map.
 *
 * <pre>{@code
 * .from(1, tree -> tree.rename("Level", "level"))
 * .from(2, tree -> tree.move("skills", "abilities.list")
 *                      .map("level", v -> v.asInt() * 10)
 *                      .remove("legacyFlag")
 *                      .set("schema", "rpg"))
 * }</pre>
 *
 * <ul>
 *   <li>A step on a path that is not there does nothing, so one step serves files that never had the key.</li>
 *   <li>{@link #set} and {@link #map} take a {@code String}, a {@code Number}, a {@code Boolean} or a {@link Value}.</li>
 *   <li>A path through a value that is not a map is refused.</li>
 * </ul>
 */
public final class CgTreeEdit {

    /** One value of the tree, read as the type a step expects. */
    public static final class Value {
        private final CgDynamicOps<Object> ops;
        private final Object raw;

        Value(CgDynamicOps<Object> ops, Object raw) {
            this.ops = ops;
            this.raw = raw;
        }

        public String asString() {
            return ops.getStringValue(raw);
        }

        public int asInt() {
            return ops.getNumberValue(raw).intValue();
        }

        public long asLong() {
            return ops.getNumberValue(raw).longValue();
        }

        public double asDouble() {
            return ops.getNumberValue(raw).doubleValue();
        }

        public boolean asBool() {
            return ops.getBooleanValue(raw);
        }

        /** Decoded through {@code codec}: a whole old shape read the way its codec once did. */
        public <A> A as(CgCodec<A> codec) {
            return codec.decode(ops, raw);
        }
    }

    /** A map opened for editing; anything else in the tree is the format's own value, never edited in place. */
    private static final class Opened extends LinkedHashMap<String, Object> {
    }

    private final CgDynamicOps<Object> ops;
    private final Opened root;

    @SuppressWarnings("unchecked")
    <T> CgTreeEdit(CgDynamicOps<T> ops, T map) {
        this.ops = (CgDynamicOps<Object>) ops;
        this.root = open(map, "the saved value");
    }

    public boolean has(String path) {
        return parentOf(path, false) != null && parentOf(path, false).containsKey(last(path));
    }

    @Nullable
    public Value get(String path) {
        Map<String, Object> parent = parentOf(path, false);
        Object raw = parent == null ? null : parent.get(last(path));
        return raw == null ? null : new Value(ops, close(raw));
    }

    /** {@code from}'s value under {@code to}, dotted paths both; maps are made on the way to {@code to}. */
    public CgTreeEdit move(String from, String to) {
        Map<String, Object> parent = parentOf(from, false);
        if (parent == null || !parent.containsKey(last(from))) return this;
        Object value = parent.remove(last(from));
        parentOf(to, true).put(last(to), value);
        return this;
    }

    /** {@link #move} within one map, for the common case of a key renamed. */
    public CgTreeEdit rename(String from, String to) {
        return move(from, to);
    }

    public CgTreeEdit remove(String path) {
        Map<String, Object> parent = parentOf(path, false);
        if (parent != null) parent.remove(last(path));
        return this;
    }

    /** Sets {@code path}, making maps on the way. */
    public CgTreeEdit set(String path, Object value) {
        parentOf(path, true).put(last(path), create(value));
        return this;
    }

    /** Replaces {@code path}'s value with what {@code step} answers for it. */
    public CgTreeEdit map(String path, Function<Value, Object> step) {
        Value value = get(path);
        if (value != null) parentOf(path, false).put(last(path), create(step.apply(value)));
        return this;
    }

    /** The edited tree, in the format it came in. */
    Object build() {
        return close(root);
    }

    private Object create(Object value) {
        if (value instanceof Value) return ((Value) value).raw;
        if (value instanceof String) return ops.createString((String) value);
        if (value instanceof Boolean) return ops.createBoolean((Boolean) value);
        if (value instanceof Number) return ops.createNumber((Number) value);
        throw new IllegalArgumentException("a tree value is a String, a Number, a Boolean or a Value, not " + value);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private Map<String, Object> parentOf(String path, boolean make) {
        Map<String, Object> at = root;
        int start = 0;
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', start)) {
            String key = path.substring(start, dot);
            Object child = at.get(key);
            if (child == null) {
                if (!make) return null;
                child = new Opened();
                at.put(key, child);
            } else if (!(child instanceof Opened)) {
                child = open(child, path);
                at.put(key, child);
            }
            at = (Map<String, Object>) child;
            start = dot + 1;
        }
        return at;
    }

    private Opened open(Object raw, String path) {
        Opened out = new Opened();
        try {
            for (Map.Entry<Object, Object> e : ops.getMapValue(raw).entrySet()) out.put(ops.getStringValue(e.getKey()), e.getValue());
        } catch (CgCodecException notAMap) {
            throw new CgCodecException("'" + path + "' goes through a value that is not a map", notAMap);
        }
        return out;
    }

    private Object close(Object value) {
        if (!(value instanceof Opened)) return value;
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Opened) value).entrySet()) {
            out.put(ops.createString(e.getKey()), close(e.getValue()));
        }
        return ops.createMap(out);
    }

    private static String last(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
