package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;

/**
 * A {@link CgFormat} people read and edit, as text too: {@link CgFormats#JSON} and {@link CgFormats#YAML}.
 *
 * <pre>{@code
 * String yaml = CgFormats.YAML.toText(Settings.CODEC, settings);
 * Settings back = CgFormats.YAML.fromText(Settings.CODEC, yaml);
 * CgFormats.JSON.fromText(Settings.CODEC, json, live);                // into a live object
 * }</pre>
 *
 * Map keys are text; bytes are Base64 in JSON and {@code !!binary} in YAML.
 */
public interface CgTextFormat extends CgFormat {

    <V> String toText(CgCodec<V> codec, V value);

    <V> V fromText(CgCodec<V> codec, String text);

    <V> V fromText(CgCodec<V> codec, String text, V target);
}
