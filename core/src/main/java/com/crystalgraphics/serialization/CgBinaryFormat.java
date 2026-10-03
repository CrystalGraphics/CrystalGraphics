package com.crystalgraphics.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire encoding — a {@link CgPlainOps} tree to bytes and back.
 *
 * <p><b>Deliberately not a {@link CgDynamicOps}.</b> A binary {@code CgDynamicOps} was the obvious shape and
 * is the wrong one: {@code CgDynamicOps} <em>builds a tree</em>, and a tree of {@code byte[]} composes by
 * concatenating its children, so every nesting level recopies everything below it. {@code CgPlainOps}
 * already produces exactly the tree we want to send, describes itself as "an in-process wire format",
 * and orders its maps deliberately because "descriptions are content-addressed, so encoding order has to
 * be stable". So this is an <em>encoding of that tree</em> rather than a third way of building one, and
 * there is no second ops to keep semantically in step with the two that exist.</p>
 *
 * <p>The practical consequence for Phase 4: what crosses {@code CgInMemoryTransport} today and what will
 * cross a real connection are the same tree, so the transport swap does not change what is being sent.</p>
 *
 * <h3>Why not JSON</h3>
 *
 * <p>{@code JsonOps} would work and stays the readable path for captures and debugging. It is a poor wire
 * format here for one measured reason: a client may send at most <b>32,766 bytes per packet on 1.7.10 and
 * 32,767 on 1.20.1</b> — a signed short in the vanilla packet format, in both eras — and that is the
 * direction carrying file saves. Text encoding spends the budget that matters most.</p>
 *
 * <h3>Number width is part of the value</h3>
 *
 * <p>Every numeric box gets its own tag, and {@link #decode} restores the same box type it was given.
 * This is not tidiness: {@code CgPlainOps} holds {@code Object}, so a codec that reads a field back and
 * compares or casts it sees the runtime class. Collapsing every integer to {@code Long} on the way out —
 * which is what a JSON round trip does — turns "the same tree" into a claim that is true of the values
 * and false of the types, and it fails at the reader rather than here.</p>
 *
 * <p>Lengths and counts are unsigned varints, and signed integers are zig-zag encoded, so small values
 * (which is nearly all of them: element counts, string lengths, network ids) cost one byte.</p>
 */
public final class CgBinaryFormat {

    // ── Tags ────────────────────────────────────────────────────────────────
    // Explicit values rather than an enum ordinal: these are ON THE WIRE, so reordering the list must not
    // be able to change them silently. A new tag appends; nothing is ever renumbered.
    private static final int TAG_NULL   = 0x00;
    private static final int TAG_FALSE  = 0x01;
    private static final int TAG_TRUE   = 0x02;
    private static final int TAG_BYTE   = 0x03;
    private static final int TAG_SHORT  = 0x04;
    private static final int TAG_INT    = 0x05;
    private static final int TAG_LONG   = 0x06;
    private static final int TAG_FLOAT  = 0x07;
    private static final int TAG_DOUBLE = 0x08;
    private static final int TAG_STRING = 0x09;
    private static final int TAG_BYTES  = 0x0A;
    private static final int TAG_LIST   = 0x0B;
    private static final int TAG_MAP    = 0x0C;

    /**
     * How deep a decoded tree may nest.
     *
     * <p>A bound rather than a preference: {@link #decode} recurses, and the bytes come off a network.
     * Without this, {@code 0x0B 0x01 0x0B 0x01 …} — a list holding a list holding a list — is a few
     * hundred bytes that overflows the stack of whatever thread drains the connection. The limit is far
     * above any real description; a tree that trips it is malformed or hostile, and either way the answer
     * is to refuse the packet rather than to take the process down with it.</p>
     */
    private static final int MAX_DEPTH = 512;

    private CgBinaryFormat() {
    }

    // ── Encode ──────────────────────────────────────────────────────────────

    /** Encodes a {@link CgPlainOps} tree. Throws {@link CgCodecException} on anything it cannot represent. */
    public static byte[] encode(Object tree) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, tree);
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not do I/O. If this ever fires, the cause is not I/O.
            throw new CgCodecException("encoding to memory failed", impossible);
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(TAG_NULL);
        } else if (value instanceof Boolean) {
            out.writeByte(((Boolean) value) ? TAG_TRUE : TAG_FALSE);
        } else if (value instanceof Byte) {
            out.writeByte(TAG_BYTE);
            out.writeByte((Byte) value);
        } else if (value instanceof Short) {
            out.writeByte(TAG_SHORT);
            out.writeShort((Short) value);
        } else if (value instanceof Integer) {
            out.writeByte(TAG_INT);
            writeVarLong(out, zigZag((Integer) value));
        } else if (value instanceof Long) {
            out.writeByte(TAG_LONG);
            writeVarLong(out, zigZag((Long) value));
        } else if (value instanceof Float) {
            out.writeByte(TAG_FLOAT);
            out.writeFloat((Float) value);
        } else if (value instanceof Double) {
            out.writeByte(TAG_DOUBLE);
            out.writeDouble((Double) value);
        } else if (value instanceof String) {
            out.writeByte(TAG_STRING);
            byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
            // Written as a length and raw bytes rather than through writeUTF, whose two-byte length caps
            // a string at 65,535 bytes -- reachable by a file this protocol exists to carry.
            writeVarLong(out, utf8.length);
            out.write(utf8);
        } else if (value instanceof byte[]) {
            out.writeByte(TAG_BYTES);
            byte[] raw = (byte[]) value;
            writeVarLong(out, raw.length);
            out.write(raw);
        } else if (value instanceof List) {
            out.writeByte(TAG_LIST);
            List<?> list = (List<?>) value;
            writeVarLong(out, list.size());
            for (Object element : list) write(out, element);
        } else if (value instanceof Map) {
            out.writeByte(TAG_MAP);
            Map<?, ?> map = (Map<?, ?>) value;
            writeVarLong(out, map.size());
            // Iteration order is the map's own. CgPlainOps builds LinkedHashMap precisely so this is stable,
            // which is what CgContentHash depends on -- an encoding whose byte order varied by hash order
            // would give one tree two hashes.
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                write(out, entry.getKey());
                write(out, entry.getValue());
            }
        } else {
            // Loudly, and naming the type. CgPlainOps' representation is a closed set; anything else is a
            // caller putting something in the tree that no reader could have decoded.
            throw new CgCodecException("CgBinaryFormat cannot encode " + value.getClass().getName()
                    + " — a CgPlainOps tree holds null, Boolean, the boxed number types, String, byte[], "
                    + "List and Map, and nothing else");
        }
    }

    // ── Decode ──────────────────────────────────────────────────────────────

    /** Decodes bytes produced by {@link #encode}. Throws {@link CgCodecException} on malformed input. */
    public static Object decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Object tree = read(in, 0);
            if (in.available() > 0) {
                // Trailing bytes mean the sender and this reader disagree about the format. Refusing is
                // the point: a partial parse that silently ignores a tail is how a version mismatch turns
                // into a wrong tree rather than an error.
                throw new CgCodecException("trailing bytes after a complete value: " + in.available());
            }
            return tree;
        } catch (IOException truncated) {
            throw new CgCodecException("truncated or malformed binary payload", truncated);
        }
    }

    private static Object read(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new CgCodecException("nesting deeper than " + MAX_DEPTH);
        int tag = in.readUnsignedByte();
        switch (tag) {
            case TAG_NULL:   return null;
            case TAG_FALSE:  return Boolean.FALSE;
            case TAG_TRUE:   return Boolean.TRUE;
            case TAG_BYTE:   return in.readByte();
            case TAG_SHORT:  return in.readShort();
            case TAG_INT:    return (int) unZigZag(readVarLong(in));
            case TAG_LONG:   return unZigZag(readVarLong(in));
            case TAG_FLOAT:  return in.readFloat();
            case TAG_DOUBLE: return in.readDouble();
            case TAG_STRING: {
                byte[] utf8 = new byte[readLength(in)];
                in.readFully(utf8);
                return new String(utf8, StandardCharsets.UTF_8);
            }
            case TAG_BYTES: {
                byte[] raw = new byte[readLength(in)];
                in.readFully(raw);
                return raw;
            }
            case TAG_LIST: {
                int count = readLength(in);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++) list.add(read(in, depth + 1));
                return list;
            }
            case TAG_MAP: {
                int count = readLength(in);
                // LinkedHashMap, matching what CgPlainOps builds: a decoded tree that re-encoded to
                // different bytes would break content addressing on the receiving side.
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    Object key = read(in, depth + 1);
                    map.put(key, read(in, depth + 1));
                }
                return map;
            }
            default:
                throw new CgCodecException("unknown tag 0x" + Integer.toHexString(tag));
        }
    }

    /**
     * A length or count, checked before it is used to allocate.
     *
     * <p>The check is the whole reason this is not inlined. {@code new byte[readVarLong(in)]} on a
     * network-supplied length is an out-of-memory error a peer can request: a five-byte varint asks for
     * two gigabytes. Reading is bounded by what actually arrived, so refusing a length larger than the
     * remaining input costs nothing and removes the class entirely.</p>
     */
    private static int readLength(DataInputStream in) throws IOException {
        long length = readVarLong(in);
        if (length < 0 || length > Integer.MAX_VALUE) throw new CgCodecException("bad length " + length);
        if (length > in.available()) {
            throw new CgCodecException("length " + length + " exceeds the " + in.available()
                    + " bytes that remain — truncated, or a hostile length");
        }
        return (int) length;
    }

    // ── Varint ──────────────────────────────────────────────────────────────
    // Unsigned LEB128, with zig-zag applied by the caller for signed values. Small numbers cost one byte,
    // which is nearly all of them here: counts, lengths, network ids and element indices.

    private static void writeVarLong(DataOutputStream out, long value) throws IOException {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            out.writeByte((int) (remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte((int) remaining);
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            // Ten groups of seven bits covers a 64-bit value; an eleventh means the stream is malformed
            // or is trying to spin this loop forever.
            if (shift >= 70) throw new CgCodecException("varint longer than 10 bytes");
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private static long zigZag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unZigZag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    // ── Stream forms, for the wire engine ───────────────────────────────────

    /** Writes one value into an existing stream, for a caller framing several. */
    public static void writeTo(OutputStream out, Object tree) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        write(data, tree);
        data.flush();
    }

    /** Reads one value from a stream positioned at a tag. */
    public static Object readFrom(InputStream in) throws IOException {
        return read(new DataInputStream(in), 0);
    }
}
