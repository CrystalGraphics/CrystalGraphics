package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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

    private static final Gson PRINTER = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private CgJsonOps() {
    }

    /**
     * A tree built by hand as pretty-printed JSON, ending in a newline: what {@link CgFormats#JSON} writes, for a tree
     * no single codec made.
     *
     * <pre>{@code
     * CgStateMap<JsonElement> out = new CgStateMap<>(CgJsonOps.INSTANCE);
     * out.putString("theme", "dark");
     * storage.write(file, CgJsonOps.toText(out.encode()));
     * }</pre>
     */
    public static String toText(JsonElement tree) {
        return PRINTER.toJson(tree) + "\n";
    }

    /**
     * {@code text} as a tree.
     *
     * @throws CgCodecException where it is not JSON
     */
    public static JsonElement fromText(String text) {
        try {
            // the instance parse: 2.2.4, 1.7.10's, has no static JsonParser.parseString
            return new JsonParser().parse(text);
        } catch (JsonParseException e) {
            throw new CgCodecException("Not JSON: " + e.getMessage(), e);
        }
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

    /** A whole number as an {@code Integer} where it fits, else a {@code Long}; any other number a {@code Double}. */
    @Override
    public Object toPlain(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) map.put(e.getKey(), toPlain(e.getValue()));
            return map;
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : value.getAsJsonArray()) list.add(toPlain(e));
            return list;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return p.getAsString();
        String text = p.getAsString();
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            long whole = p.getAsLong();
            return whole == (int) whole ? (Object) (int) whole : (Object) whole;
        }
        return p.getAsDouble();
    }
}
