package com.crystalgraphics.serialization;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;

/**
 * How a value of type {@code A} is written and read, in any format: the wire, CrystalGraphics' binary files, NBT, JSON.
 * A codec names a value's shape (its keys, each one's codec), and a {@link CgDynamicOps} supplies the format. The shape
 * of Mojang's DataFixerUpper {@code Codec}, without the library.
 *
 * <pre>{@code
 * record Ping(int seq, String from) {
 *     static final CgCodec<Ping> CODEC = CgCodecs.record(
 *             CgCodecs.field("s", CgCodecs.INT, Ping::seq),
 *             CgCodecs.field("f", CgCodecs.STRING, Ping::from),
 *             Ping::new);
 * }
 * }</pre>
 *
 * A shape a record cannot state (a key written only sometimes) implements {@link #encode} and {@link #decode} by hand,
 * over {@code CgCodecs.map(ops)} and {@code CgCodecs.read(ops, in)}.
 *
 * A mutable object declares its fields once with {@link CgCodecs#object}, and can then be decoded into as well as
 * made:
 *
 * <pre>{@code
 * Settings fresh = CgFormats.read(file, Settings.CODEC);      // a new one
 * CgFormats.read(file, Settings.CODEC, live);                // live itself, refreshed; whoever holds it sees it
 * }</pre>
 *
 * <ul>
 *   <li>{@link #decode} throws {@link CgCodecException} on input it cannot read; it never answers null for a failure.</li>
 *   <li>Encode and decode must use the same keys: a key read that was never written throws on the reading side.</li>
 * </ul>
 *
 * @param <A> the value type
 */
public interface CgCodec<A> {

    /** {@code input} in the format {@code ops} builds. */
    <T> T encode(CgDynamicOps<T> ops, A input);

    /** A new value out of {@code input}. */
    <T> A decode(CgDynamicOps<T> ops, T input);

    /**
     * Decodes into {@code target}, answering what holds the data: {@code target} where this codec updates in place
     * ({@code listOf}, {@code mapOf}, {@code dispatch}, {@code object}), a new value otherwise.
     *
     * <pre>{@code
     * CgCodecs.listOf(CgCodecs.STRING).decode(ops, in, holder.names);   // the same list, refilled
     * holder.title = CgCodecs.STRING.decode(ops, in, holder.title);     // a string is replaced
     * }</pre>
     *
     * A failure part-way can leave {@code target} half-updated. A null target decodes anew.
     */
    default <T> A decode(CgDynamicOps<T> ops, T input, @Nullable A target) {
        return decode(ops, input);
    }

    /**
     * What a text format that keeps comments ({@code CgFormats.TOML}, {@code YAML}) writes above each key, by dotted
     * path: {@code "volume"}, {@code "audio.volume"}. None unless the codec declares some, as
     * {@code object(...).comment(text)} and {@code field(...).comment(text)} do. {@code ""} is a TOML file's header,
     * above its first key.
     */
    default Map<String, String> comments() {
        return Collections.emptyMap();
    }
}
