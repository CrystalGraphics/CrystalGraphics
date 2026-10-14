package com.crystalgraphics.serialization.format.nbt;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgDynamicOps} over a host's tags: what {@link CgFormats#nbt()} hands out. The format's rules live here, once,
 * and each era answers only {@link CgHostFormats}' primitives: a boolean is a byte, map keys are text, and the absent
 * value is an empty compound. Mojang's {@code NbtOps} keeps the same rules.
 */
public final class HostTagOps implements CgDynamicOps<Object> {

    private final CgHostFormats tags;

    public HostTagOps(CgHostFormats tags) {
        this.tags = tags;
    }

    @Override
    public Object empty() {
        return tags.compound();
    }

    @Override
    public Object createString(String value) {
        return tags.string(value);
    }

    @Override
    public Object createNumber(Number value) {
        return tags.number(value);
    }

    @Override
    public Object createBoolean(boolean value) {
        return tags.number((byte) (value ? 1 : 0));
    }

    @Override
    public Object createList(List<Object> values) {
        Object list = tags.list();
        for (Object value : values) tags.add(list, value);
        return list;
    }

    @Override
    public Object createMap(Map<Object, Object> entries) {
        Object compound = tags.compound();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            String key = tags.stringOf(e.getKey());
            if (key == null) throw new CgCodecException("an NBT key is text, not " + e.getKey());
            tags.put(compound, key, e.getValue());
        }
        return compound;
    }

    @Override
    public Object createBytes(byte[] value) {
        return tags.bytes(value);
    }

    @Override
    public byte[] getBytesValue(Object value) {
        byte[] bytes = tags.bytesOf(value);
        if (bytes == null) throw new CgCodecException("Not a byte array tag: " + value);
        return bytes;
    }

    @Override
    public String getStringValue(Object value) {
        String text = tags.stringOf(value);
        if (text == null) throw new CgCodecException("Not a string tag: " + value);
        return text;
    }

    @Override
    public Number getNumberValue(Object value) {
        Number number = tags.numberOf(value);
        if (number == null) throw new CgCodecException("Not a numeric tag: " + value);
        return number;
    }

    @Override
    public boolean getBooleanValue(Object value) {
        return getNumberValue(value).intValue() != 0;
    }

    @Override
    public List<Object> getListValue(Object value) {
        List<?> elements = tags.elements(value);
        if (elements == null) throw new CgCodecException("Not a list tag: " + value);
        return new ArrayList<>(elements);
    }

    @Override
    public Map<Object, Object> getMapValue(Object value) {
        Collection<String> keys = tags.keys(value);
        if (keys == null) throw new CgCodecException("Not a compound tag: " + value);
        Map<Object, Object> out = new LinkedHashMap<>(keys.size() * 2);
        for (String key : keys) out.put(tags.string(key), tags.get(value, key));
        return out;
    }
}
