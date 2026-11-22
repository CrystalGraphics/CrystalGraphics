package com.crystalgraphics.mc.entity;

import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.mc.CgCommandReader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * An entity as data: its type and what {@code /summon} gives it. Nothing in it is the game's; a host makes the entity
 * from it where one is drawn, and it never joins a level.
 *
 * <pre>{@code
 * CgEntityData sheep = CgEntityData.parse("minecraft:sheep{Color:2b}");       // /summon's type and data
 * CgEntityData still = sheep.withData(d -> d.putByte("NoAI", 1));             // edits answer new data
 * CgNbtCompound saved = sheep.toNbt();                                         // {id:"minecraft:sheep",Color:2b}
 * CgEntityData back = CgEntityData.fromNbt(saved);                             // as the game saves an entity
 * }</pre>
 *
 * <ul>
 *   <li>{@link #toString} is the command text, what {@link #parse} reads.</li>
 *   <li>The data is the game's own keys for that version ({@code Color}, {@code CustomName}); a key the type does not
 *       read is ignored by the game, as {@code /summon} ignores it.</li>
 *   <li>Equal when the type and the data are.</li>
 * </ul>
 */
public final class CgEntityData {

    /** As the game saves an entity: {@code {id, ...data}}. */
    public static final CgCodec<CgEntityData> CODEC = CgCodecs.xmap(CgNbtCompound.CODEC, CgEntityData::fromNbt,
            CgEntityData::toNbt);

    private final String type;
    private final CgNbtCompound data;

    private CgEntityData(String type, CgNbtCompound data) {
        this.type = type;
        this.data = data.copy();
        this.data.remove("id");
    }

    /** {@code type} with no data: what {@code /summon minecraft:pig} makes. */
    public static CgEntityData of(String type) {
        return new CgEntityData(type, new CgNbtCompound());
    }

    public static CgEntityData of(String type, CgNbtCompound data) {
        return new CgEntityData(type, data);
    }

    /** From {@code /summon}'s type and data: {@code minecraft:sheep} or {@code sheep{Color:2b}}. */
    public static CgEntityData parse(String text) {
        CgCommandReader r = new CgCommandReader(text);
        r.skipSpaces();
        String type = r.readId();
        CgNbtCompound data = new CgNbtCompound();
        if (r.peek() == '{') {
            CgNbtTag tag = r.readTag();
            if (!(tag instanceof CgNbtCompound)) throw r.error("the entity's data as a compound");
            data = (CgNbtCompound) tag;
        }
        r.expectEnd();
        return new CgEntityData(type, data);
    }

    /** From the game's saved form, {@code {id, ...}}. */
    public static CgEntityData fromNbt(CgNbtCompound saved) {
        String id = saved.getString("id");
        if (id.isEmpty()) throw new CgCodecException("an entity's data needs its id: " + saved);
        return new CgEntityData(id, saved);
    }

    /** As the game saves the entity: the data with {@code id} first. */
    public CgNbtCompound toNbt() {
        CgNbtCompound out = new CgNbtCompound();
        out.put("id", type);
        for (String key : data.keys()) out.put(key, data.get(key).copy());
        return out;
    }

    /** The entity type's registry name. */
    public String type() {
        return type;
    }

    /** The data, a copy, without the id. */
    public CgNbtCompound data() {
        return data.copy();
    }

    /** Edits a copy of the data; the answer holds the result. */
    public CgEntityData withData(Consumer<CgNbtCompound> edit) {
        CgNbtCompound d = data.copy();
        edit.accept(d);
        return new CgEntityData(type, d);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgEntityData)) return false;
        CgEntityData other = (CgEntityData) o;
        return type.equals(other.type) && data.equals(other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, data);
    }

    /** {@code minecraft:sheep{Color:2b}}: what {@link #parse} reads. */
    @Override
    public String toString() {
        return data.isEmpty() ? type : type + data;
    }
}
