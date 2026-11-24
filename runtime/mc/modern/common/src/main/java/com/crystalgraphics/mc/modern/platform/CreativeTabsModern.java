package com.crystalgraphics.mc.modern.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.mc.item.CgCreativeTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
//? if >=1.19.3 {
import net.minecraft.world.item.CreativeModeTabs;
//?} else {
/*import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
*///?}
//? if >=1.20.1 {
import net.minecraft.core.registries.BuiltInRegistries;
//?}

/**
 * {@link CgCreativeTabs} on 1.13.2-26.3: {@code CreativeModeTabs}' tabs of items from 1.19.3, their contents rebuilt for
 * the world's features and the player's permissions as the creative screen rebuilds them; {@code CreativeModeTab.TABS}
 * and {@code fillItemList} before. Client only.
 */
public final class CreativeTabsModern implements CgCreativeTabs.Host {

    public static final CreativeTabsModern INSTANCE = new CreativeTabsModern();

    private CreativeTabsModern() {
    }

    @Override
    public List<String> tabs() {
        List<String> out = new ArrayList<>();
        //? if >=1.19.3 {
        List<CreativeModeTab> all = CreativeModeTabs.allTabs();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getType() == CreativeModeTab.Type.CATEGORY) out.add(id(all.get(i), i));
        }
        //?} else {
        /*for (CreativeModeTab tab : CreativeModeTab.TABS) if (tab != null) out.add("minecraft:" + tab.getRecipeFolderName());
        *///?}
        return out;
    }

    @Override
    public List<Object> items(String tab) {
        List<Object> out = new ArrayList<>();
        //? if >=1.19.3 {
        rebuild();
        List<CreativeModeTab> all = CreativeModeTabs.allTabs();
        for (int i = 0; i < all.size(); i++) {
            CreativeModeTab t = all.get(i);
            if (t.getType() == CreativeModeTab.Type.CATEGORY && id(t, i).equals(tab)) out.addAll(t.getDisplayItems());
        }
        //?} else {
        /*for (CreativeModeTab t : CreativeModeTab.TABS) {
            if (t == null || !tab.equals("minecraft:" + t.getRecipeFolderName())) continue;
            NonNullList<ItemStack> list = NonNullList.create();
            t.fillItemList(list);
            out.addAll(list);
        }
        *///?}
        return out;
    }

    //? if >=1.19.3 {
    // Registry names from 1.20.1; 1.19.3-1.19.4's tabs have none, so their place stands in
    private static String id(CreativeModeTab tab, int index) {
        //? if >=1.20.1 {
        Object key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        return key == null ? "tab" + index : key.toString();
        //?} else {
        /*return "tab" + index;
        *///?}
    }

    // As CreativeModeInventoryScreen does when it opens
    private static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean operator = mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks();
        //? if >=1.19.4 {
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), operator, mc.level.registryAccess());
        //?} else {
        /*CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), operator);
        *///?}
    }
    //?}
}
