package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A file format for any codec: {@link CgFormats#BINARY}, {@link CgFormats#JSON} or {@link CgFormats#YAML}.
 *
 * <pre>{@code
 * CgFormats.YAML.write(file, Settings.CODEC, settings);
 * Settings settings = CgFormats.YAML.read(file, Settings.CODEC);      // null when there is no file
 * CgFormats.YAML.read(file, Settings.CODEC, live);                     // into a live object
 * CgFormats.of(file).read(file, Settings.CODEC);                       // by extension
 * }</pre>
 *
 * <ul>
 *   <li>A write replaces the file whole: a crash mid-save leaves the last save.</li>
 *   <li>A read parses the whole file before decoding, so a damaged one throws before a target is touched.</li>
 *   <li>A file that is not this format, or holds what the codec cannot read, throws {@link CgCodecException}.</li>
 * </ul>
 */
public interface CgFormat {

    /** Writes {@code value} to {@code file}, making its folders, replacing it whole. */
    <V> void write(Path file, CgCodec<V> codec, V value) throws IOException;

    /** The value in {@code file}; null when there is no file. */
    @Nullable
    <V> V read(Path file, CgCodec<V> codec) throws IOException;

    /** The value in {@code file}, decoded into {@code target}; no file leaves {@code target} as it is. */
    <V> V read(Path file, CgCodec<V> codec, V target) throws IOException;

    /**
     * {@code value} as this format's bytes, for a store that is not a file. {@code previous} is what they replace, or
     * null: a format that keeps comments carries the old file's across.
     */
    <V> byte[] toBytes(CgCodec<V> codec, V value, @Nullable byte[] previous) throws IOException;

    /** The value in {@code bytes}. */
    <V> V fromBytes(CgCodec<V> codec, byte[] bytes) throws IOException;

    /** The value in {@code bytes}, decoded into {@code target}. */
    <V> V fromBytes(CgCodec<V> codec, byte[] bytes, V target) throws IOException;
}
