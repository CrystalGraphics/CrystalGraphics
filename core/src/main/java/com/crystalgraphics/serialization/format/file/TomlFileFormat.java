package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

import javax.annotation.Nullable;

/**
 * {@link CgFormats#TOML}: TOML 1.0 over the plain tree, read by {@link TomlReader} and written by {@link TomlWriter}.
 * Comments as {@link YamlFileFormat} keeps them: a codec's own above their keys, a person's carried to the same key
 * path, theirs winning; a comment inside an array is not kept.
 *
 * <ul>
 *   <li>The top of a file is a table: a codec whose value is not a map cannot be written.</li>
 *   <li>Bytes are Base64 text; a date or time reads as its text. TOML has no null: a null in a map is not written,
 *       one in a list throws.</li>
 * </ul>
 */
public final class TomlFileFormat extends TextFileFormat<Object> {

    @Override
    CgDynamicOps<Object> ops() {
        return TextTreeOps.INSTANCE;
    }

    @Override
    String print(Object tree) {
        return TomlWriter.write(tree, TextTreeOps.INSTANCE, null);
    }

    @Override
    Object parse(String text) {
        return TomlReader.read(text, null);
    }

    @Override
    <V> String text(CgCodec<V> codec, V value, @Nullable String previous) {
        FileComments theirs = null;
        if (previous != null) {
            try {
                theirs = new FileComments();
                TomlReader.read(previous, theirs);
            } catch (CgCodecException notToml) {
                theirs = null;   // replaced whole; its comments cannot be placed
            }
        }
        CommentedOps ours = new CommentedOps(TextTreeOps.INSTANCE);
        return TomlWriter.write(codec.encode(ours, value), ours, theirs);
    }
}
