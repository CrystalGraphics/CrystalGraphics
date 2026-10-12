package com.crystalgraphics.net.object;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.crystalgraphics.serialization.CgCodecException;

/** A growable byte buffer with the varints and fixed widths an object frame is made of. Reused; never shrinks. */
final class NetBuffer implements CgNetWire.Out, CgNetWire.In {

    byte[] bytes;
    /** Where the next write goes; the end of what a read may see. */
    int size;
    /** Where the next read comes from. */
    int pos;

    NetBuffer(int capacity) {
        bytes = new byte[capacity];
    }

    /** Reads {@code bytes}, whole. */
    NetBuffer(byte[] bytes) {
        this.bytes = bytes;
        size = bytes.length;
    }

    void clear() {
        size = 0;
        pos = 0;
    }

    boolean isEmpty() {
        return size == 0;
    }

    private void ensure(int more) {
        if (size + more > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    void writeByte(int b) {
        ensure(1);
        bytes[size++] = (byte) b;
    }

    @Override
    public void writeBool(boolean b) {
        writeByte(b ? 1 : 0);
    }

    /** Unsigned. */
    @Override
    public void writeVarInt(int v) {
        writeVarLong(v & 0xFFFFFFFFL);
    }

    /** Unsigned. */
    void writeVarLong(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            bytes[size++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        bytes[size++] = (byte) v;
    }

    /** Signed, small either side of zero. */
    @Override
    public void writeZigZag(long v) {
        writeVarLong((v << 1) ^ (v >> 63));
    }

    @Override
    public void writeInt(int v) {
        ensure(4);
        bytes[size++] = (byte) (v >>> 24);
        bytes[size++] = (byte) (v >>> 16);
        bytes[size++] = (byte) (v >>> 8);
        bytes[size++] = (byte) v;
    }

    @Override
    public void writeLong(long v) {
        writeInt((int) (v >>> 32));
        writeInt((int) v);
    }

    @Override
    public void writeFloat(float v) {
        writeInt(Float.floatToRawIntBits(v));
    }

    @Override
    public void writeDouble(double v) {
        writeLong(Double.doubleToRawLongBits(v));
    }

    void writeString(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(utf8.length);
        writeBytes(utf8, 0, utf8.length);
    }

    void writeBytes(byte[] from, int offset, int length) {
        ensure(length);
        System.arraycopy(from, offset, bytes, size, length);
        size += length;
    }

    /** {@code other}'s bytes {@code [from, to)}. */
    void append(NetBuffer other, int from, int to) {
        writeBytes(other.bytes, from, to - from);
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    boolean more() {
        return pos < size;
    }

    private void need(int n) {
        if (pos + n > size) throw new CgCodecException("object frame ends early");
    }

    int readByte() {
        need(1);
        return bytes[pos++] & 0xFF;
    }

    @Override
    public boolean readBool() {
        return readByte() != 0;
    }

    @Override
    public int readVarInt() {
        long v = readVarLong();
        if (v > 0xFFFFFFFFL) throw new CgCodecException("varint too long");
        return (int) v;
    }

    long readVarLong() {
        long v = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = readByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new CgCodecException("varint too long");
    }

    @Override
    public long readZigZag() {
        long v = readVarLong();
        return (v >>> 1) ^ -(v & 1);
    }

    @Override
    public int readInt() {
        need(4);
        int v = (bytes[pos] & 0xFF) << 24 | (bytes[pos + 1] & 0xFF) << 16 | (bytes[pos + 2] & 0xFF) << 8 | (bytes[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    @Override
    public long readLong() {
        return (long) readInt() << 32 | (readInt() & 0xFFFFFFFFL);
    }

    @Override
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    String readString() {
        int length = readVarInt();
        need(length);
        String s = new String(bytes, pos, length, StandardCharsets.UTF_8);
        pos += length;
        return s;
    }

    /** Moves past {@code n} bytes. */
    void skip(int n) {
        need(n);
        pos += n;
    }
}
