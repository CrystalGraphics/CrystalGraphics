package com.crystalgraphics.serialization;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A stable content hash for an encoded value — the identity a client caches UI descriptions under.
 *
 * <h3>Why the encoded bytes aren't hashed directly</h3>
 * <p>Hashing a JSON string would tie the identity to formatting and to whichever {@link CgDynamicOps}
 * produced it, so the same tree encoded through {@code JsonOps} and {@link CgPlainOps} would hash
 * differently. Instead the value is walked and rewritten into one canonical byte form:</p>
 * <ul>
 *   <li>every piece is <b>type-tagged and length-prefixed</b>, so {@code ["ab","c"]} cannot collide
 *       with {@code ["a","bc"]};</li>
 *   <li><b>map keys are sorted</b>, so a hash never depends on iteration order — belt and braces
 *       alongside the codec's own insertion ordering;</li>
 *   <li>numbers are normalised to IEEE-754 doubles, so an {@code int} 3 and a {@code float} 3.0
 *       agree. They are the same value; a wire format that disagreed would re-send an identical
 *       description for no reason.</li>
 * </ul>
 *
 * <h3>SHA-256, not {@code hashCode}</h3>
 * <p>This is a <em>content-addressed</em> store: a collision does not mean a slow lookup, it means
 * serving the wrong UI. A 32-bit hash reaches a 50% collision chance at about 77,000 entries, which
 * is not a comfortable margin for something a player would experience as a GUI silently showing
 * someone else's screen.</p>
 */
public final class CgContentHash {

    private static final byte TAG_NULL = 'N';
    private static final byte TAG_STRING = 'S';
    private static final byte TAG_NUMBER = 'D';
    private static final byte TAG_BOOL = 'B';
    private static final byte TAG_LIST = 'L';
    private static final byte TAG_MAP = 'M';
    /** Raw bytes. Distinct from {@link #TAG_STRING} so a base64 string and the bytes it denotes do
     * not hash alike — they are different values and a content address must say so. */
    private static final byte TAG_BYTES = 'Y';

    private CgContentHash() {
    }

    /** Lowercase hex SHA-256 of {@code value}'s canonical form. */
    public static <T> String of(CgDynamicOps<T> ops, T value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalBytes(ops, value));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The canonical byte form itself, exposed for tests and for anything wanting to compare
     * two values without hashing. */
    public static <T> byte[] canonicalBytes(CgDynamicOps<T> ops, T value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        write(ops, value, out);
        return out.toByteArray();
    }

    private static <T> void write(CgDynamicOps<T> ops, T value, ByteArrayOutputStream out) {
        if (value == null) {
            out.write(TAG_NULL);
            return;
        }
        // Probed in a fixed order, most-specific first. CgDynamicOps has no type query, so this walks
        // the accessors and treats a CgCodecException as "not that shape" — the same duck-typing
        // CgPlainOps itself relies on.
        Map<T, T> map = tryMap(ops, value);
        if (map != null) {
            writeMap(ops, map, out);
            return;
        }
        List<T> list = tryList(ops, value);
        if (list != null) {
            out.write(TAG_LIST);
            writeVarInt(list.size(), out);
            for (T element : list) write(ops, element, out);
            return;
        }
        byte[] bytes = tryBytes(ops, value);
        if (bytes != null) {
            out.write(TAG_BYTES);
            writeBytes(bytes, out);
            return;
        }
        String string = tryString(ops, value);
        if (string != null) {
            out.write(TAG_STRING);
            writeBytes(string.getBytes(StandardCharsets.UTF_8), out);
            return;
        }
        Number number = tryNumber(ops, value);
        if (number != null) {
            out.write(TAG_NUMBER);
            writeLongBits(Double.doubleToLongBits(number.doubleValue()), out);
            return;
        }
        Boolean bool = tryBool(ops, value);
        if (bool != null) {
            out.write(TAG_BOOL);
            out.write(bool ? 1 : 0);
            return;
        }
        throw new CgCodecException("Value is not representable in canonical form: " + value);
    }

    /**
     * Bytes, or null when {@code value} is not bytes.
     *
     * <p><b>Only ever true for an ops with a NATIVE byte representation.</b> The default
     * {@link CgDynamicOps#getBytesValue} decodes base64 out of a string, so on a textual ops this would
     * report bytes for any string that happens to be valid base64 — {@code "abcd"} among them. That is why
     * the probe asks whether the ops <em>round-trips</em> the value rather than merely decoding it: a
     * native ops returns the same bytes it was given, a base64 default does not survive the comparison
     * against its own re-encoding.</p>
     */
    private static <T> byte[] tryBytes(CgDynamicOps<T> ops, T value) {
        byte[] decoded;
        try {
            decoded = ops.getBytesValue(value);
        } catch (RuntimeException e) {
            return null;
        }
        if (decoded == null) return null;
        // A textual ops answers this from a String; re-creating from the decoded bytes gives a String
        // again, never the original value. A native ops gives back something equal to what it holds.
        try {
            T recreated = ops.createBytes(decoded);
            if (recreated instanceof byte[] && value instanceof byte[]) return decoded;
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> void writeMap(CgDynamicOps<T> ops, Map<T, T> map, ByteArrayOutputStream out) {
        // Sorted by key, so the hash is a function of content alone. The codec already emits keys in
        // a fixed order; this makes the identity survive an ops that doesn't preserve it.
        List<Map.Entry<T, T>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> ops.getStringValue(a.getKey()).compareTo(ops.getStringValue(b.getKey())));

        out.write(TAG_MAP);
        writeVarInt(entries.size(), out);
        for (Map.Entry<T, T> entry : entries) {
            writeBytes(ops.getStringValue(entry.getKey()).getBytes(StandardCharsets.UTF_8), out);
            write(ops, entry.getValue(), out);
        }
    }

    private static void writeBytes(byte[] bytes, ByteArrayOutputStream out) {
        writeVarInt(bytes.length, out);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarInt(int value, ByteArrayOutputStream out) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static void writeLongBits(long bits, ByteArrayOutputStream out) {
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((bits >> shift) & 0xFF));
    }

    // ── Shape probes ────────────────────────────────────────────────────────

    private static <T> Map<T, T> tryMap(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getMapValue(value);
        } catch (RuntimeException notAMap) {
            return null;
        }
    }

    private static <T> List<T> tryList(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getListValue(value);
        } catch (RuntimeException notAList) {
            return null;
        }
    }

    private static <T> String tryString(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getStringValue(value);
        } catch (RuntimeException notAString) {
            return null;
        }
    }

    private static <T> Number tryNumber(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getNumberValue(value);
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static <T> Boolean tryBool(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getBooleanValue(value);
        } catch (RuntimeException notABool) {
            return null;
        }
    }
}
