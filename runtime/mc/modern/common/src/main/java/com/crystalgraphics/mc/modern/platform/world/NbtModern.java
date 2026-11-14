package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
//? if >=1.21.5 {
/*import net.minecraft.nbt.NumericTag;
*///?}

import java.util.Collection;
import java.util.List;

/**
 * {@link CgNbt.Host} over Minecraft's tags, 1.13.2 to 26.3. Provided by {@code PlatformServiceModern} on both sides.
 *
 * <ul>
 *   <li>Strings and numbers are made by {@code valueOf} from 1.15.2, by constructor before; read through
 *       {@code getAsString}/{@code getAs*} to 1.21.4, and from 1.21.5, where tags are records, {@code value()} and
 *       {@code NumericTag.box()}.</li>
 *   <li>A compound's keys are {@code getAllKeys()} to 1.21.4, {@code keySet()} after; 1.13.2's put is still
 *       obfuscated, {@code a(String, Tag)}.</li>
 *   <li>A tag's kind is {@code getId()} throughout: the ids are the format's own.</li>
 * </ul>
 */
public final class NbtModern implements CgNbt.Host {

    @Override
    public int kind(Object tag) {
        return tag instanceof Tag ? ((Tag) tag).getId() : 0;
    }

    @Override
    public Object compound() {
        return new CompoundTag();
    }

    @Override
    public void put(Object compound, String key, Object value) {
        //? if >=1.14.3 {
        ((CompoundTag) compound).put(key, (Tag) value);
        //?} else {
        /*((CompoundTag) compound).a(key, (Tag) value);
        *///?}
    }

    @Override
    public Collection<String> keys(Object compound) {
        //? if >=1.21.5 {
        /*return ((CompoundTag) compound).keySet();
        *///?} else {
        return ((CompoundTag) compound).getAllKeys();
        //?}
    }

    @Override
    public Object get(Object compound, String key) {
        return ((CompoundTag) compound).get(key);
    }

    @Override
    public Object list() {
        return new ListTag();
    }

    @Override
    public void add(Object list, Object value) {
        ((ListTag) list).add((Tag) value);
    }

    @Override
    public List<?> elements(Object list) {
        return (ListTag) list;
    }

    @Override
    public Object string(String value) {
        //? if >=1.15.2 {
        return StringTag.valueOf(value);
        //?} else {
        /*return new StringTag(value);
        *///?}
    }

    @Override
    public String stringOf(Object tag) {
        //? if >=1.21.5 {
        /*return ((StringTag) tag).value();
        *///?} else {
        return ((StringTag) tag).getAsString();
        //?}
    }

    @Override
    public Object number(CgNbtTag.Type type, Number value) {
        //? if >=1.15.2 {
        switch (type) {
            case BYTE: return ByteTag.valueOf(value.byteValue());
            case SHORT: return ShortTag.valueOf(value.shortValue());
            case INT: return IntTag.valueOf(value.intValue());
            case LONG: return LongTag.valueOf(value.longValue());
            case FLOAT: return FloatTag.valueOf(value.floatValue());
            default: return DoubleTag.valueOf(value.doubleValue());
        }
        //?} else {
        /*switch (type) {
            case BYTE: return new ByteTag(value.byteValue());
            case SHORT: return new ShortTag(value.shortValue());
            case INT: return new IntTag(value.intValue());
            case LONG: return new LongTag(value.longValue());
            case FLOAT: return new FloatTag(value.floatValue());
            default: return new DoubleTag(value.doubleValue());
        }
        *///?}
    }

    @Override
    public Number numberOf(Object tag) {
        //? if >=1.21.5 {
        /*return ((NumericTag) tag).box();
        *///?} else {
        if (tag instanceof ByteTag) return ((ByteTag) tag).getAsByte();
        if (tag instanceof ShortTag) return ((ShortTag) tag).getAsShort();
        if (tag instanceof IntTag) return ((IntTag) tag).getAsInt();
        if (tag instanceof LongTag) return ((LongTag) tag).getAsLong();
        if (tag instanceof FloatTag) return ((FloatTag) tag).getAsFloat();
        return ((DoubleTag) tag).getAsDouble();
        //?}
    }

    @Override
    public Object bytes(byte[] value) {
        return new ByteArrayTag(value);
    }

    @Override
    public byte[] bytesOf(Object tag) {
        return ((ByteArrayTag) tag).getAsByteArray();
    }

    @Override
    public Object ints(int[] value) {
        return new IntArrayTag(value);
    }

    @Override
    public int[] intsOf(Object tag) {
        return ((IntArrayTag) tag).getAsIntArray();
    }

    @Override
    public Object longs(long[] value) {
        return new LongArrayTag(value);
    }

    @Override
    public long[] longsOf(Object tag) {
        return ((LongArrayTag) tag).getAsLongArray();
    }
}
