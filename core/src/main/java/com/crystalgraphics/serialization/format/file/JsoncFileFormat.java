package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

import javax.annotation.Nullable;

/**
 * {@link CgFormats#JSONC}: JSON with comments, as VS Code's {@code settings.json}, read by {@link JsoncReader} and
 * written by {@link JsoncWriter}. Comments as {@link YamlFileFormat} keeps them: a codec's own above their keys, a
 * person's carried to the same key path and kept as they wrote them, theirs winning.
 *
 * <ul>
 *   <li>Reads {@code //} and {@code /* *}{@code /} comments and a trailing comma; writes {@code //} comments.</li>
 *   <li>Bytes are Base64 text. NaN and the infinities are not JSON, and throw.</li>
 * </ul>
 */
public final class JsoncFileFormat extends TextFileFormat<Object> {

    @Override
    CgDynamicOps<Object> ops() {
        return TextTreeOps.INSTANCE;
    }

    @Override
    String print(Object tree) {
        return JsoncWriter.write(tree, TextTreeOps.INSTANCE, null);
    }

    @Override
    Object parse(String text) {
        return JsoncReader.read(text, null);
    }

    @Override
    <V> String text(CgCodec<V> codec, V value, @Nullable String previous) {
        FileComments theirs = null;
        if (previous != null) {
            try {
                theirs = new FileComments();
                JsoncReader.read(previous, theirs);
            } catch (CgCodecException notJson) {
                theirs = null;   // replaced whole; its comments cannot be placed
            }
        }
        CommentedOps ours = new CommentedOps(TextTreeOps.INSTANCE);
        return JsoncWriter.write(codec.encode(ours, value), ours, theirs);
    }
}
