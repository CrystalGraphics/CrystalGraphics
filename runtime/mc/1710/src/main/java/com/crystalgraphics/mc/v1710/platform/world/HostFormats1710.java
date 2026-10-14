package com.crystalgraphics.mc.v1710.platform.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.format.nbt.CgHostFormats;

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

/**
 * {@link CgHostFormats} over 1.7.10's tags, whose readers are still SRG names. Provided by {@code Network1710} on
 * both sides.
 */
public final class HostFormats1710 implements CgHostFormats {

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
        return tag instanceof NBTTagCompound ? ((NBTTagCompound) tag).func_150296_c() : null;
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
        // no reader by index: taken off the end, then put back, the list's kind kept
        NBTTagList list = (NBTTagList) tag;
        NBTBase[] out = new NBTBase[list.tagCount()];
        for (int i = out.length - 1; i >= 0; i--) out[i] = list.removeTag(i);
        for (NBTBase element : out) list.appendTag(element);
        return new ArrayList<>(Arrays.asList(out));
    }

    @Override
    public Object string(String value) {
        return new NBTTagString(value);
    }

    @Override
    @Nullable
    public String stringOf(Object tag) {
        return tag instanceof NBTTagString ? ((NBTTagString) tag).func_150285_a_() : null;
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
        if (tag instanceof NBTTagByte) return ((NBTTagByte) tag).func_150290_f();
        if (tag instanceof NBTTagShort) return ((NBTTagShort) tag).func_150289_e();
        if (tag instanceof NBTTagInt) return ((NBTTagInt) tag).func_150287_d();
        if (tag instanceof NBTTagLong) return ((NBTTagLong) tag).func_150291_c();
        if (tag instanceof NBTTagFloat) return ((NBTTagFloat) tag).func_150288_h();
        if (tag instanceof NBTTagDouble) return ((NBTTagDouble) tag).func_150286_g();
        return null;
    }

    @Override
    public Object bytes(byte[] value) {
        return new NBTTagByteArray(value);
    }

    @Override
    @Nullable
    public byte[] bytesOf(Object tag) {
        return tag instanceof NBTTagByteArray ? ((NBTTagByteArray) tag).func_150292_c() : null;
    }
}
