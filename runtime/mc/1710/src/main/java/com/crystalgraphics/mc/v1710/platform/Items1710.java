package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgItems} on 1.7.10, both sides. {@code writeToNBT} saves the item as a number, so its id is replaced by the
 * registry name here and back on the way in; an empty slot is null.
 */
public final class Items1710 implements CgItems.Host {

    public static final Items1710 INSTANCE = new Items1710();

    private Items1710() {
    }

    @Override
    public boolean isStack(Object o) {
        return o instanceof ItemStack;
    }

    @Override
    public CgNbtCompound save(Object stack) {
        ItemStack s = (ItemStack) stack;
        CgNbtCompound saved = CgNbt.of(s.writeToNBT(new NBTTagCompound()));
        String name = Item.itemRegistry.getNameForObject(s.getItem());
        saved.put("id", name == null ? "minecraft:air" : name);
        return saved;
    }

    @Override
    public Object load(CgNbtCompound saved) {
        if (!saved.has("id")) return null;
        Object item = Item.itemRegistry.getObject(saved.getString("id"));
        if (!(item instanceof Item)) return null;
        ItemStack stack = new ItemStack((Item) item, saved.getInt("Count", 1), saved.getInt("Damage"));
        if (saved.has("tag")) {
            NBTTagCompound tag = CgNbt.toHost(saved.getCompound("tag"));
            stack.setTagCompound(tag);
        }
        return stack;
    }

    @Override
    public int maxDamage(Object stack) {
        return ((ItemStack) stack).getMaxDamage();
    }

    @Override
    public int maxStackSize(Object stack) {
        return ((ItemStack) stack).getMaxStackSize();
    }

    @Override
    public int stamp(Object stack) {
        ItemStack s = (ItemStack) stack;
        int h = 31 * Item.getIdFromItem(s.getItem()) + s.getItemDamage();
        return 31 * h + (s.getTagCompound() == null ? 0 : s.getTagCompound().hashCode());
    }

    @Override
    public CgItems.Shape shape() {
        return CgItems.Shape.LEGACY;
    }

    @Override
    public String name(int itemId) {
        Item item = Item.getItemById(itemId);
        return item == null ? null : Item.itemRegistry.getNameForObject(item);
    }

    /** 1.7.10 has no item tags: a tag matches nothing. */
    @Override
    public boolean inTag(int itemId, String tag) {
        return false;
    }
}
