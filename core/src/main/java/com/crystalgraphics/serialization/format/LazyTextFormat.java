package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A text format made on first use, so {@link CgFormats} loads where its library is missing: a dev run without
 * SnakeYAML still writes binary files and NBT, and a YAML call there names what is missing.
 */
final class LazyTextFormat implements CgTextFormat {

    private final String name;
    private final String library;
    private final Supplier<CgTextFormat> make;
    @Nullable
    private volatile CgTextFormat format;

    LazyTextFormat(String name, String library, Supplier<CgTextFormat> make) {
        this.name = name;
        this.library = library;
        this.make = make;
    }

    private CgTextFormat format() {
        CgTextFormat made = format;
        if (made != null) return made;
        try {
            return format = make.get();
        } catch (NoClassDefFoundError missing) {
            throw new IllegalStateException(name + " needs " + library + " on the classpath; CrystalGraphics' jar "
                    + "carries it, a dev run must add it", missing);
        }
    }

    @Override
    public <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        format().write(file, codec, value);
    }

    @Override
    @Nullable
    public <V> V read(Path file, CgCodec<V> codec) throws IOException {
        return format().read(file, codec);
    }

    @Override
    public <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        return format().read(file, codec, target);
    }

    @Override
    public <V> String toText(CgCodec<V> codec, V value) {
        return format().toText(codec, value);
    }

    @Override
    public <V> V fromText(CgCodec<V> codec, String text) {
        return format().fromText(codec, text);
    }

    @Override
    public <V> V fromText(CgCodec<V> codec, String text, V target) {
        return format().fromText(codec, text, target);
    }

    @Override
    public String toString() {
        return name;
    }
}
