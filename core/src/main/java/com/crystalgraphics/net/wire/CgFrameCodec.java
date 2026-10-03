package com.crystalgraphics.net.wire;

import com.crystalgraphics.serialization.CgCodecException;


/**
 * One frame — the unit the platform actually carries.
 *
 * <pre>
 *   [u8 opcode][u8 flags][varint streamId][payload …]
 * </pre>
 *
 * <p><b>No length field.</b> The platform hands over one discrete {@code byte[]} per frame — a Minecraft
 * custom payload is already framed — so a length would restate what {@code array.length} says, and the
 * two could disagree. This is the one piece of framing we get for free and should not rebuild.</p>
 *
 * <p><b>No sequence number either</b>, which is the larger simplification. Delivery is a single ordered,
 * reliable TCP connection, so fragments cannot arrive out of order and cannot go missing without the
 * connection dying. A sequence number would encode a guarantee the transport already makes, and it would
 * buy a reordering buffer that no test could ever exercise — code that is never run is never correct.
 * A datagram protocol could not make this choice; this one can.</p>
 */
final class CgFrameCodec {

    /** A fragment of a message. {@link #FLAG_FIN} marks the last one on its stream. */
    static final int OP_DATA = 0x01;

    /** Grants the peer credit to send more bytes. Stream 0 means the connection as a whole. */
    static final int OP_WINDOW_UPDATE = 0x02;

    /** Abandons a stream. Both sides drop its reassembly state. */
    static final int OP_RESET = 0x03;

    /** Last fragment of a message. */
    static final int FLAG_FIN = 0x01;

    /** Stream 0 is the connection itself and never carries {@link #OP_DATA}. */
    static final int CONNECTION_STREAM = 0;

    /** opcode + flags, before the varint stream id. */
    private static final int FIXED_HEADER = 2;

    private CgFrameCodec() {
    }

    /** Worst-case header size for a stream id, so a caller can size payloads without guessing. */
    static int headerSize(int streamId) {
        return FIXED_HEADER + varIntSize(streamId);
    }

    static byte[] data(int streamId, byte[] source, int offset, int length, boolean fin) {
        byte[] frame = new byte[headerSize(streamId) + length];
        int at = writeHeader(frame, OP_DATA, fin ? FLAG_FIN : 0, streamId);
        System.arraycopy(source, offset, frame, at, length);
        return frame;
    }

    static byte[] windowUpdate(int streamId, int credit) {
        byte[] frame = new byte[headerSize(streamId) + varIntSize(credit)];
        int at = writeHeader(frame, OP_WINDOW_UPDATE, 0, streamId);
        writeVarInt(frame, at, credit);
        return frame;
    }

    static byte[] reset(int streamId) {
        byte[] frame = new byte[headerSize(streamId)];
        writeHeader(frame, OP_RESET, 0, streamId);
        return frame;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    static int opcode(byte[] frame) {
        require(frame.length >= FIXED_HEADER, "frame shorter than a header");
        return frame[0] & 0xFF;
    }

    static boolean isFin(byte[] frame) {
        require(frame.length >= FIXED_HEADER, "frame shorter than a header");
        return (frame[1] & FLAG_FIN) != 0;
    }

    static int streamId(byte[] frame) {
        return (int) readVarInt(frame, FIXED_HEADER);
    }

    /** Where the payload starts — past the header and the varint stream id. */
    static int payloadOffset(byte[] frame) {
        return FIXED_HEADER + varIntSize(streamId(frame));
    }

    static int payloadLength(byte[] frame) {
        return frame.length - payloadOffset(frame);
    }

    /** The credit carried by an {@link #OP_WINDOW_UPDATE}. */
    static int credit(byte[] frame) {
        return (int) readVarInt(frame, payloadOffset(frame));
    }

    // ── Varint ──────────────────────────────────────────────────────────────
    // Unsigned; stream ids and credits are never negative. Shared shape with CgBinaryFormat, deliberately
    // not shared code -- that one reads from a stream and this one indexes an array, and forcing one
    // helper to do both would mean a stream wrapper per frame on the hot path.

    private static int writeHeader(byte[] frame, int opcode, int flags, int streamId) {
        frame[0] = (byte) opcode;
        frame[1] = (byte) flags;
        return writeVarInt(frame, FIXED_HEADER, streamId);
    }

    private static int writeVarInt(byte[] target, int at, int value) {
        int remaining = value;
        int index = at;
        while ((remaining & ~0x7F) != 0) {
            target[index++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        target[index++] = (byte) remaining;
        return index;
    }

    private static long readVarInt(byte[] source, int at) {
        long value = 0;
        int shift = 0;
        int index = at;
        while (true) {
            require(index < source.length, "varint runs past the end of the frame");
            require(shift < 35, "varint longer than five bytes");
            int b = source[index++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                require(value <= Integer.MAX_VALUE, "varint larger than an int");
                return value;
            }
            shift += 7;
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    private static void require(boolean condition, String message) {
        // CgCodecException rather than an assertion: these bytes came off a network, so a malformed frame
        // is an ordinary event to refuse rather than a bug to crash on.
        if (!condition) throw new CgCodecException("malformed wire frame — " + message);
    }
}
