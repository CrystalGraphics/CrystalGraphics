package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgItems} on Forge 1.8.9-1.12.2, both sides: a stack's {@code writeToNBT} ({@code {id, Count, Damage, tag}}, the
 * id its registry name), and rebuilt by {@code loadItemStackFromNBT} to 1.10.2 and {@code new ItemStack(tag)} from 1.11.
 * An empty slot is null to 1.10.2 and {@code ItemStack.EMPTY} from 1.11.
 */
public final class ItemsLegacy implements CgItems.Host {

    public static final ItemsLegacy INSTANCE = new ItemsLegacy();

    private ItemsLegacy() {
    }

    @Override
    public boolean isStack(Object o) {
        return o instanceof ItemStack;
    }

    @Override
    public CgNbtCompound save(Object stack) {
        return CgNbt.of(((ItemStack) stack).writeToNBT(new NBTTagCompound()));
    }

    @Override
    public Object load(CgNbtCompound saved) {
        if (!saved.has("id")) {
            //? if >=1.11 {
            return ItemStack.EMPTY;
            //?} else {
            /*return null;
            *///?}
        }
        NBTTagCompound tag = CgNbt.toHost(saved);
        //? if >=1.11 {
        return new ItemStack(tag);
        //?} else {
        /*return ItemStack.loadItemStackFromNBT(tag);
        *///?}
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
        int h = 31 * Item.getIdFromItem(s.getItem()) + s.getMetadata();
        return 31 * h + (s.getTagCompound() == null ? 0 : s.getTagCompound().hashCode());
    }

    @Override
    public CgItems.Shape shape() {
        return CgItems.Shape.LEGACY;
    }

    @Override
    public String name(int itemId) {
        Item item = Item.getItemById(itemId);
        if (item == null) return null;
        //? if >=1.10 {
        Object name = Item.REGISTRY.getNameForObject(item);
        //?} else {
        /*Object name = Item.itemRegistry.getNameForObject(item);
        *///?}
        return name == null ? null : name.toString();
    }

    /** Forge 1.8.9-1.12.2 have no item tags: a tag matches nothing. */
    @Override
    public boolean inTag(int itemId, String tag) {
        return false;
    }
}
