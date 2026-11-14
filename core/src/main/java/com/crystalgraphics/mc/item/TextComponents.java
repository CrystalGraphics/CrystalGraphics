package com.crystalgraphics.mc.item;

import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import com.crystalgraphics.serialization.nbt.CgNbtValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * A chat component as an NBT tag, the form 1.21.5 and later store, and its older spellings: JSON text (1.13 to 1.21.4)
 * and a plain string with {@code §} codes (before 1.13). A plain string tag is a component of that text.
 */
final class TextComponents {

    private TextComponents() {
    }

    /** A component's JSON as a tag; a JSON string becomes a string tag. Text that is not JSON is taken as plain text. */
    static CgNbtTag fromJson(String json) {
        JsonElement element;
        try {
            element = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            return CgNbtValue.ofString(json);
        }
        return fromJson(element);
    }

    private static CgNbtTag fromJson(JsonElement e) {
        if (e.isJsonObject()) {
            CgNbtCompound out = new CgNbtCompound();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) out.put(entry.getKey(), fromJson(entry.getValue()));
            return out;
        }
        if (e.isJsonArray()) return list(e.getAsJsonArray());
        if (e.isJsonNull()) return CgNbtValue.ofString("");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return CgNbtValue.ofBool(p.getAsBoolean());
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE ? CgNbtValue.ofInt((int) d) : CgNbtValue.ofDouble(d);
        }
        return CgNbtValue.ofString(p.getAsString());
    }

    /** A list holds one kind of tag, so a mix of strings and objects ({@code extra}) wraps each loose string as {@code {text:..}}. */
    private static CgNbtList list(JsonArray array) {
        CgNbtList out = new CgNbtList();
        boolean uniform = true;
        for (int i = 1; i < array.size(); i++) uniform &= array.get(i).isJsonObject() == array.get(0).isJsonObject();
        for (JsonElement element : array) {
            CgNbtTag tag = fromJson(element);
            if (!uniform && !(tag instanceof CgNbtCompound)) tag = new CgNbtCompound().put("text", plain(tag));
            out.add(tag);
        }
        return out;
    }

    /** A component as JSON text, the form 1.13 to 1.21.4 store. */
    static String toJson(CgNbtTag tag) {
        return toJsonElement(tag, null).toString();
    }

    private static JsonElement toJsonElement(CgNbtTag tag, String key) {
        if (tag instanceof CgNbtCompound) {
            JsonObject out = new JsonObject();
            ((CgNbtCompound) tag).forEach((k, v) -> out.add(k, toJsonElement(v, k)));
            return out;
        }
        if (tag instanceof CgNbtList) {
            JsonArray out = new JsonArray();
            for (CgNbtTag element : (CgNbtList) tag) out.add(toJsonElement(element, null));
            return out;
        }
        CgNbtValue value = (CgNbtValue) tag;
        if (value.isString()) return new JsonPrimitive(value.asString());
        if (value.isNumber()) {
            if (key != null && isFlag(key)) return new JsonPrimitive(value.asBool());
            return new JsonPrimitive(value.number());
        }
        return new JsonPrimitive(value.toString());
    }

    private static boolean isFlag(String key) {
        switch (key) {
            case "bold": case "italic": case "underlined": case "strikethrough": case "obfuscated": return true;
            default: return false;
        }
    }

    /** The text of a component with its styling dropped: a string, or a compound's {@code text} then its {@code extra}. */
    static String plain(CgNbtTag tag) {
        StringBuilder out = new StringBuilder();
        plain(tag, out);
        return out.toString();
    }

    private static void plain(CgNbtTag tag, StringBuilder out) {
        if (tag instanceof CgNbtValue) {
            CgNbtValue v = (CgNbtValue) tag;
            if (v.isString()) out.append(v.asString());
            return;
        }
        if (tag instanceof CgNbtList) {
            for (CgNbtTag element : (CgNbtList) tag) plain(element, out);
            return;
        }
        CgNbtCompound c = (CgNbtCompound) tag;
        if (c.has("text")) plain(c.get("text"), out);
        else if (c.has("translate")) out.append(c.getString("translate"));
        if (c.has("extra")) plain(c.get("extra"), out);
    }
}
