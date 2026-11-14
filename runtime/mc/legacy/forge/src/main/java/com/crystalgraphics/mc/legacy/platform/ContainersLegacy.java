package com.crystalgraphics.mc.legacy.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.item.CgItemStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.IInventory;
//? if >=1.9 {
import net.minecraft.inventory.ClickType;
//?}
import net.minecraft.item.ItemStack;

/**
 * {@link CgContainers} on Forge 1.8.9-1.12.2: the client player's open container read slot by slot, and a click made
 * through {@code PlayerControllerMP.windowClick}, which sends vanilla's packet. Client only.
 *
 * <ul>
 *   <li>The click type is an int mode on 1.8.9 and {@code ClickType} from 1.9; both are the protocol's order.</li>
 *   <li>An empty slot holds null to 1.10.2 and an empty stack from 1.11; the count is a field to 1.10.2 and
 *       {@code getCount()} from 1.11.</li>
 *   <li>These menus have no registry, so the kind is named from the class: the player's, a furnace, and a chest as
 *       {@code minecraft:generic_9xN} for its rows.</li>
 *   <li>A furnace's progress is its inventory's fields: burn time, the fuel's total, cook time, its total.</li>
 * </ul>
 */
public final class ContainersLegacy implements CgContainers.Host {

    public static final ContainersLegacy INSTANCE = new ContainersLegacy();

    private ContainersLegacy() {
    }

    private static EntityPlayerSP player() {
        //? if >=1.10 {
        return Minecraft.getMinecraft().player;
        //?} else {
        /*return Minecraft.getMinecraft().thePlayer;
        *///?}
    }

    @Override
    public CgContainers.Menu menu() {
        EntityPlayerSP player = player();
        if (player == null) return null;
        Container menu = player.openContainer;
        List<CgContainers.Slot> slots = new ArrayList<>(menu.inventorySlots.size());
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            slots.add(new CgContainers.Slot(i, stack(menu.inventorySlots.get(i).getStack())));
        }
        return new CgContainers.Menu(menu.windowId, type(player, menu), slots, stack(player.inventory.getItemStack()), data(menu));
    }

    @Override
    public void click(int containerId, int slot, int button, CgContainers.Click type) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = player();
        if (player == null || mc.playerController == null) return;
        //? if >=1.9 {
        mc.playerController.windowClick(containerId, slot, button, ClickType.values()[type.ordinal()], player);
        //?} else {
        /*mc.playerController.windowClick(containerId, slot, button, type.ordinal(), player);
        *///?}
    }

    @Override
    public void close() {
        EntityPlayerSP player = player();
        if (player != null && player.openContainer != player.inventoryContainer) player.closeScreen();
    }

    private static CgItemStack stack(ItemStack stack) {
        //? if >=1.11 {
        if (stack.isEmpty()) return CgItemStack.EMPTY;
        //?} else {
        /*if (stack == null || stack.stackSize <= 0) return CgItemStack.EMPTY;
        *///?}
        return CgItemStack.of(stack);
    }

    private static String type(EntityPlayerSP player, Container menu) {
        if (menu == player.inventoryContainer || menu instanceof ContainerPlayer) return CgContainers.Menu.PLAYER;
        if (menu instanceof ContainerFurnace) return "minecraft:furnace";
        if (menu instanceof ContainerChest) {
            return "minecraft:generic_9x" + ((ContainerChest) menu).getLowerChestInventory().getSizeInventory() / 9;
        }
        return "unknown";
    }

    /** {@code burn} and {@code cook}, 0-1000, of a furnace: its fields are burn time, the fuel's, cook time, its total. */
    private static Map<String, Integer> data(Container menu) {
        Map<String, Integer> data = new HashMap<>();
        if (menu instanceof ContainerFurnace) {
            IInventory furnace = menu.getSlot(0).inventory;
            int fuel = furnace.getField(1) == 0 ? 200 : furnace.getField(1);
            int total = furnace.getField(3);
            data.put("burn", furnace.getField(0) * 1000 / fuel);
            data.put("cook", total == 0 ? 0 : furnace.getField(2) * 1000 / total);
        }
        return data;
    }
}
