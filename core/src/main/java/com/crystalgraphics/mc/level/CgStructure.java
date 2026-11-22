package com.crystalgraphics.mc.level;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * A piece of a Minecraft world as a value: its size, its blocks (each a {@link CgBlockState}, block entity data
 * included) and its entities, as a structure block saves it. Placed into a pocket level to be drawn; read and written
 * as vanilla's structure files, so a structure saved in game loads here and back.
 *
 * <pre>{@code
 * CgStructure tower = CgStructure.load("mymod:tower");   // data/mymod/structure/tower.nbt in a mod's jar, any thread
 *
 * CgStructure ring = CgStructure.builder(7, 2, 7)
 *         .fill(0, 0, 0, 6, 0, 6, CgBlockState.parse("minecraft:stone_bricks"))
 *         .block(3, 1, 3, CgBlockState.parse("minecraft:chest[facing=south]{Items:[]}"))
 *         .entity(1.5, 1, 1.5, CgEntityData.parse("minecraft:armor_stand{ShowArms:1b}"))
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * ring.forEachBlock((x, y, z, state) -> count[0]++);      // every block it holds, air included
 * CgBlockState at = ring.block(3, 1, 3);                   // null where it holds nothing (structure void)
 * try (OutputStream out = Files.newOutputStream(file)) {  // as a structure block writes it
 *     CgNbt.writeBinary(out, ring.toNbt());
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Immutable; any thread. A builder is not shared between threads.</li>
 *   <li>Loaded as written: a file saved by another Minecraft version is not upgraded, and a block or entity the game
 *       lacks fails where it is drawn, not here.</li>
 *   <li>Of a file with several palettes (a shipwreck's), the first.</li>
 * </ul>
 */
public final class CgStructure {

    /** What {@link #forEachBlock} hands each block. */
    @FunctionalInterface
    public interface BlockVisitor {
        void block(int x, int y, int z, CgBlockState state);
    }

    /** An entity it holds, at a position inside it, in blocks from its corner. */
    public record Entity(double x, double y, double z, CgEntityData data) {
        public Entity {
            Objects.requireNonNull(data, "data");
        }
    }

    private final int sizeX, sizeY, sizeZ;
    // Cells in the order they were placed, each a packed position and its state; the map finds one by position
    private final int[] cells;
    private final CgBlockState[] states;
    private final Map<Integer, Integer> byCell;
    private final List<Entity> entities;
    private final int dataVersion;

    private CgStructure(int sizeX, int sizeY, int sizeZ, Map<Integer, CgBlockState> blocks, List<Entity> entities,
                        int dataVersion) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new int[blocks.size()];
        this.states = new CgBlockState[blocks.size()];
        this.byCell = new HashMap<>(blocks.size() * 2);
        int i = 0;
        for (Map.Entry<Integer, CgBlockState> e : blocks.entrySet()) {
            cells[i] = e.getKey();
            states[i] = e.getValue();
            byCell.put(e.getKey(), i++);
        }
        this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
        this.dataVersion = dataVersion;
    }

    /** An empty structure of this size, to fill: blocks set last win. */
    public static Builder builder(int sizeX, int sizeY, int sizeZ) {
        return new Builder(sizeX, sizeY, sizeZ);
    }

    /**
     * The structure file {@code namespace:path} from the mods' jars: {@code data/<namespace>/structure/<path>.nbt}, or
     * {@code structures/} as before 1.21. Throws where none has it.
     */
    public static CgStructure load(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
        for (String folder : new String[]{"structure", "structures"}) {
            String resource = "data/" + namespace + "/" + folder + "/" + path + ".nbt";
            InputStream in = open(resource);
            if (in == null) continue;
            try (InputStream s = in) {
                return read(s);
            } catch (IOException e) {
                throw new UncheckedIOException("structure " + id + " (" + resource + ")", e);
            }
        }
        throw new IllegalArgumentException("no structure " + id + ": none at data/" + namespace + "/structure/" + path
                + ".nbt or structures/");
    }

    /** A structure file's contents, gzipped or not. The caller closes the stream. */
    public static CgStructure read(InputStream in) throws IOException {
        return fromNbt(CgNbt.readBinary(in));
    }

    /** A structure file's compound: {@code size}, {@code palette} (or {@code palettes}), {@code blocks}, {@code entities}. */
    public static CgStructure fromNbt(CgNbtCompound saved) {
        CgNbtList size = saved.getList("size");
        if (size.size() != 3) throw new IllegalArgumentException("a structure's size is three ints, not " + size);
        Builder b = builder(size.getInt(0), size.getInt(1), size.getInt(2));
        b.dataVersion = saved.getInt("DataVersion", 0);
        CgNbtList paletteTag = saved.has("palette") ? saved.getList("palette")
                : saved.getList("palettes").isEmpty() ? new CgNbtList() : saved.getList("palettes").getList(0);
        List<CgBlockState> palette = new ArrayList<>(paletteTag.size());
        for (CgNbtTag t : paletteTag) palette.add(state(t.asCompound()));
        for (CgNbtTag t : saved.getList("blocks")) {
            CgNbtCompound block = t.asCompound();
            CgNbtList pos = block.getList("pos");
            int index = block.getInt("state");
            if (index < 0 || index >= palette.size()) throw new IllegalArgumentException("a block's state " + index
                    + " is outside the palette of " + palette.size());
            CgBlockState state = palette.get(index);
            if (block.has("nbt")) state = state.withBlockEntity(block.getCompound("nbt"));
            b.block(pos.getInt(0), pos.getInt(1), pos.getInt(2), state);
        }
        for (CgNbtTag t : saved.getList("entities")) {
            CgNbtCompound entity = t.asCompound();
            CgNbtList pos = entity.getList("pos");
            b.entity(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), CgEntityData.fromNbt(entity.getCompound("nbt")));
        }
        return b.build();
    }

    /** As a structure block writes it: one palette, a block's data under {@code nbt}, the version it was read with. */
    public CgNbtCompound toNbt() {
        CgNbtCompound out = new CgNbtCompound();
        out.put("size", CgNbtList.of(sizeX, sizeY, sizeZ));
        Map<CgBlockState, Integer> palette = new LinkedHashMap<>();
        CgNbtList blocks = new CgNbtList();
        for (int i = 0; i < cells.length; i++) {
            CgBlockState state = states[i];
            CgBlockState bare = state.hasBlockEntity() ? state.withBlockEntity(new CgNbtCompound()) : state;
            Integer index = palette.computeIfAbsent(bare, k -> palette.size());
            int cell = cells[i];
            CgNbtCompound block = new CgNbtCompound()
                    .put("pos", CgNbtList.of(x(cell), y(cell), z(cell)))
                    .put("state", index);
            if (state.hasBlockEntity()) block.put("nbt", state.blockEntity());
            blocks.add(block);
        }
        CgNbtList paletteTag = new CgNbtList();
        for (CgBlockState state : palette.keySet()) {
            CgNbtCompound entry = new CgNbtCompound().put("Name", state.block());
            if (!state.properties().isEmpty()) {
                CgNbtCompound props = new CgNbtCompound();
                state.properties().forEach(props::put);
                entry.put("Properties", props);
            }
            paletteTag.add(entry);
        }
        CgNbtList entityTags = new CgNbtList();
        for (Entity e : entities) {
            entityTags.add(new CgNbtCompound()
                    .put("pos", new CgNbtList().add(e.x()).add(e.y()).add(e.z()))
                    .put("blockPos", CgNbtList.of((int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z())))
                    .put("nbt", e.data().toNbt()));
        }
        out.put("palette", paletteTag).put("blocks", blocks).put("entities", entityTags);
        if (dataVersion != 0) out.put("DataVersion", dataVersion);
        return out;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** How many blocks it holds, air included; a cell it does not hold is the world's, untouched as it is placed. */
    public int blockCount() {
        return cells.length;
    }

    /** The state at {@code (x, y, z)}, its block entity's data with it; null where it holds nothing or out of bounds. */
    @Nullable
    public CgBlockState block(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
        Integer i = byCell.get(cell(x, y, z));
        return i == null ? null : states[i];
    }

    /** Every block it holds, in the order they were set. */
    public void forEachBlock(BlockVisitor visitor) {
        for (int i = 0; i < cells.length; i++) visitor.block(x(cells[i]), y(cells[i]), z(cells[i]), states[i]);
    }

    public List<Entity> entities() {
        return entities;
    }

    /** The {@code DataVersion} it was read with: the Minecraft version that saved it; 0 where built or unstated. */
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgStructure)) return false;
        CgStructure s = (CgStructure) o;
        return sizeX == s.sizeX && sizeY == s.sizeY && sizeZ == s.sizeZ && blocks().equals(s.blocks())
                && entities.equals(s.entities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sizeX, sizeY, sizeZ, blocks(), entities);
    }

    @Override
    public String toString() {
        return "CgStructure[" + sizeX + "x" + sizeY + "x" + sizeZ + ", " + cells.length + " blocks, " + entities.size()
                + " entities]";
    }

    private Map<Integer, CgBlockState> blocks() {
        Map<Integer, CgBlockState> out = new TreeMap<>();
        for (int i = 0; i < cells.length; i++) out.put(cells[i], states[i]);
        return out;
    }

    private int cell(int x, int y, int z) {
        return x + sizeX * (z + sizeZ * y);
    }

    private int x(int cell) {
        return cell % sizeX;
    }

    private int y(int cell) {
        return cell / (sizeX * sizeZ);
    }

    private int z(int cell) {
        return cell / sizeX % sizeZ;
    }

    private static CgBlockState state(CgNbtCompound entry) {
        Map<String, String> props = new TreeMap<>();
        CgNbtCompound p = entry.getCompound("Properties");
        for (String key : p.keys()) props.put(key, p.getString(key));
        return CgBlockState.of(entry.getString("Name"), props);
    }

    @Nullable
    private static InputStream open(String resource) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream in = context == null ? null : context.getResourceAsStream(resource);
        return in != null ? in : CgStructure.class.getClassLoader().getResourceAsStream(resource);
    }

    /** A structure being built: blocks and entities set in any order, positions inside its size. */
    public static final class Builder {
        private final int sizeX, sizeY, sizeZ;
        private final Map<Integer, CgBlockState> blocks = new LinkedHashMap<>();
        private final List<Entity> entities = new ArrayList<>();
        private int dataVersion;

        private Builder(int sizeX, int sizeY, int sizeZ) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("a structure's size is positive, not "
                    + sizeX + "x" + sizeY + "x" + sizeZ);
            if ((long) sizeX * sizeY * sizeZ > Integer.MAX_VALUE) throw new IllegalArgumentException("a structure of "
                    + sizeX + "x" + sizeY + "x" + sizeZ + " is too large");
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        /** {@code state} at {@code (x, y, z)}, replacing what was there. */
        public Builder block(int x, int y, int z, CgBlockState state) {
            Objects.requireNonNull(state, "state");
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) throw new IllegalArgumentException(
                    "(" + x + ", " + y + ", " + z + ") is outside " + sizeX + "x" + sizeY + "x" + sizeZ);
            int cell = x + sizeX * (z + sizeZ * y);
            blocks.remove(cell);   // set last, so it is also placed last
            blocks.put(cell, state);
            return this;
        }

        /** {@code state} in every cell of the box between two corners, both included, in either order. */
        public Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, CgBlockState state) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) block(x, y, z, state);
                }
            }
            return this;
        }

        /** An entity standing at {@code (x, y, z)}, in blocks from the corner. */
        public Builder entity(double x, double y, double z, CgEntityData data) {
            entities.add(new Entity(x, y, z, data));
            return this;
        }

        public CgStructure build() {
            return new CgStructure(sizeX, sizeY, sizeZ, blocks, entities, dataVersion);
        }
    }
}
