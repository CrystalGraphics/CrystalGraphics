package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /** The plain tree, bytes as Base64 text. */
    private static final CgDynamicOps<Object> OPS = new CgDynamicOps<Object>() {
        @Override public Object empty() { return CgPlainOps.INSTANCE.empty(); }
        @Override public Object createString(String value) { return value; }
        @Override public Object createNumber(Number value) { return value; }
        @Override public Object createBoolean(boolean value) { return value; }
        @Override public Object createList(List<Object> values) { return CgPlainOps.INSTANCE.createList(values); }
        @Override public Object createMap(Map<Object, Object> entries) { return CgPlainOps.INSTANCE.createMap(entries); }
        @Override public String getStringValue(Object value) { return CgPlainOps.INSTANCE.getStringValue(value); }
        @Override public Number getNumberValue(Object value) { return CgPlainOps.INSTANCE.getNumberValue(value); }
        @Override public boolean getBooleanValue(Object value) { return CgPlainOps.INSTANCE.getBooleanValue(value); }
        @Override public List<Object> getListValue(Object value) { return CgPlainOps.INSTANCE.getListValue(value); }
        @Override public Map<Object, Object> getMapValue(Object value) { return CgPlainOps.INSTANCE.getMapValue(value); }
    };

    @Override
    CgDynamicOps<Object> ops() {
        return OPS;
    }

    @Override
    String print(Object tree) {
        return TomlWriter.write(tree, Collections.emptyMap(), null);
    }

    @Override
    Object parse(String text) {
        return TomlReader.read(text, null);
    }

    @Override
    <V> String text(CgCodec<V> codec, V value, @Nullable String previous) {
        TomlReader.Comments theirs = null;
        if (previous != null) {
            try {
                theirs = new TomlReader.Comments();
                TomlReader.read(previous, theirs);
            } catch (CgCodecException notToml) {
                theirs = null;   // replaced whole; its comments cannot be placed
            }
        }
        return TomlWriter.write(codec.encode(OPS, value), codec.comments(), theirs);
    }
}
