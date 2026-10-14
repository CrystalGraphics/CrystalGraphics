package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Character fresh = CgFormats.read(file, Character.CODEC);       // Character::new, then filled
 * CgFormats.read(file, Character.CODEC, live);               // live, its skills and its stats keep their identity
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

    private CgObjectCodec(@Nullable Supplier<A> make, String name, List<Field<A, ?>> fields, @Nullable String about) {
        this.make = make;
        this.name = name;
        this.fields = fields;
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
        return new CgObjectCodec<>(make, name, fields, text);
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
            Field<A, ?> f = byKey.get(ops.getStringValue(e.getKey()));
            if (f != null) f.read(ops, e.getValue(), target);
        }
        return target;
    }

    /** Declares an object codec's fields; {@link #build} once. */
    public static final class Builder<A> {
        @Nullable
        private final Supplier<A> make;
        private final String name;
        private final List<Field<A, ?>> fields = new ArrayList<>();
        /** The comment for the next field. */
        @Nullable
        private String pending;

        private Builder(@Nullable Supplier<A> make, String name) {
            this.make = make;
            this.name = name;
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
            return new CgObjectCodec<>(make, name, new ArrayList<>(fields), null);
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
