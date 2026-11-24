package com.crystalgraphics.mc.v1710.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.mc.item.CgCreativeTabs;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

/**
 * {@link CgCreativeTabs} on 1.7.10: the tab array, each tab's items as its own list fills them. A tab is
 * {@code minecraft:<label>}; the search tab and the survival inventory's, which list nothing, are left out. Client only.
 */
public final class CreativeTabs1710 implements CgCreativeTabs.Host {

    public static final CreativeTabs1710 INSTANCE = new CreativeTabs1710();

    private CreativeTabs1710() {
    }

    @Override
    public List<String> tabs() {
        List<String> out = new ArrayList<>();
        for (CreativeTabs tab : CreativeTabs.creativeTabArray) {
            if (tab != null && !tab.hasSearchBar() && !fill(tab).isEmpty()) out.add("minecraft:" + tab.getTabLabel());
        }
        return out;
    }

    @Override
    public List<Object> items(String id) {
        for (CreativeTabs tab : CreativeTabs.creativeTabArray) {
            if (tab != null && !tab.hasSearchBar() && id.equals("minecraft:" + tab.getTabLabel())) return new ArrayList<>(fill(tab));
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> fill(CreativeTabs tab) {
        List<ItemStack> list = new ArrayList<>();
        tab.displayAllReleventItems(list);
        return list;
    }
}
