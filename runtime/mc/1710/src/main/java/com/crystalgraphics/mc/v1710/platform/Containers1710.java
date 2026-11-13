package com.crystalgraphics.mc.v1710.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.platform.service.CgContainers;
import com.crystalgraphics.platform.service.CgItemStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;

/**
 * {@link CgContainers} on 1.7.10: the client player's open container read slot by slot, and a click made through
 * {@code PlayerControllerMP.windowClick}, which sends vanilla's packet. Client only.
 *
 * <ul>
 *   <li>An empty slot holds null; the click type is an int mode, the protocol's order.</li>
 *   <li>Menus have no registry, so the kind is named from the class: the player's, a furnace, and a chest as
 *       {@code minecraft:generic_9xN} for its rows.</li>
 *   <li>A furnace's progress is its client tile entity's public fields.</li>
 * </ul>
 */
public final class Containers1710 implements CgContainers.Host {

    public static final Containers1710 INSTANCE = new Containers1710();

    private Containers1710() {
    }

    private static EntityPlayerSP player() {
        return Minecraft.getMinecraft().thePlayer;
    }

    @Override
    public CgContainers.Menu menu() {
        EntityPlayerSP player = player();
        if (player == null) return null;
        Container menu = player.openContainer;
        List<CgContainers.Slot> slots = new ArrayList<CgContainers.Slot>(menu.inventorySlots.size());
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            slots.add(new CgContainers.Slot(i, stack(menu.getSlot(i).getStack())));
        }
        return new CgContainers.Menu(menu.windowId, type(player, menu), slots, stack(player.inventory.getItemStack()), data(menu));
    }

    @Override
    public void click(int containerId, int slot, int button, CgContainers.Click type) {
        EntityPlayerSP player = player();
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc.playerController == null) return;
        mc.playerController.windowClick(containerId, slot, button, type.ordinal(), player);
    }

    @Override
    public void close() {
        EntityPlayerSP player = player();
        if (player != null && player.openContainer != player.inventoryContainer) player.closeScreen();
    }

    private static CgItemStack stack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return CgItemStack.EMPTY;
        String item = Item.itemRegistry.getNameForObject(stack.getItem());
        int tag = stack.getTagCompound() == null ? 0 : stack.getTagCompound().hashCode();
        return new CgItemStack(String.valueOf(item), stack.stackSize, stack.getItemDamage(), stack.getMaxDamage(), tag, stack);
    }

    private static String type(EntityPlayerSP player, Container menu) {
        if (menu == player.inventoryContainer || menu instanceof ContainerPlayer) return CgContainers.Menu.PLAYER;
        if (menu instanceof ContainerFurnace) return "minecraft:furnace";
        if (menu instanceof ContainerChest) {
            return "minecraft:generic_9x" + ((ContainerChest) menu).getLowerChestInventory().getSizeInventory() / 9;
        }
        return "unknown";
    }

    /** {@code burn} and {@code cook}, 0-1000, of a furnace. */
    private static Map<String, Integer> data(Container menu) {
        Map<String, Integer> data = new HashMap<String, Integer>();
        if (menu instanceof ContainerFurnace && menu.getSlot(0).inventory instanceof TileEntityFurnace) {
            TileEntityFurnace furnace = (TileEntityFurnace) menu.getSlot(0).inventory;
            int fuel = furnace.currentItemBurnTime == 0 ? 200 : furnace.currentItemBurnTime;
            data.put("burn", furnace.furnaceBurnTime * 1000 / fuel);
            data.put("cook", furnace.furnaceCookTime * 1000 / 200);
        }
        return data;
    }
}
