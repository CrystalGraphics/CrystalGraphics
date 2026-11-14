package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link CgNbt.Host} over legacy Forge's tags, 1.8.9 to 1.12.2: one spelling throughout. Provided by
 * {@code PlatformServiceLegacy} on both sides. A long array tag does not exist before 1.12.2 and has no reader there,
 * so none is made or read.
 */
public final class NbtLegacy implements CgNbt.Host {

    private static CgCodecException noLongArrays() {
        return new CgCodecException("long array tags need Minecraft 1.13 or later");
    }

    @Override
    public int kind(Object tag) {
        return tag instanceof NBTBase ? ((NBTBase) tag).getId() : 0;
    }

    @Override
    public Object compound() {
        return new NBTTagCompound();
    }

    @Override
    public void put(Object compound, String key, Object value) {
        ((NBTTagCompound) compound).setTag(key, (NBTBase) value);
    }

    @Override
    public Collection<String> keys(Object compound) {
        return ((NBTTagCompound) compound).getKeySet();
    }

    @Override
    public Object get(Object compound, String key) {
        return ((NBTTagCompound) compound).getTag(key);
    }

    @Override
    public Object list() {
        return new NBTTagList();
    }

    @Override
    public void add(Object list, Object value) {
        ((NBTTagList) list).appendTag((NBTBase) value);
    }

    @Override
    public List<?> elements(Object list) {
        NBTTagList tags = (NBTTagList) list;
        List<NBTBase> out = new ArrayList<>(tags.tagCount());
        for (int i = 0; i < tags.tagCount(); i++) out.add(tags.get(i));
        return out;
    }

    @Override
    public Object string(String value) {
        return new NBTTagString(value);
    }

    @Override
    public String stringOf(Object tag) {
        return ((NBTTagString) tag).getString();
    }

    @Override
    public Object number(CgNbtTag.Type type, Number value) {
        switch (type) {
            case BYTE: return new NBTTagByte(value.byteValue());
            case SHORT: return new NBTTagShort(value.shortValue());
            case INT: return new NBTTagInt(value.intValue());
            case LONG: return new NBTTagLong(value.longValue());
            case FLOAT: return new NBTTagFloat(value.floatValue());
            default: return new NBTTagDouble(value.doubleValue());
        }
    }

    @Override
    public Number numberOf(Object tag) {
        if (tag instanceof NBTTagByte) return ((NBTTagByte) tag).getByte();
        if (tag instanceof NBTTagShort) return ((NBTTagShort) tag).getShort();
        if (tag instanceof NBTTagInt) return ((NBTTagInt) tag).getInt();
        if (tag instanceof NBTTagLong) return ((NBTTagLong) tag).getLong();
        if (tag instanceof NBTTagFloat) return ((NBTTagFloat) tag).getFloat();
        return ((NBTTagDouble) tag).getDouble();
    }

    @Override
    public Object bytes(byte[] value) {
        return new NBTTagByteArray(value);
    }

    @Override
    public byte[] bytesOf(Object tag) {
        return ((NBTTagByteArray) tag).getByteArray();
    }

    @Override
    public Object ints(int[] value) {
        return new NBTTagIntArray(value);
    }

    @Override
    public int[] intsOf(Object tag) {
        return ((NBTTagIntArray) tag).getIntArray();
    }

    @Override
    public Object longs(long[] value) {
        throw noLongArrays();
    }

    @Override
    public long[] longsOf(Object tag) {
        throw noLongArrays();
    }
}
