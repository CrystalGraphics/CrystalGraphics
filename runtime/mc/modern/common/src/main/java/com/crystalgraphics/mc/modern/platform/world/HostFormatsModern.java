package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
//? if >=1.21.5 {
/*import net.minecraft.nbt.NumericTag;
*///?}

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * {@link CgHostFormats} over Minecraft's tags, 1.13.2 to 26.3. Provided by {@code NetworkModern} on both sides.
 *
 * <ul>
 *   <li>Strings and numbers are made by {@code valueOf} from 1.15.2, by constructor before; read through
 *       {@code getAsString}/{@code getAs*} to 1.21.4, and from 1.21.5, where tags are records, {@code value()} and
 *       {@code NumericTag.box()}.</li>
 *   <li>A compound's keys are {@code getAllKeys()} to 1.21.4, {@code keySet()} after; 1.13.2's put is still
 *       obfuscated, {@code a(String, Tag)}.</li>
 * </ul>
 */
public final class HostFormatsModern implements CgHostFormats {

    @Override
    public boolean present() {
        return true;
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
    @Nullable
    public Collection<String> keys(Object tag) {
        if (!(tag instanceof CompoundTag)) return null;
        //? if >=1.21.5 {
        /*return ((CompoundTag) tag).keySet();
        *///?} else {
        return ((CompoundTag) tag).getAllKeys();
        //?}
    }

    @Override
    @Nullable
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
    @Nullable
    public List<?> elements(Object tag) {
        return tag instanceof ListTag ? (ListTag) tag : null;
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
    @Nullable
    public String stringOf(Object tag) {
        if (!(tag instanceof StringTag)) return null;
        //? if >=1.21.5 {
        /*return ((StringTag) tag).value();
        *///?} else {
        return ((StringTag) tag).getAsString();
        //?}
    }

    @Override
    public Object number(Number value) {
        //? if >=1.15.2 {
        if (value instanceof Byte) return ByteTag.valueOf((Byte) value);
        if (value instanceof Short) return ShortTag.valueOf((Short) value);
        if (value instanceof Integer) return IntTag.valueOf((Integer) value);
        if (value instanceof Long) return LongTag.valueOf((Long) value);
        if (value instanceof Float) return FloatTag.valueOf((Float) value);
        return DoubleTag.valueOf(value.doubleValue());
        //?} else {
        /*if (value instanceof Byte) return new ByteTag((Byte) value);
        if (value instanceof Short) return new ShortTag((Short) value);
        if (value instanceof Integer) return new IntTag((Integer) value);
        if (value instanceof Long) return new LongTag((Long) value);
        if (value instanceof Float) return new FloatTag((Float) value);
        return new DoubleTag(value.doubleValue());
        *///?}
    }

    @Override
    @Nullable
    public Number numberOf(Object tag) {
        //? if >=1.21.5 {
        /*return tag instanceof NumericTag ? ((NumericTag) tag).box() : null;
        *///?} else {
        if (tag instanceof ByteTag) return ((ByteTag) tag).getAsByte();
        if (tag instanceof ShortTag) return ((ShortTag) tag).getAsShort();
        if (tag instanceof IntTag) return ((IntTag) tag).getAsInt();
        if (tag instanceof LongTag) return ((LongTag) tag).getAsLong();
        if (tag instanceof FloatTag) return ((FloatTag) tag).getAsFloat();
        if (tag instanceof DoubleTag) return ((DoubleTag) tag).getAsDouble();
        return null;
        //?}
    }

    @Override
    public Object bytes(byte[] value) {
        return new ByteArrayTag(value);
    }

    @Override
    @Nullable
    public byte[] bytesOf(Object tag) {
        return tag instanceof ByteArrayTag ? ((ByteArrayTag) tag).getAsByteArray() : null;
    }
}
