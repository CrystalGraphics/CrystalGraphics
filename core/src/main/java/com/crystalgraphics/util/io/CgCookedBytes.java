package com.crystalgraphics.util.io;

import javax.annotation.Nullable;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The little-endian bytes a cooked file is made of: what the cooked mesh and animation files are written and read
 * through. Strings are a u16 length and UTF-8, {@code 0xFFFF} for null; arrays are raw, their counts written by the
 * file's own layout.
 *
 * <pre>{@code
 * CgCookedBytes.Writer out = new CgCookedBytes.Writer();
 * out.magic("CGMS").u16(1).u32(vertices).string(name).floats(rest);
 * byte[] file = out.toBytes();
 *
 * CgCookedBytes.Reader in = new CgCookedBytes.Reader(file);
 * in.magic("CGMS");                                     // throws naming what it found otherwise
 * int version = in.u16();
 * }</pre>
 *
 * <ul>
 *   <li>A read past the end, a wrong magic or a string that is not UTF-8 throws {@link IllegalArgumentException}
 *       naming where.</li>
 *   <li>Not thread-safe; each file reads and writes on one thread.</li>
 * </ul>
 */
public final class CgCookedBytes {

    private static final int NULL_STRING = 0xFFFF;

    private CgCookedBytes() {
    }

    /** Appends to a growing little-endian buffer. */
    public static final class Writer {

        private ByteBuffer out = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

        public Writer magic(String fourLetters) {
            byte[] b = fourLetters.getBytes(StandardCharsets.US_ASCII);
            if (b.length != 4) throw new IllegalArgumentException("a magic is four letters: " + fourLetters);
            room(4).put(b);
            return this;
        }

        public Writer u8(int v) {
            room(1).put((byte) v);
            return this;
        }

        public Writer u16(int v) {
            room(2).putShort((short) v);
            return this;
        }

        public Writer u32(int v) {
            room(4).putInt(v);
            return this;
        }

        public Writer f32(float v) {
            room(4).putFloat(v);
            return this;
        }

        public Writer f64(double v) {
            room(8).putDouble(v);
            return this;
        }

        public Writer string(@Nullable String s) {
            if (s == null) return u16(NULL_STRING);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length >= NULL_STRING) throw new IllegalArgumentException("a string of " + b.length + " bytes");
            u16(b.length);
            room(b.length).put(b);
            return this;
        }

        public Writer bytes(byte[] b, int from, int length) {
            room(length).put(b, from, length);
            return this;
        }

        public Writer floats(float[] values) {
            room(values.length * 4).asFloatBuffer().put(values);
            out.position(out.position() + values.length * 4);
            return this;
        }

        public Writer ints(int[] values, int count) {
            room(count * 4).asIntBuffer().put(values, 0, count);
            out.position(out.position() + count * 4);
            return this;
        }

        public byte[] toBytes() {
            return Arrays.copyOf(out.array(), out.position());
        }

        private ByteBuffer room(int bytes) {
            if (out.remaining() < bytes) {
                ByteBuffer grown = ByteBuffer.allocate(Math.max(out.capacity() * 2, out.position() + bytes))
                        .order(ByteOrder.LITTLE_ENDIAN);
                grown.put(out.array(), 0, out.position());
                out = grown;
            }
            return out;
        }
    }

    /** Reads a file's bytes in order. */
    public static final class Reader {

        private final ByteBuffer in;

        public Reader(byte[] file) {
            this.in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        }

        /** @throws IllegalArgumentException if the next four bytes are not {@code fourLetters} */
        public void magic(String fourLetters) {
            byte[] b = new byte[4];
            take(4).get(b);
            String found = new String(b, StandardCharsets.US_ASCII);
            if (!found.equals(fourLetters)) throw new IllegalArgumentException("not a " + fourLetters + " file: it starts " + found);
        }

        public int u8() {
            return take(1).get() & 0xFF;
        }

        public int u16() {
            return take(2).getShort() & 0xFFFF;
        }

        public int u32() {
            return take(4).getInt();
        }

        public float f32() {
            return take(4).getFloat();
        }

        public double f64() {
            return take(8).getDouble();
        }

        @Nullable
        public String string() {
            int length = u16();
            if (length == NULL_STRING) return null;
            byte[] b = new byte[length];
            take(length).get(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        /** {@code length} bytes into {@code out} from {@code at}. */
        public void bytes(byte[] out, int at, int length) {
            take(length).get(out, at, length);
        }

        public float[] floats(int count) {
            float[] out = new float[count(count, 4)];
            take(count * 4).asFloatBuffer().get(out);
            in.position(in.position() + count * 4);
            return out;
        }

        public int[] ints(int count) {
            int[] out = new int[count(count, 4)];
            take(count * 4).asIntBuffer().get(out);
            in.position(in.position() + count * 4);
            return out;
        }

        /** Whether every byte has been read. */
        public boolean atEnd() {
            return !in.hasRemaining();
        }

        private int count(int count, int width) {
            if (count < 0 || (long) count * width > in.remaining()) {
                throw new IllegalArgumentException(count + " values at byte " + in.position() + " of " + in.limit());
            }
            return count;
        }

        /** The buffer, checked to hold {@code bytes} more; a relative read on it then moves past them. */
        private ByteBuffer take(int bytes) {
            if (bytes < 0 || in.remaining() < bytes) {
                throw new IllegalArgumentException(new BufferUnderflowException() + ": " + bytes + " bytes at byte "
                        + in.position() + " of " + in.limit());
            }
            return in;
        }
    }
}
