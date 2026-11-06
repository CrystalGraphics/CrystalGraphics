package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormat;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFormats;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A {@link CgFormat} as a tree in {@code T} and how that tree becomes bytes; the file handling is here, once. */
abstract class FileFormat<T> implements CgFormat {

    abstract CgDynamicOps<T> ops();

    abstract byte[] toBytes(T tree) throws IOException;

    abstract T fromBytes(byte[] bytes) throws IOException;

    /** A format that keeps something of the bytes it replaces (comments) overrides this. */
    @Override
    public <V> byte[] toBytes(CgCodec<V> codec, V value, @Nullable byte[] previous) throws IOException {
        return toBytes(codec.encode(ops(), value));
    }

    @Override
    public <V> V fromBytes(CgCodec<V> codec, byte[] bytes) throws IOException {
        return codec.decode(ops(), fromBytes(bytes));
    }

    @Override
    public <V> V fromBytes(CgCodec<V> codec, byte[] bytes, V target) throws IOException {
        return codec.decode(ops(), fromBytes(bytes), target);
    }

    @Override
    public <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        byte[] previous = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        CgFormats.writeAtomic(file, toBytes(codec, value, previous));
    }

    @Override
    @Nullable
    public <V> V read(Path file, CgCodec<V> codec) throws IOException {
        if (!Files.exists(file)) return null;
        return fromBytes(codec, Files.readAllBytes(file));
    }

    @Override
    public <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        if (!Files.exists(file)) return target;
        return fromBytes(codec, Files.readAllBytes(file), target);
    }
}
