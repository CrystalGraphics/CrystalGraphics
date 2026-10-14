package com.crystalgraphics.serialization;

import javax.annotation.Nullable;

/**
 * How a value of type {@code A} is written and read, in any format: the wire, CrystalGraphics' binary files, NBT, JSON.
 * A codec names a value's shape (its keys, each one's codec), and a {@link CgDynamicOps} supplies the format. The shape
 * of Mojang's DataFixerUpper {@code Codec}, without the library.
 *
 * <pre>{@code
 * record Ping(int seq, String from) {
 *     static final CgCodec<Ping> CODEC = new CgCodec<Ping>() {
 *         @Override public <T> T encode(CgDynamicOps<T> ops, Ping p) {
 *             return CgCodecs.map(ops).field("s", CgCodecs.INT, p.seq()).field("f", CgCodecs.STRING, p.from()).build();
 *         }
 *         @Override public <T> Ping decode(CgDynamicOps<T> ops, T in) {
 *             CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
 *             return new Ping(r.field("s", CgCodecs.INT), r.field("f", CgCodecs.STRING));
 *         }
 *     };
 * }
 * }</pre>
 *
 * A mutable object declares its fields once with {@link CgCodecs#object}, and can then be decoded into as well as
 * made:
 *
 * <pre>{@code
 * Settings fresh = CgFormats.read(file, Settings.CODEC);      // a new one
 * CgFormats.readInto(file, Settings.CODEC, live);             // live itself, refreshed; whoever holds it sees the change
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
     * {@code input} decoded into {@code target} where this codec can update one in place, answering what now holds the
     * data: {@code target} itself, or a new value where it cannot (a string, a record, an enum), which the caller stores
     * in place of the old one. A null target is a plain {@link #decode}.
     *
     * <pre>{@code
     * List<String> names = holder.names;
     * CgCodecs.listOf(CgCodecs.STRING).decodeInto(ops, in, names);    // names refilled; holder.names is still names
     * holder.title = CgCodecs.STRING.decodeInto(ops, in, holder.title); // a string cannot change: the new one is stored
     * }</pre>
     *
     * <ul>
     *   <li>In place where it says so: {@link CgCodecs#listOf}, {@link CgCodecs#mapOf}, {@link CgCodecs#dispatch} for the
     *       same key, and {@link CgCodecs#object}. Everything else decodes anew, the default here.</li>
     *   <li>A failure part-way can leave {@code target} half-updated.</li>
     *   <li>A collection target must be mutable.</li>
     * </ul>
     */
    default <T> A decodeInto(CgDynamicOps<T> ops, T input, @Nullable A target) {
        return decode(ops, input);
    }
}
