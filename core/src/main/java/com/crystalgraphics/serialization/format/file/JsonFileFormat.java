package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.format.CgJsonOps;
import com.google.gson.JsonElement;

/** {@link CgFiles#JSON}: Gson, pretty-printed, through {@link CgJsonOps}. */
public final class JsonFileFormat extends TextFileFormat<JsonElement> {

    @Override
    CgDynamicOps<JsonElement> ops() {
        return CgJsonOps.INSTANCE;
    }

    @Override
    String print(JsonElement tree) {
        return CgJsonOps.toText(tree);
    }

    @Override
    JsonElement parse(String text) {
        return CgJsonOps.fromText(text);
    }
}
