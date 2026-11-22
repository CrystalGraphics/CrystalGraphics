package com.crystalgraphics.mc.block;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.CgCommandReader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import com.crystalgraphics.serialization.nbt.CgNbtValue;

/**
 * A block as a value: which block, its properties, and its block entity's data where it has one. What {@code /setblock}
 * takes, the same on every Minecraft version, and the game's own state only where you ask ({@link #toHost}).
 *
 * <pre>{@code
 * CgBlockState furnace = CgBlockState.parse("minecraft:furnace[facing=east,lit=true]");
 * String lit = furnace.property("lit");                              // "true"
 * CgBlockState out = furnace.with("lit", "false");                   // edits answer a new state
 *
 * CgBlockState chest = CgBlockState.parse("chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:apple\",count:3}]}");
 * CgNbtCompound items = chest.blockEntity();                         // the {Items:[...]} part
 *
 * CgBlockState here = CgBlockState.of(level.getBlockState(pos));     // a copy of the game's state
 * BlockState back = here.toHost();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #toString} is the command text, what {@link #parse} reads: properties sorted by name, the block
 *       entity's data last.</li>
 *   <li>Properties are the game's names and values as text ({@code facing=east}). Ones the state does not set are the
 *       block's defaults when {@link #toHost} builds it; one the block has not got makes it throw, naming it.</li>
 *   <li>1.7.10's blocks have metadata, not properties: there a state's one property is {@code meta}, 0 to 15.</li>
 *   <li>Equal when the block, the properties and the data are.</li>
 * </ul>
 */
public final class CgBlockState {

    public static final CgBlockState AIR = of("minecraft:air");

    /**
     * The game's saved form, {@code {Name, Properties}}, as structures and {@code NbtUtils} write a state, with the
     * block entity's data under {@code nbt} where there is any.
     */
    public static final CgCodec<CgBlockState> CODEC = CgCodecs.record(
            CgCodecs.field("Name", CgCodecs.STRING, CgBlockState::block),
            CgCodecs.optionalField("Properties", CgNbtCompound.CODEC, CgBlockState::propertiesTag, new CgNbtCompound()),
            CgCodecs.optionalField("nbt", CgNbtCompound.CODEC, CgBlockState::blockEntity, new CgNbtCompound()),
            (name, props, nbt) -> new CgBlockState(name, fromTag(props), nbt));

    private final String block;
    private final Map<String, String> properties;
    private final CgNbtCompound blockEntity;

    private CgBlockState(String block, Map<String, String> properties, CgNbtCompound blockEntity) {
        this.block = block;
        this.properties = Collections.unmodifiableMap(new TreeMap<>(properties));
        this.blockEntity = blockEntity.copy();
    }

    /** The block in its default state: {@code minecraft:stone}. */
    public static CgBlockState of(String block) {
        return new CgBlockState(block, Collections.emptyMap(), new CgNbtCompound());
    }

    public static CgBlockState of(String block, Map<String, String> properties) {
        return new CgBlockState(block, properties, new CgNbtCompound());
    }

    /** A copy of the game's block state; its block entity is not in it ({@link #withBlockEntity}). Throws outside Minecraft. */
    public static CgBlockState of(Object hostState) {
        return CgBlocks.of(hostState);
    }

    /** The game's own state, built from the block and properties; the block entity's data is not part of it. */
    public <V> V toHost() {
        return CgBlocks.toHost(this);
    }

    /**
     * From {@code /setblock}'s text: {@code id}, {@code id[name=value,...]}, then {@code {data}} for the block entity.
     * An id without a namespace is {@code minecraft:}'s.
     *
     * @throws com.crystalgraphics.serialization.CgCodecException where it is not that, naming the position
     */
    public static CgBlockState parse(String text) {
        CgCommandReader r = new CgCommandReader(text);
        r.skipSpaces();
        String block = r.readId();
        Map<String, String> props = new TreeMap<>();
        if (r.skip('[')) {
            r.skipSpaces();
            if (!r.skip(']')) {
                do {
                    r.skipSpaces();
                    String name = r.readWord();
                    r.skipSpaces();
                    r.expect('=');
                    r.skipSpaces();
                    if (props.put(name, r.readWord()) != null) throw r.error("each property once (" + name + " twice)");
                    r.skipSpaces();
                } while (r.skip(','));
                r.expect(']');
            }
        }
        CgNbtCompound data = new CgNbtCompound();
        if (r.peek() == '{') {
            CgNbtTag tag = r.readTag();
            if (!(tag instanceof CgNbtCompound)) throw r.error("the block entity's data as a compound");
            data = (CgNbtCompound) tag;
        }
        r.expectEnd();
        return new CgBlockState(block, props, data);
    }

    // ---- the value ----

    /** The block's registry name. */
    public String block() {
        return block;
    }

    /** Property name to value, sorted by name; empty for a block's default state as written. */
    public Map<String, String> properties() {
        return properties;
    }

    /** One property's value as text, or null where the state does not set it. */
    @Nullable
    public String property(String name) {
        return properties.get(name);
    }

    /** The block entity's data, a copy; empty where there is none. */
    public CgNbtCompound blockEntity() {
        return blockEntity.copy();
    }

    public boolean hasBlockEntity() {
        return !blockEntity.isEmpty();
    }

    public boolean isAir() {
        return block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");
    }

    // ---- edits ----

    public CgBlockState with(String property, String value) {
        Map<String, String> props = new TreeMap<>(properties);
        props.put(property, value);
        return new CgBlockState(block, props, blockEntity);
    }

    public CgBlockState without(String property) {
        if (!properties.containsKey(property)) return this;
        Map<String, String> props = new TreeMap<>(properties);
        props.remove(property);
        return new CgBlockState(block, props, blockEntity);
    }

    /** With the block entity's data; an empty compound removes it. */
    public CgBlockState withBlockEntity(CgNbtCompound data) {
        return new CgBlockState(block, properties, data);
    }

    // ---- comparing ----

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgBlockState)) return false;
        CgBlockState other = (CgBlockState) o;
        return block.equals(other.block) && properties.equals(other.properties) && blockEntity.equals(other.blockEntity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(block, properties, blockEntity);
    }

    /** {@code minecraft:furnace[facing=east,lit=true]{BurnTime:200s}}: what {@link #parse} reads. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(block);
        if (!properties.isEmpty()) {
            out.append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(e.getKey()).append('=').append(e.getValue());
            }
            out.append(']');
        }
        if (!blockEntity.isEmpty()) out.append(blockEntity);
        return out.toString();
    }

    private CgNbtCompound propertiesTag() {
        CgNbtCompound out = new CgNbtCompound();
        properties.forEach(out::put);
        return out;
    }

    private static Map<String, String> fromTag(CgNbtCompound tag) {
        Map<String, String> out = new TreeMap<>();
        for (String key : tag.keys()) {
            CgNbtTag v = tag.get(key);
            out.put(key, v instanceof CgNbtValue ? ((CgNbtValue) v).asString() : String.valueOf(v));
        }
        return out;
    }
}
