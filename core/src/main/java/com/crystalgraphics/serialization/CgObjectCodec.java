package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A codec for a mutable object, each field declared once: encoding, decoding and decoding into a live object all read
 * the same declaration, so their keys cannot drift apart. Made by {@link CgCodecs#object}.
 *
 * <pre>{@code
 * public final class Character {
 *     String name = "";
 *     int level = 1;
 *     final List<String> skills = new ArrayList<>();
 *     final Stats stats = new Stats();
 *
 *     public static final CgObjectCodec<Character> CODEC = CgCodecs.object(Character::new)
 *             .field("name", CgCodecs.STRING, c -> c.name, (c, v) -> c.name = v)
 *             .optional("level", CgCodecs.INT, c -> c.level, (c, v) -> c.level = v, 1)   // 1 is not written
 *             .into("skills", CgCodecs.listOf(CgCodecs.STRING), c -> c.skills)        // refilled in place
 *             .into("stats", Stats.CODEC, c -> c.stats)                              // updated in place
 *             .build();
 * }
 *
 * Character fresh = CgFiles.read(file, Character.CODEC);       // Character::new, then filled
 * CgFiles.read(file, Character.CODEC, live);               // live, its skills and its stats keep their identity
 * }</pre>
 *
 * An object the caller makes itself (from a registry, a constructor with arguments) takes the factory-less form:
 *
 * <pre>{@code
 * static final CgObjectCodec<Machine> STATE = CgCodecs.object(Machine.class)
 *         .field("fuel", CgCodecs.INT, m -> m.fuel, (m, v) -> m.fuel = v)
 *         .build();
 *
 * Machine machine = new Machine(level, pos);
 * STATE.decode(ops, saved, machine);
 * }</pre>
 *
 * <ul>
 *   <li><b>A key the input lacks leaves its field as it is</b>: the constructor's default in {@link #decode}, the live
 *       value in {@link #decode}. A partial input is a patch. A field holding null is not written.</li>
 *   <li>{@link Builder#field} decodes a new value and stores it through its setter; what its getter answers is only
 *       ever encoded, so a getter may return a snapshot. {@link Builder#into} is the in-place form: it has no setter,
 *       its codec must update the held value, and one that cannot (a string, a record) throws, naming the key.</li>
 *   <li>The factory-less form's {@link #decode} throws: it has nothing to decode into.</li>
 *   <li>A failure part-way leaves the target with the fields read before it.</li>
 * </ul>
 */
public final class CgObjectCodec<A> implements CgCodec<A> {

    @Nullable
    private final Supplier<A> make;
    private final String name;
    private final List<Field<A, ?>> fields;
    private final Map<String, Field<A, ?>> byKey;
    /** The object's own comment by null and each field's by its key: one map, handed to every encode. */
    private final Map<String, String> comments;
    /** Each key's rules beyond its codec, by key. */
    private final Map<String, Policy> policies;

    /**
     * A key's rules beyond its codec: {@code serverOnly} (kept on the server, which a networked model never sends),
     * {@code readOnly} (written, never read back), and the range a decoded number is clamped to.
     */
    public record Policy(boolean serverOnly, boolean readOnly, double min, double max) {
        static final Policy NONE = new Policy(false, false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private CgObjectCodec(@Nullable Supplier<A> make, String name, List<Field<A, ?>> fields, @Nullable String about,
                          Map<String, Policy> policies) {
        this.make = make;
        this.name = name;
        this.fields = fields;
        this.policies = policies;
        this.byKey = new HashMap<>(fields.size() * 2);
        Map<String, String> notes = new LinkedHashMap<>();
        if (about != null) notes.put(null, about);
        for (Field<A, ?> f : fields) {
            if (byKey.put(f.key, f) != null) throw new IllegalArgumentException(name + " declares '" + f.key + "' twice");
            if (f.comment != null) notes.put(f.key, f.comment);
        }
        this.comments = Collections.unmodifiableMap(notes);
    }

    static <A> Builder<A> builder(@Nullable Supplier<A> make, String name) {
        return new Builder<>(make, name);
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, A input) {
        Map<T, T> entries = new LinkedHashMap<>(fields.size() * 2);
        for (Field<A, ?> f : fields) f.write(ops, input, entries);
        T made = ops.createMap(entries);
        return comments.isEmpty() ? made : ops.withComments(made, comments);
    }

    /** This codec, with {@code text} about the object as a whole: see {@link CgCodec#comment}. */
    @Override
    public CgObjectCodec<A> comment(String text) {
        return new CgObjectCodec<>(make, name, fields, text, policies);
    }

    /** Its keys, in the order they are written. {@code CgCodecs.keysOf} answers it for a test to pin. */
    public List<String> keys() {
        List<String> out = new ArrayList<>(fields.size());
        for (Field<A, ?> f : fields) out.add(f.key);
        return out;
    }

    /** {@code key}'s rules beyond its codec; a key with none answers no rule at all. */
    public Policy policy(String key) {
        return policies.getOrDefault(key, Policy.NONE);
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input) {
        if (make == null) {
            throw new CgCodecException(name + " has no factory: decode into an object with decode");
        }
        return decode(ops, input, make.get());
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input, @Nullable A target) {
        if (target == null) return decode(ops, input);
        for (Map.Entry<T, T> e : ops.getMapValue(input).entrySet()) {
            String key = ops.getStringValue(e.getKey());
            Field<A, ?> f = byKey.get(key);
            if (f == null) continue;
            Policy policy = policies.get(key);
            if (policy == null) f.read(ops, e.getValue(), target);
            else if (!policy.readOnly) f.read(ops, e.getValue(), target, policy);
        }
        return target;
    }

    /** Declares an object codec's fields; {@link #build} once. */
    public static final class Builder<A> {
        @Nullable
        private final Supplier<A> make;
        private final String name;
        private final List<Field<A, ?>> fields = new ArrayList<>();
        private final Map<String, Policy> policies = new LinkedHashMap<>();
        /** What {@link CgCodecs#fieldsOf} derives at {@link #build}, or null for a builder written out. */
        @Nullable
        private CgFieldScan<A> scan;
        /** The comment for the next field. */
        @Nullable
        private String pending;

        private Builder(@Nullable Supplier<A> make, String name) {
            this.make = make;
            this.name = name;
        }

        Builder(CgFieldScan<A> scan, @Nullable Supplier<A> make) {
            this(make, scan.type.getSimpleName() + "'s codec");
            this.scan = scan;
        }

        // ── A derived codec's own calls ─────────────────────────────────────────────────────────

        /**
         * The derived field {@code field} under {@code key} instead of its name: how a rename keeps old saves reading.
         *
         * <pre>{@code
         * CgCodecs.fieldsOf(Npc.class).key("health", "hp").build();     // the field was renamed from hp
         * }</pre>
         */
        public Builder<A> key(String field, String key) {
            derived("key").keys.put(field, key);
            return this;
        }

        /** Leaves {@code fields} out: not saved, not sent, not read. */
        public Builder<A> skip(String... fields) {
            Collections.addAll(derived("skip").skipped, fields);
            return this;
        }

        /** {@code codec} for every derived field of {@code type}: a type with no codec of its own. */
        public <T> Builder<A> with(Class<T> type, CgCodec<T> codec) {
            derived("with").with.put(type, codec);
            return this;
        }

        private CgFieldScan<A> derived(String call) {
            if (scan == null) throw new IllegalStateException(call + "(...) is for a codec from CgCodecs.fieldsOf");
            return scan;
        }

        // ── Any key's rules ─────────────────────────────────────────────────────────────────────

        /** {@code key} is kept on the server: a networked model never sends it. Saved as any other. */
        public Builder<A> serverOnly(String key) {
            Policy p = policies.getOrDefault(key, Policy.NONE);
            policies.put(key, new Policy(true, p.readOnly, p.min, p.max));
            return this;
        }

        /** {@code key} is written and never read back: a value a reader may see but not set. */
        public Builder<A> readOnly(String key) {
            Policy p = policies.getOrDefault(key, Policy.NONE);
            policies.put(key, new Policy(p.serverOnly, true, p.min, p.max));
            return this;
        }

        /** A number decoded for {@code key} is clamped to {@code min..max}: what a client may set, kept fair. */
        public Builder<A> range(String key, double min, double max) {
            if (!(min <= max)) throw new IllegalArgumentException("range " + min + ".." + max);
            Policy p = policies.getOrDefault(key, Policy.NONE);
            policies.put(key, new Policy(p.serverOnly, p.readOnly, min, max));
            return this;
        }

        /**
         * Every field of a superclass's codec, first, under the same keys: layered data written flat.
         *
         * <pre>{@code
         * static final CgObjectCodec<DownState> CODEC = CgCodecs.object(DownState::new)
         *         .extend(HurtState.CODEC)
         *         .field("bounce", CgCodecs.FLOAT, s -> s.bounce, (s, v) -> s.bounce = v)
         *         .build();
         * }</pre>
         */
        @SuppressWarnings("unchecked")
        public Builder<A> extend(CgObjectCodec<? super A> parent) {
            if (pending != null) throw new IllegalStateException("comment() goes before a field, not before extend()");
            for (Field<?, ?> f : parent.fields) fields.add((Field<A, ?>) f);
            return this;
        }

        /**
         * A field read with {@code get} and stored with {@code set}, always as a newly decoded value.
         *
         * <pre>{@code
         * .field("traits", CgCodecs.listOf(Trait.CODEC), Handler::snapshot, Handler::restore)   // a snapshot is fine
         * }</pre>
         */
        public <F> Builder<A> field(String key, CgCodec<F> codec, Function<? super A, ? extends F> get,
                                    BiConsumer<? super A, ? super F> set) {
            return add(new Field<>(key, codec, get, set, false, null));
        }

        /** As {@link #field}, writing nothing when the value equals {@code omitWhen}. */
        public <F> Builder<A> optional(String key, CgCodec<F> codec, Function<? super A, ? extends F> get,
                                       BiConsumer<? super A, ? super F> set, @Nullable F omitWhen) {
            return add(new Field<>(key, codec, get, set, true, omitWhen));
        }

        /** A member the object holds for good (a final list, an owned object), always updated in place. */
        public <F> Builder<A> into(String key, CgCodec<F> codec, Function<? super A, ? extends F> get) {
            return add(new Field<>(key, codec, get, null, false, null));
        }

        private <F> Builder<A> add(Field<A, F> field) {
            fields.add(pending == null ? field : field.withComment(pending));
            pending = null;
            return this;
        }

        /**
         * {@code text} above the field declared next, where a format keeps comments (TOML, YAML); a line each, for a
         * {@code \n}. The object's own comment goes on the built codec: {@code build().comment(text)}.
         *
         * <pre>{@code
         * CgCodecs.object(ModConfig::new)
         *         .comment("Master volume, 0 to 1.")
         *         .field("volume", CgCodecs.FLOAT, s -> s.volume, (s, v) -> s.volume = v)
         *         .build()
         *         .comment("mymod's settings. Edit them while the game is closed.");   // the file's header
         * }</pre>
         */
        public Builder<A> comment(String text) {
            pending = text;
            return this;
        }

        /** @throws IllegalStateException where a comment has no field after it */
        public CgObjectCodec<A> build() {
            if (pending != null) throw new IllegalStateException("comment() goes before the field it describes");
            List<Field<A, ?>> all = new ArrayList<>(fields);
            if (scan != null) {
                // DERIVED FIRST, in key order; a field written out replaces the derived one of its key
                Set<String> written = new HashSet<>();
                for (Field<A, ?> f : fields) written.add(f.key);
                Builder<A> derived = new Builder<>(make, name);
                scan.deriveInto(derived, written);
                all = new ArrayList<>(derived.fields);
                all.addAll(fields);
            }
            return new CgObjectCodec<>(make, name, all, null, new LinkedHashMap<>(policies));
        }
    }

    private static final class Field<A, F> {
        final String key;
        final CgCodec<F> codec;
        final Function<? super A, ? extends F> get;
        @Nullable
        final BiConsumer<? super A, ? super F> set;
        final boolean optional;
        @Nullable
        final F omitWhen;
        @Nullable
        final String comment;

        Field(String key, CgCodec<F> codec, Function<? super A, ? extends F> get,
              @Nullable BiConsumer<? super A, ? super F> set, boolean optional, @Nullable F omitWhen) {
            this(key, codec, get, set, optional, omitWhen, null);
        }

        private Field(String key, CgCodec<F> codec, Function<? super A, ? extends F> get,
                      @Nullable BiConsumer<? super A, ? super F> set, boolean optional, @Nullable F omitWhen,
                      @Nullable String comment) {
            this.key = key;
            this.codec = codec;
            this.get = get;
            this.set = set;
            this.optional = optional;
            this.omitWhen = omitWhen;
            this.comment = comment;
        }

        Field<A, F> withComment(String text) {
            return new Field<>(key, codec, get, set, optional, omitWhen, text);
        }

        <T> void write(CgDynamicOps<T> ops, A owner, Map<T, T> entries) {
            F value = get.apply(owner);
            if (value == null || optional && Objects.equals(value, omitWhen)) return;
            entries.put(ops.createString(key), codec.encode(ops, value));
        }

        /** {@link #read}, a decoded number clamped to the policy's range. */
        @SuppressWarnings("unchecked")
        <T> void read(CgDynamicOps<T> ops, T raw, A owner, Policy policy) {
            if (set == null || policy.min == Double.NEGATIVE_INFINITY && policy.max == Double.POSITIVE_INFINITY) {
                read(ops, raw, owner);
                return;
            }
            F value = codec.decode(ops, raw);
            if (value instanceof Number n) value = (F) clamp(n, policy.min, policy.max);
            set.accept(owner, value);
        }

        private static Number clamp(Number n, double min, double max) {
            double d = Math.max(min, Math.min(max, n.doubleValue()));
            if (n instanceof Integer) return (int) d;
            if (n instanceof Long) return (long) d;
            if (n instanceof Float) return (float) d;
            if (n instanceof Short) return (short) d;
            if (n instanceof Byte) return (byte) d;
            return d;
        }

        <T> void read(CgDynamicOps<T> ops, T raw, A owner) {
            if (set != null) {
                // Fresh, never into what get answers: a getter may hand out a snapshot or a copy.
                set.accept(owner, codec.decode(ops, raw));
                return;
            }
            F current = get.apply(owner);
            if (current == null) throw new CgCodecException("'" + key + "' is held by the object, and it holds null");
            if (codec.decode(ops, raw, current) != current) {
                throw new CgCodecException("'" + key + "' cannot be updated in place: declare it with field(...)");
            }
        }
    }
}
