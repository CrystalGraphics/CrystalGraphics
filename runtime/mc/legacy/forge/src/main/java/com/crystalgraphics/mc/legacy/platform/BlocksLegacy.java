package com.crystalgraphics.mc.legacy.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

/**
 * {@link CgBlocks} on legacy Forge, 1.8.9 to 1.12.2: {@code IBlockState}'s properties as {@code {Name, Properties}},
 * each value by its name. A value is matched among the property's allowed ones, which every legacy version has
 * ({@code parseValue} is 1.10's).
 */
public final class BlocksLegacy implements CgBlocks.Host {

    public static final BlocksLegacy INSTANCE = new BlocksLegacy();

    private BlocksLegacy() {
    }

    @Override
    public boolean isState(Object o) {
        return o instanceof IBlockState;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CgNbtCompound save(Object o) {
        IBlockState state = (IBlockState) o;
        CgNbtCompound props = new CgNbtCompound();
        // Raw: 1.8.9's map is ImmutableMap<IProperty, Comparable>
        for (Object entry : state.getProperties().entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            IProperty p = (IProperty) e.getKey();
            props.put(p.getName(), p.getName((Comparable) e.getValue()));
        }
        CgNbtCompound out = new CgNbtCompound().put("Name", nameOf(state.getBlock()));
        if (!props.isEmpty()) out.put("Properties", props);
        return out;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object load(CgNbtCompound saved) {
        String name = saved.getString("Name");
        Block block = blockOf(new ResourceLocation(name));
        if (block == null) throw new IllegalArgumentException("no block " + name);
        IBlockState state = block.getDefaultState();
        CgNbtCompound props = saved.getCompound("Properties");
        for (String key : props.keys()) {
            IProperty p = property(state, key);
            if (p == null) throw new IllegalArgumentException(name + " has no property '" + key + "' (" + names(state) + ")");
            String value = props.getString(key);
            Comparable match = null;
            List<String> allowed = new ArrayList<>();
            for (Object v : p.getAllowedValues()) {
                String n = p.getName((Comparable) v);
                allowed.add(n);
                if (n.equals(value)) match = (Comparable) v;
            }
            if (match == null) throw new IllegalArgumentException(name + "'s " + key + " cannot be '" + value + "' (" + allowed + ")");
            state = state.withProperty(p, match);
        }
        return state;
    }

    private static IProperty<?> property(IBlockState state, String name) {
        for (IProperty<?> p : state.getProperties().keySet()) if (p.getName().equals(name)) return p;
        return null;
    }

    private static String names(IBlockState state) {
        List<String> out = new ArrayList<>();
        for (IProperty<?> p : state.getProperties().keySet()) out.add(p.getName());
        return String.join(", ", out);
    }

    private static String nameOf(Block block) {
        //? if >=1.10 {
        return String.valueOf(Block.REGISTRY.getNameForObject(block));
        //?} else {
        /*return String.valueOf(Block.blockRegistry.getNameForObject(block));
        *///?}
    }

    // Null where the registry has none: its own get answers air
    private static Block blockOf(ResourceLocation id) {
        //? if >=1.10 {
        return Block.REGISTRY.containsKey(id) ? Block.REGISTRY.getObject(id) : null;
        //?} else {
        /*return Block.blockRegistry.containsKey(id) ? Block.blockRegistry.getObject(id) : null;
        *///?}
    }
}
