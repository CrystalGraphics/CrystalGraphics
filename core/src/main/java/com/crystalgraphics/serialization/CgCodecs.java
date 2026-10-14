package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
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
     * through {@code elementCodec}'s own {@code decode}, extras dropped, new ones added (serde's {@code Vec}).
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
            public <T> List<A> decode(CgDynamicOps<T> ops, T input, @Nullable List<A> target) {
                if (target == null) return decode(ops, input);
                List<T> raw = ops.getListValue(input);
                int kept = Math.min(raw.size(), target.size());
                for (int i = 0; i < kept; i++) {
                    A current = target.get(i);
                    A next = elementCodec.decode(ops, raw.get(i), current);
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
     *   <li>JSON, TOML, YAML and NBT take only text keys. {@link #INT} and {@link #LONG} keys are written as their text
     *       in every format, and read back from text or a number; any other key codec must encode as a string there.</li>
     *   <li>Decoded into a map, it keeps that map: a key already there goes through its value's {@code decode}, a
     *       new one is added, and one the input lacks is removed.</li>
     * </ul>
     */
    public static <K, V> CgCodec<Map<K, V>> mapOf(CgCodec<K> keys, CgCodec<V> valueCodec) {
        CgCodec<K> keyCodec = asKey(keys);
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
            public <T> Map<K, V> decode(CgDynamicOps<T> ops, T input, @Nullable Map<K, V> target) {
                if (target == null) return decode(ops, input);
                Map<T, T> raw = ops.getMapValue(input);
                Set<K> seen = new HashSet<>(raw.size() * 2);
                for (Map.Entry<T, T> e : raw.entrySet()) {
                    K key = keyCodec.decode(ops, e.getKey());
                    seen.add(key);
                    V current = target.get(key);
                    V next = current == null ? valueCodec.decode(ops, e.getValue())
                            : valueCodec.decode(ops, e.getValue(), current);
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
            public <T> V decode(CgDynamicOps<T> ops, T input, @Nullable V target) {
                MapCodecReader<T> r = read(ops, input);
                K key = r.field(keyName, keyCodec);
                if (target == null || !Objects.equals(keyOf.apply(target), key)) return r.field("value", codecFor(key));
                T value = r.raw("value");
                if (value == null) throw new CgCodecException("Missing required field 'value'");
                return codecFor(key).decode(ops, value, target);
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

    /** {@link #INT} and {@link #LONG} as {@link #mapOf}'s keys: their text, which every format can key by. */
    @SuppressWarnings("unchecked")
    private static <K> CgCodec<K> asKey(CgCodec<K> codec) {
        if (codec == INT) return (CgCodec<K>) xmap(WHOLE_KEY, Long::intValue, Integer::longValue);
        if (codec == LONG) return (CgCodec<K>) WHOLE_KEY;
        return codec;
    }

    private static final CgCodec<Long> WHOLE_KEY = new CgCodec<Long>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Long input) {
            return ops.createString(Long.toString(input));
        }

        // A number too: a save from before keys were text, in a format that kept it a number.
        @Override
        public <T> Long decode(CgDynamicOps<T> ops, T input) {
            String text;
            try {
                text = ops.getStringValue(input);
            } catch (CgCodecException notText) {
                return ops.getNumberValue(input).longValue();
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new CgCodecException("'" + text + "' is not a whole-number key", e);
            }
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
     * A plain tree, carried whole: maps, lists, strings, numbers, booleans, {@code byte[]} and null, as
     * {@link CgPlainOps} holds them. For a value that is already another codec's output, picked by something the
     * carrier does not know, such as a state's synced values encoded by that state's own codec. Equal trees are equal
     * {@code Map}s and {@code List}s, so a networked field of it sends only when the tree changed.
     *
     * <pre>{@code
     * static final CgNetField<Object> VALUES = CgNetField.of("values", CgCodecs.PLAIN);
     *
     * obj.set(VALUES, state.codec().encode(CgPlainOps.INSTANCE, state));      // server
     * State back = state.codec().decode(CgPlainOps.INSTANCE, obj.get(VALUES)); // client
     * }</pre>
     *
     * <ul>
     *   <li>Writes through any format. Reads back through the plain ones (the wire, binary files, TOML, YAML) and
     *       JSON; NBT throws ({@link CgDynamicOps#toPlain}).</li>
     *   <li>Through a text format, bytes come back as their Base64 text, and JSON's numbers as {@code Integer},
     *       {@code Long} or {@code Double}.</li>
     * </ul>
     */
    public static final CgCodec<Object> PLAIN = new CgCodec<Object>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Object input) {
            return plainTo(ops, input);
        }

        @Override
        public <T> Object decode(CgDynamicOps<T> ops, T input) {
            return ops.toPlain(input);
        }
    };

    private static <T> T plainTo(CgDynamicOps<T> ops, @Nullable Object value) {
        if (value == null) return ops.empty();
        if (value instanceof String s) return ops.createString(s);
        if (value instanceof Boolean b) return ops.createBoolean(b);
        if (value instanceof Number n) return ops.createNumber(n);
        if (value instanceof byte[] bytes) return ops.createBytes(bytes);
        if (value instanceof List<?> list) {
            List<T> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(plainTo(ops, e));
            return ops.createList(out);
        }
        if (value instanceof Map<?, ?> map) {
            Map<T, T> out = new LinkedHashMap<>(map.size() * 2);
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(plainTo(ops, e.getKey()), plainTo(ops, e.getValue()));
            return ops.createMap(out);
        }
        throw new CgCodecException("not a plain tree: a " + value.getClass().getName());
    }

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

    // ── Records ─────────────────────────────────────────────────────────────

    /** A required key of a {@link #record}: missing on decode throws, naming it. */
    public static <R, F> CgRecordField<R, F> field(String key, CgCodec<F> codec, Function<? super R, ? extends F> get) {
        return new CgRecordField<>(key, codec, get, false, false, null);
    }

    /** An optional key of a {@link #record}: not written when equal to {@code fallback}, read as it when absent. */
    public static <R, F> CgRecordField<R, F> optionalField(String key, CgCodec<F> codec, Function<? super R, ? extends F> get,
                                                           @Nullable F fallback) {
        return new CgRecordField<>(key, codec, get, true, true, fallback);
    }

    /** A key of a {@link #record} always written, and read as {@code fallback} when absent. */
    public static <R, F> CgRecordField<R, F> fieldOr(String key, CgCodec<F> codec, Function<? super R, ? extends F> get,
                                                     F fallback) {
        return new CgRecordField<>(key, codec, get, false, true, fallback);
    }

    /**
     * Another record's keys written flat into this one's map, not nested under a key of their own: DataFixerUpper's
     * {@code MapCodec} put into a {@code RecordCodecBuilder}. For a group of keys several records share.
     *
     * <pre>{@code
     * record Group(String file, String group) {
     *     static final CgRecordCodec<Group> CODEC = CgCodecs.record(
     *             CgCodecs.field("File", CgCodecs.STRING, Group::file),
     *             CgCodecs.field("Group", CgCodecs.STRING, Group::group),
     *             Group::new);
     * }
     * record Known(String style, Group group) {
     *     static final CgCodec<Known> CODEC = CgCodecs.record(
     *             CgCodecs.field("Style", CgCodecs.STRING, Known::style),
     *             CgCodecs.inline(Group.CODEC, Known::group),     // {Style, File, Group}, all at one level
     *             Known::new);
     * }
     * }</pre>
     *
     * <ul>
     *   <li>A key both records declare throws when the outer record is made.</li>
     *   <li>The inlined record's key comments come along; its own comment, about the whole, does not.</li>
     *   <li>Its value may not be null: the inlined record's own optional keys say what may be missing.</li>
     * </ul>
     */
    public static <R, G> CgRecordField<R, G> inline(CgRecordCodec<G> keys, Function<? super R, ? extends G> get) {
        return CgRecordField.inline(keys, get);
    }

    /**
     * A key per constant of {@code type}, each through {@code value}: a map from the constants, written as one key
     * apiece. A key absent, or equal to {@code fallback}, reads as {@code fallback}; with a null fallback the map
     * simply lacks that constant.
     *
     * <pre>{@code
     * // {"left": [...], "right": [...]} for a Map<Side, List<Strand>>
     * static final CgRecordCodec<Map<Side, List<Strand>>> SIDES = CgCodecs.eachOf(Side.class, Side::key,
     *         CgCodecs.listOf(Strand.CODEC), null);
     *
     * // one bool per constant, flat beside a record's other keys
     * CgCodecs.inline(CgCodecs.eachOf(StrandValue.class, StrandValue::key, CgCodecs.BOOL, false), HairStrand::flags)
     * }</pre>
     *
     * Decodes as an {@code EnumMap}. A constant added later reads as {@code fallback} from older saves.
     */
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>, V> CgRecordCodec<Map<E, V>> eachOf(Class<E> type, Function<? super E, String> key,
                                                                       CgCodec<V> value, @Nullable V fallback) {
        E[] constants = type.getEnumConstants();
        CgRecordField<Map<E, V>, ?>[] fields = new CgRecordField[constants.length];
        for (int i = 0; i < constants.length; i++) {
            E constant = constants[i];
            fields[i] = CgCodecs.<Map<E, V>, V>optionalField(key.apply(constant), value,
                    m -> m.getOrDefault(constant, fallback), fallback);
        }
        return new CgRecordCodec<>(args -> {
            Map<E, V> out = new EnumMap<>(type);
            for (int i = 0; i < constants.length; i++) if (args[i] != null) out.put(constants[i], (V) args[i]);
            return out;
        }, fields);
    }

    /**
     * A {@link #record} key with {@code text} above it, where a format keeps comments (TOML, YAML); a line each, for a
     * {@code \n}. The comment comes first, the key after.
     *
     * <pre>{@code
     * CgCodecs.record(
     *         CgCodecs.comment("Master volume, 0 to 1.").field("volume", CgCodecs.FLOAT, Audio::volume),
     *         CgCodecs.field("subtitles", CgCodecs.BOOL, Audio::subtitles),
     *         Audio::new);
     * }</pre>
     */
    public static Commented comment(String text) {
        return new Commented(text);
    }

    /** {@link #comment}'s key: {@code field}, {@code optionalField} and {@code fieldOr}, each with the comment. */
    public static final class Commented {
        private final String text;

        private Commented(String text) {
            this.text = text;
        }

        public <R, F> CgRecordField<R, F> field(String key, CgCodec<F> codec, Function<? super R, ? extends F> get) {
            return CgCodecs.<R, F>field(key, codec, get).withComment(text);
        }

        public <R, F> CgRecordField<R, F> optionalField(String key, CgCodec<F> codec,
                                                        Function<? super R, ? extends F> get, @Nullable F fallback) {
            return CgCodecs.<R, F>optionalField(key, codec, get, fallback).withComment(text);
        }

        public <R, F> CgRecordField<R, F> fieldOr(String key, CgCodec<F> codec, Function<? super R, ? extends F> get,
                                                  F fallback) {
            return CgCodecs.<R, F>fieldOr(key, codec, get, fallback).withComment(text);
        }
    }

    /**
     * A record's codec from its keys and its constructor: see {@link CgRecordCodec}.
     *
     * <pre>{@code
     * public record Spell(String name, int cost) {
     *     public static final CgCodec<Spell> CODEC = CgCodecs.record(
     *             CgCodecs.field("name", CgCodecs.STRING, Spell::name),
     *             CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0),
     *             Spell::new);
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public static <R, A> CgRecordCodec<R> record(CgRecordField<R, A> a, Function<A, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0]), a);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                    BiFunction<A, B, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1]), a, b);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                       CgRecordField<R, C> c, CgRecordCodec.Of3<A, B, C, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2]), a, b, c);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                          CgRecordField<R, C> c, CgRecordField<R, D> d,
                                                          CgRecordCodec.Of4<A, B, C, D, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3]), a, b, c, d);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                             CgRecordField<R, C> c, CgRecordField<R, D> d,
                                                             CgRecordField<R, E> e,
                                                             CgRecordCodec.Of5<A, B, C, D, E, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4]), a, b, c, d, e);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                                CgRecordField<R, C> c, CgRecordField<R, D> d,
                                                                CgRecordField<R, E> e, CgRecordField<R, F> f,
                                                                CgRecordCodec.Of6<A, B, C, D, E, F, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5]),
                a, b, c, d, e, f);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                                   CgRecordField<R, C> c, CgRecordField<R, D> d,
                                                                   CgRecordField<R, E> e, CgRecordField<R, F> f,
                                                                   CgRecordField<R, G> g,
                                                                   CgRecordCodec.Of7<A, B, C, D, E, F, G, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5],
                (G) v[6]), a, b, c, d, e, f, g);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H> CgRecordCodec<R> record(CgRecordField<R, A> a, CgRecordField<R, B> b,
                                                                      CgRecordField<R, C> c, CgRecordField<R, D> d,
                                                                      CgRecordField<R, E> e, CgRecordField<R, F> f,
                                                                      CgRecordField<R, G> g, CgRecordField<R, H> h,
                                                                      CgRecordCodec.Of8<A, B, C, D, E, F, G, H, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5],
                (G) v[6], (H) v[7]), a, b, c, d, e, f, g, h);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i,
            CgRecordCodec.Of9<A, B, C, D, E, F, G, H, I, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8]),
                a, b, c, d, e, f, g, h, i);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j,
            CgRecordCodec.Of10<A, B, C, D, E, F, G, H, I, J, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9]),
                a, b, c, d, e, f, g, h, i, j);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k,
            CgRecordCodec.Of11<A, B, C, D, E, F, G, H, I, J, K, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10]),
                a, b, c, d, e, f, g, h, i, j, k);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k, CgRecordField<R, L> l,
            CgRecordCodec.Of12<A, B, C, D, E, F, G, H, I, J, K, L, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10], (L) v[11]),
                a, b, c, d, e, f, g, h, i, j, k, l);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k, CgRecordField<R, L> l,
            CgRecordField<R, M> m,
            CgRecordCodec.Of13<A, B, C, D, E, F, G, H, I, J, K, L, M, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10], (L) v[11], (M) v[12]),
                a, b, c, d, e, f, g, h, i, j, k, l, m);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k, CgRecordField<R, L> l,
            CgRecordField<R, M> m, CgRecordField<R, N> n,
            CgRecordCodec.Of14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10], (L) v[11], (M) v[12], (N) v[13]),
                a, b, c, d, e, f, g, h, i, j, k, l, m, n);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k, CgRecordField<R, L> l,
            CgRecordField<R, M> m, CgRecordField<R, N> n, CgRecordField<R, O> o,
            CgRecordCodec.Of15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10], (L) v[11], (M) v[12], (N) v[13],
                (O) v[14]),
                a, b, c, d, e, f, g, h, i, j, k, l, m, n, o);
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> CgRecordCodec<R> record(
            CgRecordField<R, A> a, CgRecordField<R, B> b, CgRecordField<R, C> c, CgRecordField<R, D> d,
            CgRecordField<R, E> e, CgRecordField<R, F> f, CgRecordField<R, G> g, CgRecordField<R, H> h,
            CgRecordField<R, I> i, CgRecordField<R, J> j, CgRecordField<R, K> k, CgRecordField<R, L> l,
            CgRecordField<R, M> m, CgRecordField<R, N> n, CgRecordField<R, O> o, CgRecordField<R, P> p,
            CgRecordCodec.Of16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, R> make) {
        return new CgRecordCodec<>(v -> make.apply((A) v[0], (B) v[1], (C) v[2], (D) v[3], (E) v[4], (F) v[5], (G) v[6],
                (H) v[7], (I) v[8], (J) v[9], (K) v[10], (L) v[11], (M) v[12], (N) v[13],
                (O) v[14], (P) v[15]),
                a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p);
    }

    // ── Versions ────────────────────────────────────────────────────────────

    /**
     * {@code codec}'s saves at version {@code current}, under {@code "_v"}, each older one migrated by its steps: see
     * {@link CgVersionedCodec}.
     *
     * <pre>{@code
     * static final CgCodec<Character> SAVED = CgCodecs.versioned(2, Character.CODEC)
     *         .from(1, tree -> tree.rename("Level", "level"));
     * }</pre>
     */
    public static <A> CgVersionedCodec<A> versioned(int current, CgCodec<A> codec) {
        return versioned("_v", current, codec);
    }

    /** As {@link #versioned(int, CgCodec)}, the version under {@code key}. */
    public static <A> CgVersionedCodec<A> versioned(String key, int current, CgCodec<A> codec) {
        if (key.indexOf('.') >= 0) throw new IllegalArgumentException("a version key is not a path: " + key);
        return new CgVersionedCodec<>(key, current, codec, new TreeMap<>());
    }

    // ── Comments ────────────────────────────────────────────────────────────

    /** What {@link CgCodec#comment} makes: {@code codec}, its maps described by {@code text}. */
    static <A> CgCodec<A> commented(CgCodec<A> codec, String text) {
        Map<String, String> about = Collections.singletonMap(null, text);
        return new CgCodec<A>() {
            @Override
            public <T> T encode(CgDynamicOps<T> ops, A input) {
                return ops.withComments(codec.encode(ops, input), about);
            }

            @Override
            public <T> A decode(CgDynamicOps<T> ops, T input) {
                return codec.decode(ops, input);
            }

            @Override
            public <T> A decode(CgDynamicOps<T> ops, T input, @Nullable A target) {
                return codec.decode(ops, input, target);
            }
        };
    }

    // ── Maps by hand ────────────────────────────────────────────────────────

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
     *
     * <p>A comment goes just before the key it describes, and a TOML or YAML file writes it above that key:</p>
     *
     * <pre>{@code
     * CgCodecs.map(ops)
     *         .comment("Master volume, 0 to 1.").field("volume", CgCodecs.FLOAT, volume)
     *         .comment("Gone with its key when null.").optional("theme", CgCodecs.STRING, theme, null)
     *         .build();
     * }</pre>
     *
     * A comment about the whole value goes on the codec: {@link CgCodec#comment}.
     */
    public static final class MapCodecBuilder<T> {
        private final CgDynamicOps<T> ops;
        private final Map<T, T> entries = new LinkedHashMap<>();
        @Nullable
        private Map<String, String> comments;
        /** The comment for the next key. */
        @Nullable
        private String pending;

        private MapCodecBuilder(CgDynamicOps<T> ops) {
            this.ops = ops;
        }

        public <A> MapCodecBuilder<T> field(String name, CgCodec<A> codec, A value) {
            return raw(name, codec.encode(ops, value));
        }

        /** Writes nothing when {@code value} equals {@code omitWhen} — including when both are null. */
        public <A> MapCodecBuilder<T> optional(String name, CgCodec<A> codec, A value, A omitWhen) {
            if (Objects.equals(value, omitWhen)) return omitted();
            return field(name, codec, value);
        }

        /** Writes nothing when the list is empty. */
        public <A> MapCodecBuilder<T> optionalList(String name, CgCodec<A> elementCodec, List<A> value) {
            if (value == null || value.isEmpty()) return omitted();
            return field(name, listOf(elementCodec), value);
        }

        /** A pre-encoded value, for nesting one codec's output inside another. */
        public MapCodecBuilder<T> raw(String name, T encoded) {
            entries.put(ops.createString(name), encoded);
            if (pending != null) {
                if (comments == null) comments = new LinkedHashMap<>();
                comments.put(name, pending);
                pending = null;
            }
            return this;
        }

        private MapCodecBuilder<T> omitted() {
            pending = null;
            return this;
        }

        /**
         * {@code text} above the key written next, a line each for a {@code \n}. Null writes none; so does a key the
         * next call omits.
         */
        public MapCodecBuilder<T> comment(@Nullable String text) {
            pending = text;
            return this;
        }

        /** @throws IllegalStateException where a comment has no key after it */
        public T build() {
            if (pending != null) throw new IllegalStateException("comment() goes before the key it describes");
            T made = ops.createMap(entries);
            return comments == null ? made : ops.withComments(made, comments);
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
