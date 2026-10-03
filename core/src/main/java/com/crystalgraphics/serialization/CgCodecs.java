package com.crystalgraphics.serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Built-in primitive {@link CgCodec}s and generic combinators — the reusable low-level vocabulary
 * hand-written, type-specific codecs (like a future UI-tree codec) are built from. */
public final class CgCodecs {

    public static final CgCodec<String> STRING = new CgCodec<String>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, String input) {
            return ops.createString(input);
        }

        @Override
        public <T> String decode(CgDynamicOps<T> ops, T input) {
            return ops.getStringValue(input);
        }
    };

    public static final CgCodec<Integer> INT = new CgCodec<Integer>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Integer input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Integer decode(CgDynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).intValue();
        }
    };

    public static final CgCodec<Float> FLOAT = new CgCodec<Float>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Float input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Float decode(CgDynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).floatValue();
        }
    };

    public static final CgCodec<Boolean> BOOL = new CgCodec<Boolean>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Boolean input) {
            return ops.createBoolean(input);
        }

        @Override
        public <T> Boolean decode(CgDynamicOps<T> ops, T input) {
            return ops.getBooleanValue(input);
        }
    };

    /** A {@code CgCodec<List<A>>} built from an element codec — encodes/decodes every element via
     * {@code elementCodec}, wrapping the result in the format's own list representation. */
    public static <A> CgCodec<List<A>> listOf(CgCodec<A> elementCodec) {
        return new CgCodec<List<A>>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, List<A> input) {
                List<T> encoded = new ArrayList<>(input.size());
                for (A a : input) encoded.add(elementCodec.encode(ops, a));
                return ops.createList(encoded);
            }

            @Override
            public <T> List<A> decode(CgDynamicOps<T> ops, T input) {
                List<T> raw = ops.getListValue(input);
                List<A> decoded = new ArrayList<>(raw.size());
                for (T t : raw) decoded.add(elementCodec.decode(ops, t));
                return decoded;
            }
        };
    }

    public static final CgCodec<Long> LONG = new CgCodec<Long>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Long input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Long decode(CgDynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).longValue();
        }
    };

    public static final CgCodec<Double> DOUBLE = new CgCodec<Double>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Double input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Double decode(CgDynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).doubleValue();
        }
    };

    /**
     * One codec in terms of another — {@code from} converts on decode, {@code to} on encode.
     *
     * <p>This is how every enum, wrapper and value type gets a codec without writing the
     * {@link CgDynamicOps} plumbing again.</p>
     */
    public static <A, B> CgCodec<B> xmap(CgCodec<A> base, Function<A, B> from, Function<B, A> to) {
        return new CgCodec<B>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, B input) {
                return base.encode(ops, to.apply(input));
            }

            @Override
            public <T> B decode(CgDynamicOps<T> ops, T input) {
                return from.apply(base.decode(ops, input));
            }
        };
    }

    /**
     * Any enum, by constant name.
     *
     * <p>Name rather than ordinal on purpose: an ordinal silently re-points at a different constant
     * the moment someone inserts one into the middle of the enum, and on a wire format that means a
     * client and server disagreeing about what {@code 3} meant with nothing to detect it.</p>
     */
    public static <E extends Enum<E>> CgCodec<E> enumOf(Class<E> type) {
        return xmap(STRING, name -> {
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException e) {
                throw new CgCodecException("No " + type.getSimpleName() + " constant named '" + name + "'", e);
            }
        }, Enum::name);
    }

    // ── Record building ─────────────────────────────────────────────────────
    //
    // A minimal field vocabulary. Not a RecordCodecBuilder — that needs arity-N generic plumbing this
    // codebase doesn't have and doesn't yet need. `MapCodecBuilder` below is the 80% case: build a
    // map field by field, read it back field by field, and let the caller assemble the object.

    /** Starts building a map-shaped encoding. Field order is insertion order — see
     * {@link MapCodecBuilder} for why that matters. */
    public static <T> MapCodecBuilder<T> map(CgDynamicOps<T> ops) {
        return new MapCodecBuilder<>(ops);
    }

    /** Reads a map-shaped encoding back. */
    public static <T> MapCodecReader<T> read(CgDynamicOps<T> ops, T input) {
        return new MapCodecReader<>(ops, input);
    }

    /**
     * Builds a map field by field.
     *
     * <p><b>Insertion-ordered, deliberately.</b> Descriptions are content-addressed — the client
     * caches them by a hash of their encoded bytes — so encoding the same tree twice has to produce
     * identical output. A {@code HashMap} anywhere in this path would make the hash vary per JVM run
     * and silently defeat the entire cache.</p>
     *
     * <p>Absent optionals are <b>omitted</b> rather than written as null, so the common node stays
     * small and, again, so the encoding is a function of the value alone.</p>
     */
    public static final class MapCodecBuilder<T> {
        private final CgDynamicOps<T> ops;
        private final Map<T, T> entries = new LinkedHashMap<>();

        private MapCodecBuilder(CgDynamicOps<T> ops) {
            this.ops = ops;
        }

        public <A> MapCodecBuilder<T> field(String name, CgCodec<A> codec, A value) {
            entries.put(ops.createString(name), codec.encode(ops, value));
            return this;
        }

        /** Writes nothing when {@code value} equals {@code omitWhen} — including when both are null. */
        public <A> MapCodecBuilder<T> optional(String name, CgCodec<A> codec, A value, A omitWhen) {
            if (Objects.equals(value, omitWhen)) return this;
            return field(name, codec, value);
        }

        /** Writes nothing when the list is empty. */
        public <A> MapCodecBuilder<T> optionalList(String name, CgCodec<A> elementCodec, List<A> value) {
            if (value == null || value.isEmpty()) return this;
            return field(name, listOf(elementCodec), value);
        }

        /** A pre-encoded value, for nesting one codec's output inside another. */
        public MapCodecBuilder<T> raw(String name, T encoded) {
            entries.put(ops.createString(name), encoded);
            return this;
        }

        public T build() {
            return ops.createMap(entries);
        }
    }

    /** The read side of {@link MapCodecBuilder}. Missing fields yield the caller's default. */
    public static final class MapCodecReader<T> {
        private final CgDynamicOps<T> ops;
        private final Map<String, T> byName = new LinkedHashMap<>();

        private MapCodecReader(CgDynamicOps<T> ops, T input) {
            this.ops = ops;
            for (Map.Entry<T, T> entry : ops.getMapValue(input).entrySet()) {
                byName.put(ops.getStringValue(entry.getKey()), entry.getValue());
            }
        }

        public boolean has(String name) {
            return byName.containsKey(name);
        }

        /** Throws {@link CgCodecException} naming the field if it's absent — a required field missing
         * is a protocol error, not something to paper over with a default. */
        public <A> A field(String name, CgCodec<A> codec) {
            T raw = byName.get(name);
            if (raw == null) throw new CgCodecException("Missing required field '" + name + "'");
            return codec.decode(ops, raw);
        }

        public <A> A optional(String name, CgCodec<A> codec, A fallback) {
            T raw = byName.get(name);
            return raw == null ? fallback : codec.decode(ops, raw);
        }

        public <A> List<A> optionalList(String name, CgCodec<A> elementCodec) {
            T raw = byName.get(name);
            return raw == null ? List.of() : listOf(elementCodec).decode(ops, raw);
        }

        /** The still-encoded value, for nesting. */
        public T raw(String name) {
            return byName.get(name);
        }
    }

    private CgCodecs() {
    }
}
