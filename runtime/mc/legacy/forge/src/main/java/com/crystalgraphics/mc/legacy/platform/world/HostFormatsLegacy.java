package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.serialization.CgHostFormats;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link CgHostFormats} over legacy Forge's tags, 1.8.9 to 1.12.2: one spelling throughout. Provided by
 * {@code NetworkLegacy} on both sides.
 */
public final class HostFormatsLegacy implements CgHostFormats {

    @Override
    public boolean present() {
        return true;
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
    @Nullable
    public Collection<String> keys(Object tag) {
        return tag instanceof NBTTagCompound ? ((NBTTagCompound) tag).getKeySet() : null;
    }

    @Override
    @Nullable
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
    @Nullable
    public List<?> elements(Object tag) {
        if (!(tag instanceof NBTTagList)) return null;
        NBTTagList list = (NBTTagList) tag;
        List<NBTBase> out = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) out.add(list.get(i));
        return out;
    }

    @Override
    public Object string(String value) {
        return new NBTTagString(value);
    }

    @Override
    @Nullable
    public String stringOf(Object tag) {
        return tag instanceof NBTTagString ? ((NBTTagString) tag).getString() : null;
    }

    @Override
    public Object number(Number value) {
        if (value instanceof Byte) return new NBTTagByte((Byte) value);
        if (value instanceof Short) return new NBTTagShort((Short) value);
        if (value instanceof Integer) return new NBTTagInt((Integer) value);
        if (value instanceof Long) return new NBTTagLong((Long) value);
        if (value instanceof Float) return new NBTTagFloat((Float) value);
        return new NBTTagDouble(value.doubleValue());
    }

    @Override
    @Nullable
    public Number numberOf(Object tag) {
        if (tag instanceof NBTTagByte) return ((NBTTagByte) tag).getByte();
        if (tag instanceof NBTTagShort) return ((NBTTagShort) tag).getShort();
        if (tag instanceof NBTTagInt) return ((NBTTagInt) tag).getInt();
        if (tag instanceof NBTTagLong) return ((NBTTagLong) tag).getLong();
        if (tag instanceof NBTTagFloat) return ((NBTTagFloat) tag).getFloat();
        if (tag instanceof NBTTagDouble) return ((NBTTagDouble) tag).getDouble();
        return null;
    }

    @Override
    public Object bytes(byte[] value) {
        return new NBTTagByteArray(value);
    }

    @Override
    @Nullable
    public byte[] bytesOf(Object tag) {
        return tag instanceof NBTTagByteArray ? ((NBTTagByteArray) tag).getByteArray() : null;
    }
}
