package com.crystalgraphics.mc.legacy.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.probe.CgValuesProbe;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * {@link CgValuesProbe.Host} on legacy Forge, 1.8.9 to 1.12.2. There is no command parser before 1.13: an item's
 * {@code /give} text is split as {@code /give} split it and its tag read by the game's {@code JsonToNBT}; an entity is
 * made by {@code EntityList} from data read the same way. Before 1.11 an entity's id is its name ({@code Sheep}): a
 * value's type keeps {@code minecraft:} in front of it, taken off here for the game and put back after.
 */
public final class ValuesProbeLegacy implements CgValuesProbe.Host {

    public static final ValuesProbeLegacy INSTANCE = new ValuesProbeLegacy();

    private ValuesProbeLegacy() {
    }

    @Override
    public String absent() {
        return DimensionManager.getWorld(0) == null ? "no server in this game (a multiplayer client)" : null;
    }

    // /give <item> [count] [damage] [tag]: the game's own split, its tag by the game's own parser
    @Override
    public CgItemStack gameItem(String text) {
        String[] parts = text.trim().split(" ", 4);
        Item item = Item.getByNameOrId(parts[0]);
        if (item == null) throw new IllegalArgumentException("the game has no item " + parts[0]);
        int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        int damage = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        ItemStack stack = new ItemStack(item, count, damage);
        if (parts.length > 3) stack.setTagCompound(tag(parts[3]));
        return CgItemStack.of(stack);
    }

    @Override
    public CgBlockState gameBlock(String text) {
        return null;
    }

    @Override
    public CgEntityData gameEntity(String snbt) {
        NBTTagCompound tag = tag(snbt);
        //? if <1.11 {
        /*tag.setString("id", tag.getString("id").replaceFirst("^minecraft:", ""));
        *///?}
        WorldServer level = DimensionManager.getWorld(0);
        // Made, never spawned: a picture's entity never joins the level
        Entity e = EntityList.createEntityFromNBT(tag, level);
        if (e == null) throw new IllegalArgumentException("the game made no entity from " + snbt);
        NBTTagCompound out = new NBTTagCompound();
        e.writeToNBTOptional(out);
        CgNbtCompound saved = CgNbt.of(out);
        //? if <1.11 {
        /*saved.put("id", "minecraft:" + saved.getString("id"));
        *///?}
        return CgEntityData.fromNbt(saved);
    }

    @Override
    public String sheep() {
        //? if <1.11 {
        /*return "Sheep";
        *///?} else {
        return "minecraft:sheep";
        //?}
    }

    @Override
    public List<String> faces() {
        List<String> out = new ArrayList<>();
        for (EnumFacing f : EnumFacing.VALUES) out.add(f.getName());
        return out;
    }

    // Legacy's BlockPos.toLong packs y in the middle: not the 1.14 packing the value matches
    @Override
    public Long packed(int x, int y, int z) {
        return null;
    }

    private static NBTTagCompound tag(String snbt) {
        try {
            return JsonToNBT.getTagFromJson(snbt);
        } catch (NBTException e) {
            throw new IllegalArgumentException("the game's NBT parser refused " + snbt + ": " + e.getMessage(), e);
        }
    }
}
