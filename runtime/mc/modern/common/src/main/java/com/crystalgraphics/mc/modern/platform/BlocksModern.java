package com.crystalgraphics.mc.modern.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}

/**
 * {@link CgBlocks} on the modern tree, both sides: saved by the game's own {@code NbtUtils.writeBlockState}, loaded by
 * hand so a block or property the game has not got is refused by name, where {@code readBlockState} would drop it.
 */
public final class BlocksModern implements CgBlocks.Host {

    public static final BlocksModern INSTANCE = new BlocksModern();

    private BlocksModern() {
    }

    @Override
    public boolean isState(Object o) {
        return o instanceof BlockState;
    }

    @Override
    public CgNbtCompound save(Object state) {
        CgNbtCompound saved = CgNbt.of(NbtUtils.writeBlockState((BlockState) state));
        // 26.3 writes {id, properties}; the value keeps every earlier version's {Name, Properties}
        rename(saved, "id", "Name");
        rename(saved, "properties", "Properties");
        return saved;
    }

    private static void rename(CgNbtCompound tag, String from, String to) {
        if (!tag.has(from) || tag.has(to)) return;
        tag.put(to, tag.get(from));
        tag.remove(from);
    }

    @Override
    public Object load(CgNbtCompound saved) {
        String name = saved.getString("Name");
        Block block = block(name);
        BlockState state = block.defaultBlockState();
        CgNbtCompound props = saved.getCompound("Properties");
        for (String key : props.keys()) {
            Property<?> p = block.getStateDefinition().getProperty(key);
            if (p == null) throw new IllegalArgumentException(name + " has no property '" + key + "' (" + names(block) + ")");
            state = with(state, p, props.getString(key), name);
        }
        return state;
    }

    private static Block block(String name) {
        //? if >=1.21.3 {
        /*if (!BuiltInRegistries.BLOCK.containsKey(ResourceIds.parse(name))) throw new IllegalArgumentException("no block " + name);
        return BuiltInRegistries.BLOCK.getValue(ResourceIds.parse(name));
        *///?} elif >=1.19.3 {
        if (!BuiltInRegistries.BLOCK.containsKey(ResourceIds.parse(name))) throw new IllegalArgumentException("no block " + name);
        return BuiltInRegistries.BLOCK.get(ResourceIds.parse(name));
        //?} else {
        /*// containsKey does not link on 1.14 to 1.16: an unknown id gets the default, whose own key is not the one asked
        Block b = Registry.BLOCK.get(ResourceIds.parse(name));
        if (!Registry.BLOCK.getKey(b).equals(ResourceIds.parse(name))) throw new IllegalArgumentException("no block " + name);
        return b;
        *///?}
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String value, String block) {
        Optional<T> v = p.getValue(value);
        if (!v.isPresent()) {
            List<String> allowed = new ArrayList<>();
            for (T t : p.getPossibleValues()) allowed.add(p.getName(t));
            throw new IllegalArgumentException(block + "'s " + p.getName() + " cannot be '" + value + "' (" + allowed + ")");
        }
        return state.setValue(p, v.get());
    }

    private static String names(Block block) {
        List<String> out = new ArrayList<>();
        for (Property<?> p : block.getStateDefinition().getProperties()) out.add(p.getName());
        return String.join(", ", out);
    }
}
