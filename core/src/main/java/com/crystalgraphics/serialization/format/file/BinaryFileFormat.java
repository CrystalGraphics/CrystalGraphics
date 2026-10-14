package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgBinaryFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** {@link CgFormats#BINARY}: {@link CgBinaryFormat}, gzipped. */
public final class BinaryFileFormat extends FileFormat<Object> {

    @Override
    CgDynamicOps<Object> ops() {
        return CgPlainOps.INSTANCE;
    }

    @Override
    byte[] toBytes(Object tree) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            CgBinaryFormat.writeTo(out, tree);
        }
        return bytes.toByteArray();
    }

    @Override
    Object fromBytes(byte[] compressed) throws IOException {
        // whole, then decoded: the format checks each length against what remains, which a gzip stream cannot say
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(compressed.length * 4);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) bytes.write(chunk, 0, n);
        }
        return CgBinaryFormat.decode(bytes.toByteArray());
    }
}
