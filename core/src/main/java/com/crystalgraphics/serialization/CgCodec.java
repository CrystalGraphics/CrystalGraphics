package com.crystalgraphics.serialization;

import javax.annotation.Nullable;

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
 *   <li>Comments are constants of the codec, and only a TOML or YAML file writes them: the wire never carries one.</li>
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
     * This codec, with {@code text} about the value as a whole: a TOML or YAML file's header when the value is the
     * file, above its {@code [section]} when it is nested. A line each, for a {@code \n}. Its keys' comments go before
     * each key: {@code builder.comment(text).field(...)}.
     *
     * <pre>{@code
     * static final CgObjectCodec<ModConfig> CODEC = CgCodecs.object(ModConfig::new)
     *         .comment("Master volume, 0 to 1.")
     *         .field("volume", CgCodecs.FLOAT, c -> c.volume, (c, v) -> c.volume = v)
     *         .build()
     *         .comment("mymod settings. Edit while the game is closed.");
     * }</pre>
     *
     * A value that is not a map has nowhere to put it, and the comment is dropped.
     */
    default CgCodec<A> comment(String text) {
        return CgCodecs.commented(this, text);
    }
}
