package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * A list, each element through {@code elementCodec}. Decoded into a list, it refills that list: element {@code i}
     * through {@code elementCodec}'s own {@code decodeInto}, extras dropped, new ones added (serde's {@code Vec}).
     */
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

            @Override
            public <T> List<A> decodeInto(CgDynamicOps<T> ops, T input, @Nullable List<A> target) {
                if (target == null) return decode(ops, input);
                List<T> raw = ops.getListValue(input);
                int kept = Math.min(raw.size(), target.size());
                for (int i = 0; i < kept; i++) {
                    A current = target.get(i);
                    A next = elementCodec.decodeInto(ops, raw.get(i), current);
                    if (next != current) target.set(i, next);
                }
                if (target.size() > raw.size()) target.subList(raw.size(), target.size()).clear();
                for (int i = kept; i < raw.size(); i++) target.add(elementCodec.decode(ops, raw.get(i)));
                return target;
            }
        };
    }

    /**
     * A map, each key and value through its own codec, read back in the order it was written.
     *
     * <pre>{@code
     * static final CgCodec<Map<String, Integer>> LEVELS = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT);
     * static final CgCodec<Map<Slot, Picks>> PICKS = CgCodecs.mapOf(Slot.CODEC, Picks.CODEC);
     * }</pre>
     *
     * <ul>
     *   <li>Encoding follows the map's own order: hand it a {@code LinkedHashMap} or a {@code TreeMap} where the
     *       encoded bytes must not vary (a content hash, a save diffed by hand).</li>
     *   <li>NBT and JSON take only text keys: key by a codec that encodes as a string there.</li>
     *   <li>Decoded into a map, it keeps that map: a key already there goes through its value's {@code decodeInto}, a
     *       new one is added, and one the input lacks is removed.</li>
     * </ul>
     */
    public static <K, V> CgCodec<Map<K, V>> mapOf(CgCodec<K> keyCodec, CgCodec<V> valueCodec) {
        return new CgCodec<Map<K, V>>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, Map<K, V> input) {
                Map<T, T> encoded = new LinkedHashMap<>(input.size() * 2);
                for (Map.Entry<K, V> e : input.entrySet()) {
                    encoded.put(keyCodec.encode(ops, e.getKey()), valueCodec.encode(ops, e.getValue()));
                }
                return ops.createMap(encoded);
            }

            @Override
            public <T> Map<K, V> decode(CgDynamicOps<T> ops, T input) {
                Map<T, T> raw = ops.getMapValue(input);
                Map<K, V> decoded = new LinkedHashMap<>(raw.size() * 2);
                for (Map.Entry<T, T> e : raw.entrySet()) {
                    decoded.put(keyCodec.decode(ops, e.getKey()), valueCodec.decode(ops, e.getValue()));
                }
                return decoded;
            }

            @Override
            public <T> Map<K, V> decodeInto(CgDynamicOps<T> ops, T input, @Nullable Map<K, V> target) {
                if (target == null) return decode(ops, input);
                Map<T, T> raw = ops.getMapValue(input);
                Set<K> seen = new HashSet<>(raw.size() * 2);
                for (Map.Entry<T, T> e : raw.entrySet()) {
                    K key = keyCodec.decode(ops, e.getKey());
                    seen.add(key);
                    V current = target.get(key);
                    V next = current == null ? valueCodec.decode(ops, e.getValue())
                            : valueCodec.decodeInto(ops, e.getValue(), current);
                    if (next != current) target.put(key, next);
                }
                target.keySet().retainAll(seen);
                return target;
            }
        };
    }

    /**
     * A value whose codec its key picks: each state's data by the state's id, each registry entry's properties by its
     * kind. The shape of DataFixerUpper's {@code Codec.dispatch} (MIT): encoded as the key under {@code keyName} and
     * the value's own encoding under {@code "value"}.
     *
     * <pre>{@code
     * static final CgCodec<StateData> STATES = CgCodecs.dispatch("state", CgCodecs.STRING,
     *         StateData::stateId,                       // the key a value carries
     *         id -> States.byId(id).dataCodec());        // the codec a key picks; null for none
     * }</pre>
     *
     * <ul>
     *   <li>A key {@code codecOf} answers null for fails to encode or decode, naming the key.</li>
     *   <li>The value always sits under {@code "value"}, never inlined beside the key: with no map-only codecs to tell
     *       shapes apart, inlining would make a value whose own field is called {@code "value"} ambiguous.</li>
     *   <li>Decoded into a value of the same key, the key's codec updates it in place; another key makes a new one.</li>
     * </ul>
     */
    public static <K, V> CgCodec<V> dispatch(String keyName, CgCodec<K> keyCodec, Function<? super V, ? extends K> keyOf,
                                             Function<? super K, ? extends CgCodec<? extends V>> codecOf) {
        return new CgCodec<V>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, V input) {
                K key = keyOf.apply(input);
                return map(ops).field(keyName, keyCodec, key).field("value", codecFor(key), input).build();
            }

            @Override
            public <T> V decode(CgDynamicOps<T> ops, T input) {
                MapCodecReader<T> r = read(ops, input);
                return r.field("value", codecFor(r.field(keyName, keyCodec)));
            }

            @Override
            public <T> V decodeInto(CgDynamicOps<T> ops, T input, @Nullable V target) {
                MapCodecReader<T> r = read(ops, input);
                K key = r.field(keyName, keyCodec);
                if (target == null || !Objects.equals(keyOf.apply(target), key)) return r.field("value", codecFor(key));
                T value = r.raw("value");
                if (value == null) throw new CgCodecException("Missing required field 'value'");
                return codecFor(key).decodeInto(ops, value, target);
            }

            @SuppressWarnings("unchecked")
            private CgCodec<V> codecFor(K key) {
                CgCodec<? extends V> codec = codecOf.apply(key);
                if (codec == null) throw new CgCodecException("no codec for " + keyName + " " + key);
                return (CgCodec<V>) codec;
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

    /** Raw bytes: one tag and a length on the wire, base64 in text formats. */
    public static final CgCodec<byte[]> BYTES = new CgCodec<byte[]>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, byte[] input) {
            return ops.createBytes(input);
        }

        @Override
        public <T> byte[] decode(CgDynamicOps<T> ops, T input) {
            return ops.getBytesValue(input);
        }
    };

    /** A UUID as its two longs. */
    public static final CgCodec<UUID> UUID = new CgCodec<UUID>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, UUID input) {
            return ops.createList(Arrays.asList(ops.createNumber(input.getMostSignificantBits()),
                    ops.createNumber(input.getLeastSignificantBits())));
        }

        @Override
        public <T> UUID decode(CgDynamicOps<T> ops, T input) {
            List<T> halves = ops.getListValue(input);
            if (halves.size() != 2) throw new CgCodecException("a UUID is two longs, not " + halves.size() + " values");
            return new UUID(ops.getNumberValue(halves.get(0)).longValue(), ops.getNumberValue(halves.get(1)).longValue());
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

    /**
     * A mutable object, each field declared once, decoded anew through {@code make} or into a live object: see
     * {@link CgObjectCodec}.
     *
     * <pre>{@code
     * static final CgObjectCodec<Settings> CODEC = CgCodecs.object(Settings::new)
     *         .field("volume", CgCodecs.FLOAT, s -> s.volume, (s, v) -> s.volume = v)
     *         .build();
     * }</pre>
     */
    public static <A> CgObjectCodec.Builder<A> object(Supplier<A> make) {
        return CgObjectCodec.builder(make, "an object codec");
    }

    /** An object codec with no factory: the caller makes the object and decodes into it. */
    public static <A> CgObjectCodec.Builder<A> object(Class<A> type) {
        return CgObjectCodec.builder(null, type.getSimpleName() + "'s codec");
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
