package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgTextFormat;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A {@link FileFormat} whose bytes are UTF-8 text. */
abstract class TextFileFormat<T> extends FileFormat<T> implements CgTextFormat {

    abstract String print(T tree);

    /** @throws CgCodecException where {@code text} is not this format */
    abstract T parse(String text);

    /** {@code value} as text; {@code previous} is the text it replaces, for a format that keeps something of it. */
    <V> String text(CgCodec<V> codec, V value, @Nullable String previous) {
        return print(codec.encode(ops(), value));
    }

    @Override
    final byte[] toBytes(T tree) {
        return print(tree).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    final T fromBytes(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    final <V> byte[] bytesFor(Path file, CgCodec<V> codec, V value) throws IOException {
        String previous = Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
        return text(codec, value, previous).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <V> String toText(CgCodec<V> codec, V value) {
        return text(codec, value, null);
    }

    @Override
    public <V> V fromText(CgCodec<V> codec, String text) {
        return codec.decode(ops(), parse(text));
    }

    @Override
    public <V> V fromText(CgCodec<V> codec, String text, V target) {
        return codec.decode(ops(), parse(text), target);
    }
}
