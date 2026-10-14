package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * One key of a {@link CgCodecs#record} codec: its name, its codec and how to read it from the record. Made by
 * {@link CgCodecs#field} and {@link CgCodecs#optionalField}.
 *
 * <pre>{@code
 * CgCodecs.field("name", CgCodecs.STRING, Spell::name)                  // required: missing throws, naming it
 * CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0)          // 0 is not written, and absent reads 0
 * }</pre>
 *
 * @param <R> the record
 * @param <F> the field's value
 */
public final class CgRecordField<R, F> {

    final String key;
    final CgCodec<F> codec;
    final Function<? super R, ? extends F> get;
    final boolean optional;
    @Nullable
    final F fallback;

    CgRecordField(String key, CgCodec<F> codec, Function<? super R, ? extends F> get, boolean optional,
                  @Nullable F fallback) {
        this.key = key;
        this.codec = codec;
        this.get = get;
        this.optional = optional;
        this.fallback = fallback;
    }

    public String key() {
        return key;
    }
}
