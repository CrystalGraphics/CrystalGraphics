package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgJsonOps;
import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** {@link CgFormats#JSON}: Gson, pretty-printed. Gson 2.2.4's API only, 1.7.10's. */
public final class JsonFileFormat extends TextFileFormat<JsonElement> {

    private static final Gson PRINTER = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    CgDynamicOps<JsonElement> ops() {
        return CgJsonOps.INSTANCE;
    }

    @Override
    String print(JsonElement tree) {
        return PRINTER.toJson(tree) + "\n";
    }

    @Override
    JsonElement parse(String text) {
        try {
            // the instance parse: 2.2.4 has no static JsonParser.parseString
            return new JsonParser().parse(text);
        } catch (JsonParseException e) {
            throw new CgCodecException("Not JSON: " + e.getMessage(), e);
        }
    }
}
