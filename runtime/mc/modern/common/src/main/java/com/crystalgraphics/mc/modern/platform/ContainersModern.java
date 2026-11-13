package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgContainers;
import com.crystalgraphics.platform.service.CgItemStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=1.14.3 {
import net.minecraft.world.inventory.AbstractFurnaceMenu;
//?}
//? if >=26.1 {
/*import net.minecraft.world.inventory.ContainerInput;
*///?} else {
import net.minecraft.world.inventory.ClickType;
//?}
import net.minecraft.world.item.ItemStack;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgContainers} on the modern tree: the client player's open menu read slot by slot, and a click made through
 * the game's own {@code MultiPlayerGameMode} call, which sends vanilla's packet. Client only, from
 * {@code PlatformServiceModern.installClient}.
 *
 * <ul>
 *   <li>The click is {@code handleInventoryMouseClick} to 1.21.11 and {@code handleContainerInput} from 26.1; both take
 *       the click type as an enum whose order is the protocol's.</li>
 *   <li>The cursor's stack is the menu's from 1.17.1, the player's inventory's before.</li>
 *   <li>A menu's kind is its {@code MenuType}'s registry name from 1.14.3; the player's own menu has none, and 1.13.2's
 *       menus are not typed, so it is {@code unknown} there.</li>
 *   <li>A stack's data is its component patch from 1.20.6 and its NBT before.</li>
 *   <li>A furnace's progress is whole pixels of its two textures to 1.20.1 and a fraction from 1.20.2.</li>
 * </ul>
 */
public final class ContainersModern implements CgContainers.Host {

    public static final ContainersModern INSTANCE = new ContainersModern();

    private ContainersModern() {
    }

    @Override
    public CgContainers.Menu menu() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        AbstractContainerMenu menu = player.containerMenu;
        List<CgContainers.Slot> slots = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) slots.add(new CgContainers.Slot(i, stack(menu.slots.get(i).getItem())));
        return new CgContainers.Menu(menu.containerId, type(player, menu), slots, stack(carried(player, menu)), data(menu));
    }

    @Override
    public void click(int containerId, int slot, int button, CgContainers.Click type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        //? if >=26.1 {
        /*mc.gameMode.handleContainerInput(containerId, slot, button, ContainerInput.values()[type.ordinal()], mc.player);
        *///?} else {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.values()[type.ordinal()], mc.player);
        //?}
    }

    @Override
    public void close() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu != player.inventoryMenu) player.closeContainer();
    }

    private static ItemStack carried(LocalPlayer player, AbstractContainerMenu menu) {
        //? if >=1.17.1 {
        return menu.getCarried();
        //?} else {
        /*return player.inventory.getCarried();
        *///?}
    }

    private static CgItemStack stack(ItemStack stack) {
        if (stack.isEmpty()) return CgItemStack.EMPTY;
        //? if >=1.19.3 {
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        //?} else {
        /*String item = Registry.ITEM.getKey(stack.getItem()).toString();
        *///?}
        //? if >=1.20.6 {
        /*int tag = stack.getComponentsPatch().hashCode();
        *///?} else {
        int tag = stack.getTag() == null ? 0 : stack.getTag().hashCode();
        //?}
        return new CgItemStack(item, stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(), tag, stack);
    }

    /** The menu's registry name; the player's own menu has no type, and neither does anything before 1.14.3. */
    private static String type(LocalPlayer player, AbstractContainerMenu menu) {
        if (menu == player.inventoryMenu) return CgContainers.Menu.PLAYER;
        //? if >=1.14.3 {
        //? if >=1.19.3 {
        Object key = BuiltInRegistries.MENU.getKey(menu.getType());
        //?} else {
        /*Object key = Registry.MENU.getKey(menu.getType());
        *///?}
        if (key != null) return key.toString();
        //?}
        return "unknown";
    }

    /** {@code burn} and {@code cook}, 0-1000, of a furnace, blast furnace or smoker. */
    private static Map<String, Integer> data(AbstractContainerMenu menu) {
        Map<String, Integer> data = new HashMap<>();
        //? if >=1.14.3 {
        if (menu instanceof AbstractFurnaceMenu) {
            AbstractFurnaceMenu furnace = (AbstractFurnaceMenu) menu;
            //? if >=1.20.2 {
            /*data.put("burn", (int) (furnace.getLitProgress() * 1000f));
            data.put("cook", (int) (furnace.getBurnProgress() * 1000f));
            *///?} else {
            data.put("burn", furnace.getLitProgress() * 1000 / 13);
            data.put("cook", furnace.getBurnProgress() * 1000 / 24);
            //?}
        }
        //?}
        return data;
    }
}
