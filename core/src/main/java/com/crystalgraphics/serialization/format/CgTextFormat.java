package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;

/**
 * A {@link CgFileFormat} people read and edit, as text too: {@link CgFiles#JSON} and {@link CgFiles#YAML}.
 *
 * <pre>{@code
 * String yaml = CgFiles.YAML.toText(Settings.CODEC, settings);
 * Settings back = CgFiles.YAML.fromText(Settings.CODEC, yaml);
 * CgFiles.JSON.fromText(Settings.CODEC, json, live);                // into a live object
 * }</pre>
 *
 * Map keys are text; bytes are Base64 in JSON and {@code !!binary} in YAML.
 */
public interface CgTextFormat extends CgFileFormat {

    <V> String toText(CgCodec<V> codec, V value);

    <V> V fromText(CgCodec<V> codec, String text);

    <V> V fromText(CgCodec<V> codec, String text, V target);
}
