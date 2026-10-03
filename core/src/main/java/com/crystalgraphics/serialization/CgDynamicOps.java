package com.crystalgraphics.serialization;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Abstracts "how do I build/read a string, number, boolean, list, or map in wire format {@code T}"
 * — the other half of the {@link CgCodec}/{@code CgDynamicOps} split that makes {@link CgCodec}
 * definitions format-agnostic. A {@link CgCodec} never touches a concrete tree type directly; it only
 * ever calls through a {@code CgDynamicOps<T>}, so the same {@link CgCodec} works against JSON, NBT,
 * XML, or any other format just by supplying a different {@code CgDynamicOps<T>} implementation.
 *
 * <p>{@code JsonOps} (backed by Gson's {@code JsonElement}) is the one concrete implementation
 * shipped in {@code core/}. A future NBT-backed {@code CgDynamicOps<CompoundTag>} would live in a
 * loader module instead (real NBT types aren't importable from {@code core/} — the loader-blind
 * import guard blocks {@code net.minecraft.*} generally, which covers {@code net.minecraft.nbt}
 * too) — it would reuse every {@link CgCodec} defined here unchanged, since {@link CgCodec}s carry no
 * format-specific code at all.</p>
 *
 * @param <T> the native value type of this format (e.g. {@code JsonElement} for JSON)
 */
public interface CgDynamicOps<T> {

    /** The format's representation of "nothing"/absent (e.g. {@code JsonNull} for JSON). */
    T empty();

    T createString(String value);

    T createNumber(Number value);

    T createBoolean(boolean value);

    T createList(List<T> values);

    /** Both keys and values are {@code T} — a format's own map keys are themselves just format
     * values (e.g. JSON object keys are strings, which are already representable as {@code T} via
     * {@link #createString}). */
    T createMap(Map<T, T> entries);

    /**
     * A block of raw bytes.
     *
     * <p><b>Defaulted, not abstract</b>, so adding it broke no existing implementation — and the default
     * is correct rather than a stub: Base64 through {@link #createString} is exactly how a textual format
     * represents bytes, and it is what {@code JsonOps} would have had to do anyway, since JSON has no byte
     * type at all.</p>
     *
     * <p>A format with a native representation — NBT's {@code ByteArrayTag}, or an in-process ops that can
     * simply hold the array — overrides both this and {@link #getBytesValue} and pays nothing. That is the
     * whole reason this is a pair of methods rather than a convention of "base64 it yourself at the call
     * site": the cost becomes the format's business instead of every caller's.</p>
     *
     * <p>Added for the remote workspace, where file contents cross the wire and Base64's ~33% is charged
     * on every asset in a project.</p>
     */
    default T createBytes(byte[] value) {
        return createString(Base64.getEncoder().encodeToString(value));
    }

    /**
     * Reads a block of raw bytes.
     *
     * <p>Symmetric with {@link #createBytes}, including the default: it decodes Base64 out of a string.
     * Throws {@link CgCodecException} if {@code value} is not bytes in this format.</p>
     */
    default byte[] getBytesValue(T value) {
        try {
            return Base64.getDecoder().decode(getStringValue(value));
        } catch (IllegalArgumentException e) {
            throw new CgCodecException("Not a base64 byte block: " + value, e);
        }
    }

    /** Throws {@link CgCodecException} if {@code value} isn't a string in this format. */
    String getStringValue(T value);

    /** Throws {@link CgCodecException} if {@code value} isn't a number in this format. */
    Number getNumberValue(T value);

    /** Throws {@link CgCodecException} if {@code value} isn't a boolean in this format. */
    boolean getBooleanValue(T value);

    /** Throws {@link CgCodecException} if {@code value} isn't a list in this format. */
    List<T> getListValue(T value);

    /** Throws {@link CgCodecException} if {@code value} isn't a map in this format. */
    Map<T, T> getMapValue(T value);
}
