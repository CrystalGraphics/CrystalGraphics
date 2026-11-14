package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgDynamicOps} over {@link CgNbtTag}: what every codec runs on to become NBT, with no game in the way. NBT's rules
 * live here once and match Mojang's {@code NbtOps}: a boolean is a byte, map keys are text, a list holds one kind of
 * tag, the absent value is an empty compound, and a typed array reads as a list of its numbers.
 *
 * <pre>{@code
 * CgNbtTag tag = Spell.CODEC.encode(CgNbtOps.INSTANCE, spell);
 * Spell back = Spell.CODEC.decode(CgNbtOps.INSTANCE, tag);
 * }</pre>
 *
 * Most callers use {@link CgNbt#encode} and {@link CgNbt#decode}, which are these two lines.
 */
public final class CgNbtOps implements CgDynamicOps<CgNbtTag> {

    public static final CgNbtOps INSTANCE = new CgNbtOps();

    private CgNbtOps() {
    }

    @Override
    public CgNbtTag empty() {
        return new CgNbtCompound();
    }

    @Override
    public CgNbtTag createString(String value) {
        return CgNbtValue.ofString(value);
    }

    @Override
    public CgNbtTag createNumber(Number value) {
        return CgNbtValue.ofNumber(value);
    }

    @Override
    public CgNbtTag createBoolean(boolean value) {
        return CgNbtValue.ofBool(value);
    }

    @Override
    public CgNbtTag createList(List<CgNbtTag> values) {
        CgNbtList list = new CgNbtList();
        for (CgNbtTag value : values) list.add(value);
        return list;
    }

    @Override
    public CgNbtTag createMap(Map<CgNbtTag, CgNbtTag> entries) {
        CgNbtCompound compound = new CgNbtCompound();
        for (Map.Entry<CgNbtTag, CgNbtTag> e : entries.entrySet()) {
            if (!(e.getKey() instanceof CgNbtValue key) || !key.isString()) {
                throw new CgCodecException("an NBT key is text, not " + e.getKey());
            }
            compound.put(key.asString(), e.getValue());
        }
        return compound;
    }

    @Override
    public CgNbtTag createBytes(byte[] value) {
        return CgNbtValue.ofBytes(value);
    }

    @Override
    public byte[] getBytesValue(CgNbtTag value) {
        if (value instanceof CgNbtValue v && v.type() == CgNbtTag.Type.BYTE_ARRAY) return v.bytes();
        throw new CgCodecException("Not a byte array tag: " + value);
    }

    @Override
    public String getStringValue(CgNbtTag value) {
        if (value instanceof CgNbtValue v && v.isString()) return v.asString();
        throw new CgCodecException("Not a string tag: " + value);
    }

    @Override
    public Number getNumberValue(CgNbtTag value) {
        if (value instanceof CgNbtValue v && v.isNumber()) return v.number();
        throw new CgCodecException("Not a numeric tag: " + value);
    }

    @Override
    public boolean getBooleanValue(CgNbtTag value) {
        return getNumberValue(value).doubleValue() != 0d;
    }

    @Override
    public List<CgNbtTag> getListValue(CgNbtTag value) {
        if (value instanceof CgNbtList list) return list.view();
        if (value instanceof CgNbtValue v) {
            List<CgNbtTag> out = new ArrayList<>();
            switch (v.type()) {
                case BYTE_ARRAY: for (byte b : v.bytes()) out.add(CgNbtValue.ofByte(b)); return out;
                case INT_ARRAY: for (int i : v.ints()) out.add(CgNbtValue.ofInt(i)); return out;
                case LONG_ARRAY: for (long l : v.longs()) out.add(CgNbtValue.ofLong(l)); return out;
                default: break;
            }
        }
        throw new CgCodecException("Not a list tag: " + value);
    }

    @Override
    public Map<CgNbtTag, CgNbtTag> getMapValue(CgNbtTag value) {
        if (!(value instanceof CgNbtCompound compound)) throw new CgCodecException("Not a compound tag: " + value);
        Map<CgNbtTag, CgNbtTag> out = new LinkedHashMap<>(compound.size() * 2);
        for (Map.Entry<String, CgNbtTag> e : compound.raw().entrySet()) out.put(CgNbtValue.ofString(e.getKey()), e.getValue());
        return out;
    }

    @Override
    public Object toPlain(CgNbtTag value) {
        if (value instanceof CgNbtCompound compound) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (String key : compound.keys()) out.put(key, toPlain(compound.get(key)));
            return out;
        }
        if (value instanceof CgNbtList list) {
            List<Object> out = new ArrayList<>(list.size());
            for (CgNbtTag tag : list) out.add(toPlain(tag));
            return out;
        }
        CgNbtValue v = (CgNbtValue) value;
        if (v.isNumber()) return v.number();
        if (v.isString()) return v.asString();
        if (v.type() == CgNbtTag.Type.BYTE_ARRAY) return v.bytes();
        return toPlain(createList(getListValue(v)));
    }
}
