package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodecException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary NBT, the format of {@code level.dat}, structure files and {@code .nbt}: a root tag's type, its (usually empty)
 * name, then the payload, big-endian, strings as modified UTF-8. Written from the public format.
 *
 * <p>A read refuses what would exhaust memory or the stack: nesting past 512 and an array or list longer than the bytes
 * that remain cannot be real.</p>
 */
final class NbtBinary {

    private static final int MAX_DEPTH = 512;

    private NbtBinary() {
    }

    static CgNbtCompound read(InputStream in, boolean gzipDetect) throws IOException {
        InputStream source = new BufferedInputStream(in);
        GZIPInputStream gzip = null;
        if (gzipDetect) {
            source.mark(2);
            int a = source.read();
            int b = source.read();
            source.reset();
            if (a == 0x1f && b == 0x8b) {
                gzip = new GZIPInputStream(new Keep.In(source));
                source = new BufferedInputStream(gzip);
            }
        }
        try {
            DataInputStream data = new DataInputStream(source);
            int type = data.readUnsignedByte();
            if (type != CgNbtTag.Type.COMPOUND.id) throw new CgCodecException("NBT: the root is a compound, found tag " + type);
            data.readUTF();   // the root's name
            return (CgNbtCompound) readPayload(data, CgNbtTag.Type.COMPOUND, 0);
        } finally {
            if (gzip != null) gzip.close();   // frees the inflater's native memory; the caller's stream stays open
        }
    }

    static void write(OutputStream out, CgNbtCompound root, boolean gzip) throws IOException {
        GZIPOutputStream z = gzip ? new GZIPOutputStream(new Keep.Out(out)) : null;
        try {
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(gzip ? z : out, 8192));
            data.writeByte(CgNbtTag.Type.COMPOUND.id);
            data.writeUTF("");
            writePayload(data, root);
            data.flush();
        } finally {
            if (z != null) z.close();   // finishes the stream and frees the deflater's native memory; the caller's stays open
        }
    }

    /** Closing the gzip stream must not close the stream the caller handed in. */
    private static final class Keep {
        static final class In extends FilterInputStream {
            In(InputStream in) {
                super(in);
            }

            @Override
            public void close() {
            }
        }

        static final class Out extends OutputStream {
            private final OutputStream out;

            Out(OutputStream out) {
                this.out = out;
            }

            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }
        }
    }

    private static CgNbtTag readPayload(DataInputStream in, CgNbtTag.Type type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new CgCodecException("NBT: nested deeper than " + MAX_DEPTH);
        switch (type) {
            case BYTE: return CgNbtValue.ofByte(in.readByte());
            case SHORT: return CgNbtValue.ofShort(in.readShort());
            case INT: return CgNbtValue.ofInt(in.readInt());
            case LONG: return CgNbtValue.ofLong(in.readLong());
            case FLOAT: return CgNbtValue.ofFloat(in.readFloat());
            case DOUBLE: return CgNbtValue.ofDouble(in.readDouble());
            case STRING: return CgNbtValue.ofString(in.readUTF());
            case BYTE_ARRAY: {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                return CgNbtValue.ofBytes(a);
            }
            case INT_ARRAY: {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return CgNbtValue.ofInts(a);
            }
            case LONG_ARRAY: {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return CgNbtValue.ofLongs(a);
            }
            case LIST: {
                int id = in.readUnsignedByte();
                int n = length(in);
                CgNbtList list = new CgNbtList();
                if (n > 0) {
                    CgNbtTag.Type element = CgNbtTag.Type.of(id);
                    if (element == null) throw new CgCodecException("NBT: a list of tag " + id);
                    for (int i = 0; i < n; i++) list.add(readPayload(in, element, depth + 1));
                }
                return list;
            }
            default: {
                CgNbtCompound compound = new CgNbtCompound();
                while (true) {
                    int id = in.readUnsignedByte();
                    if (id == 0) return compound;
                    CgNbtTag.Type element = CgNbtTag.Type.of(id);
                    if (element == null) throw new CgCodecException("NBT: unknown tag " + id);
                    String key = in.readUTF();
                    compound.put(key, readPayload(in, element, depth + 1));
                }
            }
        }
    }

    /** A length prefix; negative, or longer than the stream can hold, is a damaged file. */
    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > (1 << 26)) throw new CgCodecException("NBT: a length of " + n);
        return n;
    }

    private static void writePayload(DataOutputStream out, CgNbtTag tag) throws IOException {
        if (tag instanceof CgNbtCompound compound) {
            for (Map.Entry<String, CgNbtTag> e : compound.raw().entrySet()) {
                CgNbtTag child = e.getValue();
                out.writeByte(child.type().id);
                out.writeUTF(e.getKey());
                writePayload(out, child);
            }
            out.writeByte(0);
        } else if (tag instanceof CgNbtList list) {
            out.writeByte(list.isEmpty() ? 0 : list.elementType().id);
            out.writeInt(list.size());
            for (CgNbtTag child : list.raw()) writePayload(out, child);
        } else {
            CgNbtValue value = (CgNbtValue) tag;
            Object raw = value.raw();
            switch (value.type()) {
                case BYTE: out.writeByte((Byte) raw); break;
                case SHORT: out.writeShort((Short) raw); break;
                case INT: out.writeInt((Integer) raw); break;
                case LONG: out.writeLong((Long) raw); break;
                case FLOAT: out.writeFloat((Float) raw); break;
                case DOUBLE: out.writeDouble((Double) raw); break;
                case STRING: out.writeUTF((String) raw); break;
                case BYTE_ARRAY: {
                    byte[] a = (byte[]) raw;
                    out.writeInt(a.length);
                    out.write(a);
                    break;
                }
                case INT_ARRAY: {
                    int[] a = (int[]) raw;
                    out.writeInt(a.length);
                    for (int v : a) out.writeInt(v);
                    break;
                }
                default: {
                    long[] a = (long[]) raw;
                    out.writeInt(a.length);
                    for (long v : a) out.writeLong(v);
                }
            }
        }
    }
}
