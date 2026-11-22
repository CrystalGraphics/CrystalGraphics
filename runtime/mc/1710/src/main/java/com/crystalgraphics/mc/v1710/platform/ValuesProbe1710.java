package com.crystalgraphics.mc.v1710.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * {@link CgValuesProbe.Host} on 1.7.10: {@code /give}'s text split as the command split it and its tag read by the
 * game's {@code JsonToNBT}; an entity made by {@code EntityList} from data read the same way, its id the game's name
 * ({@code Sheep}) with {@code minecraft:} taken off for the game and put back after. No block states: blocks have
 * metadata here. Faces are Forge's {@code ForgeDirection}.
 */
public final class ValuesProbe1710 implements CgValuesProbe.Host {

    public static final ValuesProbe1710 INSTANCE = new ValuesProbe1710();

    private ValuesProbe1710() {
    }

    @Override
    public String absent() {
        return DimensionManager.getWorld(0) == null ? "no server in this game (a multiplayer client)" : null;
    }

    @Override
    public CgItemStack gameItem(String text) {
        String[] parts = text.trim().split(" ", 4);
        Item item = (Item) Item.itemRegistry.getObject(parts[0]);
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
        tag.setString("id", tag.getString("id").replaceFirst("^minecraft:", ""));
        WorldServer level = DimensionManager.getWorld(0);
        // Made, never spawned: a picture's entity never joins the level
        Entity e = EntityList.createEntityFromNBT(tag, level);
        if (e == null) throw new IllegalArgumentException("the game made no entity from " + snbt);
        NBTTagCompound out = new NBTTagCompound();
        e.writeToNBTOptional(out);
        CgNbtCompound saved = CgNbt.of(out);
        saved.put("id", "minecraft:" + saved.getString("id"));
        return CgEntityData.fromNbt(saved);
    }

    @Override
    public String sheep() {
        return "Sheep";
    }

    @Override
    public List<String> faces() {
        List<String> out = new ArrayList<>();
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) out.add(d.name().toLowerCase(Locale.ROOT));
        return out;
    }

    @Override
    public Long packed(int x, int y, int z) {
        return null;
    }

    private static NBTTagCompound tag(String snbt) {
        try {
            return (NBTTagCompound) JsonToNBT.func_150315_a(snbt);
        } catch (NBTException e) {
            throw new IllegalArgumentException("the game's NBT parser refused " + snbt + ": " + e.getMessage(), e);
        }
    }
}
