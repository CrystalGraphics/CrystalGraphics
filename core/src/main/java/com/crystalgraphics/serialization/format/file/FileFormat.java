package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormat;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgDynamicOps;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** A {@link CgFormat} as a tree in {@code T} and how that tree becomes bytes; the file handling is here, once. */
abstract class FileFormat<T> implements CgFormat {

    abstract CgDynamicOps<T> ops();

    abstract byte[] toBytes(T tree) throws IOException;

    abstract T fromBytes(byte[] bytes) throws IOException;

    /** What {@link #write} puts in {@code file}; a format that keeps something of the file it replaces reads it here. */
    <V> byte[] bytesFor(Path file, CgCodec<V> codec, V value) throws IOException {
        return toBytes(codec.encode(ops(), value));
    }

    @Override
    public <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        byte[] bytes = bytesFor(file, codec, value);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    @Nullable
    public <V> V read(Path file, CgCodec<V> codec) throws IOException {
        if (!Files.exists(file)) return null;
        return codec.decode(ops(), fromBytes(Files.readAllBytes(file)));
    }

    @Override
    public <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        if (!Files.exists(file)) return target;
        return codec.decode(ops(), fromBytes(Files.readAllBytes(file)), target);
    }
}
