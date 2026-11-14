package com.crystalgraphics.mc.v1710.platform.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

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

/**
 * {@link CgNbt.Host} over 1.7.10's tags, whose readers are still SRG names. Provided by {@code PlatformService1710} on
 * both sides. Long array tags do not exist in 1.7.10.
 */
public final class Nbt1710 implements CgNbt.Host {

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
    @SuppressWarnings("unchecked")
    public Collection<String> keys(Object compound) {
        return ((NBTTagCompound) compound).func_150296_c();
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
        // no reader by index: taken off the end, then put back, the list's kind kept
        NBTTagList tags = (NBTTagList) list;
        NBTBase[] out = new NBTBase[tags.tagCount()];
        for (int i = out.length - 1; i >= 0; i--) out[i] = tags.removeTag(i);
        for (NBTBase element : out) tags.appendTag(element);
        return new ArrayList<>(Arrays.asList(out));
    }

    @Override
    public Object string(String value) {
        return new NBTTagString(value);
    }

    @Override
    public String stringOf(Object tag) {
        return ((NBTTagString) tag).func_150285_a_();
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
        if (tag instanceof NBTTagByte) return ((NBTTagByte) tag).func_150290_f();
        if (tag instanceof NBTTagShort) return ((NBTTagShort) tag).func_150289_e();
        if (tag instanceof NBTTagInt) return ((NBTTagInt) tag).func_150287_d();
        if (tag instanceof NBTTagLong) return ((NBTTagLong) tag).func_150291_c();
        if (tag instanceof NBTTagFloat) return ((NBTTagFloat) tag).func_150288_h();
        return ((NBTTagDouble) tag).func_150286_g();
    }

    @Override
    public Object bytes(byte[] value) {
        return new NBTTagByteArray(value);
    }

    @Override
    public byte[] bytesOf(Object tag) {
        return ((NBTTagByteArray) tag).func_150292_c();
    }

    @Override
    public Object ints(int[] value) {
        return new NBTTagIntArray(value);
    }

    @Override
    public int[] intsOf(Object tag) {
        return ((NBTTagIntArray) tag).func_150302_c();
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
