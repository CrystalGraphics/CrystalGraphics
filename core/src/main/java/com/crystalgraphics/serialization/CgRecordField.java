package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * One key of a {@link CgCodecs#record} codec: its name, its codec and how to read it from the record. Made by
 * {@link CgCodecs#field}, {@link CgCodecs#optionalField} and {@link CgCodecs#fieldOr}.
 *
 * <pre>{@code
 * CgCodecs.field("name", CgCodecs.STRING, Spell::name)                  // required: missing throws, naming it
 * CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0)          // 0 is not written, and absent reads 0
 * CgCodecs.fieldOr("exposed", CgCodecs.BOOL, Prop::exposed, true)      // always written; absent reads true
 * CgCodecs.comment("Ki it takes.").field("cost", CgCodecs.INT, Spell::cost)   // written above cost in TOML, YAML
 * }</pre>
 *
 * @param <R> the record
 * @param <F> the field's value
 */
public final class CgRecordField<R, F> {

    final String key;
    final CgCodec<F> codec;
    final Function<? super R, ? extends F> get;
    /** Not written when equal to {@link #fallback}. */
    final boolean omits;
    /** Absent reads {@link #fallback} rather than throwing. */
    final boolean defaults;
    @Nullable
    final F fallback;
    @Nullable
    final String comment;
    /** A stored value that fails to decode reads {@link #fallback}, with a warning. */
    final boolean lenient;

    CgRecordField(String key, CgCodec<F> codec, Function<? super R, ? extends F> get, boolean omits, boolean defaults,
                  @Nullable F fallback) {
        this(key, codec, get, omits, defaults, fallback, null, false);
    }

    private CgRecordField(String key, CgCodec<F> codec, Function<? super R, ? extends F> get, boolean omits,
                          boolean defaults, @Nullable F fallback, @Nullable String comment, boolean lenient) {
        this.key = key;
        this.codec = codec;
        this.get = get;
        this.omits = omits;
        this.defaults = defaults;
        this.fallback = fallback;
        this.comment = comment;
        this.lenient = lenient;
    }

    /** This key with {@code text} above it: what {@code CgCodecs.comment(text).field(...)} makes. */
    CgRecordField<R, F> withComment(String text) {
        return new CgRecordField<>(key, codec, get, omits, defaults, fallback, text, lenient);
    }

    /**
     * This key, read as its fallback when its stored value fails to decode, with a warning naming the key, rather than
     * failing the whole record: a save written before the value's shape changed still loads. DataFixerUpper's
     * {@code lenientOptionalFieldOf}.
     *
     * <pre>{@code
     * CgCodecs.optionalField("hair", Hair.CODEC, Look::hair, Hair.DEFAULT).lenient()
     * }</pre>
     *
     * Only a key with a fallback ({@code optionalField}, {@code fieldOr}); a required {@code field} throws here.
     */
    public CgRecordField<R, F> lenient() {
        if (!defaults) throw new IllegalStateException("'" + key + "' has no fallback to read; declare it optionalField or fieldOr");
        return new CgRecordField<>(key, codec, get, omits, defaults, fallback, comment, true);
    }

    public String key() {
        return key;
    }
}
