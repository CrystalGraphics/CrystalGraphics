package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgDynamicOps} over Gson's tree: what {@link CgFormats#JSON} reads and writes, and a codec's value as a
 * {@code JsonElement} where something else wants one.
 *
 * <pre>{@code
 * JsonElement json = Settings.CODEC.encode(CgJsonOps.INSTANCE, settings);
 * Settings back = Settings.CODEC.decode(CgJsonOps.INSTANCE, json);
 * }</pre>
 *
 * <ul>
 *   <li>Map keys are text, bytes Base64 strings, and the absent value {@code JsonNull}.</li>
 *   <li>Gson 2.2.4's API only: it is what 1.7.10 ships.</li>
 * </ul>
 */
public final class CgJsonOps implements CgDynamicOps<JsonElement> {

    public static final CgJsonOps INSTANCE = new CgJsonOps();

    private CgJsonOps() {
    }

    @Override
    public JsonElement empty() {
        return JsonNull.INSTANCE;
    }

    @Override
    public JsonElement createString(String value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createNumber(Number value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createBoolean(boolean value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createList(List<JsonElement> values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) array.add(value);
        return array;
    }

    @Override
    public JsonElement createMap(Map<JsonElement, JsonElement> entries) {
        JsonObject object = new JsonObject();
        for (Map.Entry<JsonElement, JsonElement> entry : entries.entrySet()) {
            object.add(getStringValue(entry.getKey()), entry.getValue());
        }
        return object;
    }

    @Override
    public String getStringValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new CgCodecException("Not a JSON string: " + value);
        }
        return value.getAsString();
    }

    @Override
    public Number getNumberValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new CgCodecException("Not a JSON number: " + value);
        }
        return value.getAsNumber();
    }

    @Override
    public boolean getBooleanValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new CgCodecException("Not a JSON boolean: " + value);
        }
        return value.getAsBoolean();
    }

    @Override
    public List<JsonElement> getListValue(JsonElement value) {
        if (!value.isJsonArray()) throw new CgCodecException("Not a JSON array: " + value);
        List<JsonElement> list = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) list.add(element);
        return list;
    }

    @Override
    public Map<JsonElement, JsonElement> getMapValue(JsonElement value) {
        if (!value.isJsonObject()) throw new CgCodecException("Not a JSON object: " + value);
        Map<JsonElement, JsonElement> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            map.put(new JsonPrimitive(entry.getKey()), entry.getValue());
        }
        return map;
    }
}
