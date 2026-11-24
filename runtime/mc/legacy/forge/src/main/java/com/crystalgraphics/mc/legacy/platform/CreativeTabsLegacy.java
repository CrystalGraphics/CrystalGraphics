package com.crystalgraphics.mc.legacy.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.mc.item.CgCreativeTabs;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
//? if >=1.12 {
import net.minecraft.util.NonNullList;
//?}

/**
 * {@link CgCreativeTabs} on Forge 1.8.9-1.12.2: the tab array, each tab's items as its own list fills them. A tab is
 * {@code minecraft:<label>}; the search tab and the survival inventory's, which list nothing, are left out. Client only.
 */
public final class CreativeTabsLegacy implements CgCreativeTabs.Host {

    public static final CreativeTabsLegacy INSTANCE = new CreativeTabsLegacy();

    private CreativeTabsLegacy() {
    }

    @Override
    public List<String> tabs() {
        List<String> out = new ArrayList<>();
        for (CreativeTabs tab : all()) {
            if (tab != null && !tab.hasSearchBar() && !fill(tab).isEmpty()) out.add("minecraft:" + tab.getTabLabel());
        }
        return out;
    }

    @Override
    public List<Object> items(String id) {
        for (CreativeTabs tab : all()) {
            if (tab != null && !tab.hasSearchBar() && id.equals("minecraft:" + tab.getTabLabel())) return new ArrayList<>(fill(tab));
        }
        return new ArrayList<>();
    }

    private static CreativeTabs[] all() {
        //? if >=1.10 {
        return CreativeTabs.CREATIVE_TAB_ARRAY;
        //?} else {
        /*return CreativeTabs.creativeTabArray;
        *///?}
    }

    private static List<ItemStack> fill(CreativeTabs tab) {
        //? if >=1.12 {
        NonNullList<ItemStack> list = NonNullList.create();
        tab.displayAllRelevantItems(list);
        //?} elif >=1.10 {
        /*List<ItemStack> list = new ArrayList<>();
        tab.displayAllRelevantItems(list);
        *///?} else {
        /*List<ItemStack> list = new ArrayList<>();
        tab.displayAllReleventItems(list);
        *///?}
        return list;
    }
}
