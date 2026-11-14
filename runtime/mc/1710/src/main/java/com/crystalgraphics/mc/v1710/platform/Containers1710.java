package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.Capabilities;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.inventory.SlotFurnace;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.StatCollector;

/**
 * {@link CgContainers} on 1.7.10: the client player's open container read slot by slot, and actions made through
 * {@code PlayerControllerMP}, which sends vanilla's packets. Client only.
 *
 * <ul>
 *   <li>An empty slot holds null. A click is {@code windowClick} with the protocol's int mode, which is the order of
 *       {@code ClickType}; a button is {@code sendEnchantPacket}, the enchanting table's. Rename and select-trade are
 *       not carried.</li>
 *   <li>Menus have no registry, so the kind is named from the class: the player's, a furnace, and a chest as
 *       {@code minecraft:generic_9xN} for its rows. Their state is the furnace's only, from its client tile entity.</li>
 *   <li>A slot's kind is the player's inventory by its index (hotbar 0-8, main to 35, armour 36-39), a crafting or
 *       furnace result, or the container's own. Its title is its inventory's name, translated.</li>
 * </ul>
 */
public final class Containers1710 implements CgContainers.Host {

    public static final Containers1710 INSTANCE = new Containers1710();

    private Containers1710() {
    }

    private static EntityPlayerSP player() {
        return Minecraft.getMinecraft().thePlayer;
    }

    private static Container menu() {
        return player().openContainer;
    }

    @Override
    public boolean present() {
        return player() != null;
    }

    @Override
    public int id() {
        return menu().windowId;
    }

    @Override
    public int size() {
        return menu().inventorySlots.size();
    }

    @Override
    public CgContainers.Layout layout(int index) {
        Container menu = menu();
        Slot slot = menu.getSlot(index);
        int group = index;
        for (int i = 0; i < index; i++) {
            if (menu.getSlot(i).inventory == slot.inventory) {
                group = i;
                break;
            }
        }
        int inContainer = slot.getSlotIndex();
        return new CgContainers.Layout(group, inContainer, slot.xDisplayPosition, slot.yDisplayPosition, kind(slot, inContainer), slot.getSlotStackLimit());
    }

    private static SlotKind kind(Slot slot, int inContainer) {
        if (slot.inventory instanceof InventoryPlayer) {
            return inContainer < 9 ? SlotKind.HOTBAR : inContainer < 36 ? SlotKind.INVENTORY : SlotKind.ARMOR;
        }
        if (slot.inventory instanceof InventoryCraftResult || slot instanceof SlotCrafting || slot instanceof SlotFurnace) {
            return SlotKind.RESULT;
        }
        return SlotKind.CONTAINER;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.stackSize <= 0;
    }

    private static int stampOf(ItemStack stack) {
        return empty(stack) ? 0 : 31 * CgItems.stampOf(stack) + stack.stackSize;
    }

    private static CgItemStack stack(ItemStack stack) {
        return empty(stack) ? CgItemStack.EMPTY : CgItemStack.of(stack);
    }

    @Override
    public int stamp(int slot) {
        return stampOf(menu().getSlot(slot).getStack());
    }

    @Override
    public CgItemStack stack(int slot) {
        return stack(menu().getSlot(slot).getStack());
    }

    @Override
    public boolean canTake(int slot) {
        return menu().getSlot(slot).canTakeStack(player());
    }

    @Override
    public int carriedStamp() {
        return stampOf(player().inventory.getItemStack());
    }

    @Override
    public CgItemStack carried() {
        return stack(player().inventory.getItemStack());
    }

    @Override
    public boolean mayPlace(int slot, CgItemStack stack) {
        ItemStack host = stack.toHost();
        return menu().getSlot(slot).isItemValid(host);
    }

    @Override
    public boolean act(int id, Action action) {
        EntityPlayerSP player = player();
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc.playerController == null) return false;
        if (action instanceof Action.Click) {
            Action.Click click = (Action.Click) action;
            mc.playerController.windowClick(id, click.slot(), click.button(), click.type().ordinal(), player);
            return true;
        }
        if (action instanceof Action.Button) {
            mc.playerController.sendEnchantPacket(id, ((Action.Button) action).id());
            return true;
        }
        if (action instanceof Action.Close) {
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            return true;
        }
        return false;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, false, true);
    }

    @Override
    public String type() {
        Container menu = menu();
        if (menu == player().inventoryContainer || menu instanceof ContainerPlayer) return CgContainers.Menu.PLAYER;
        if (menu instanceof ContainerFurnace) return "minecraft:furnace";
        if (menu instanceof ContainerChest) {
            return "minecraft:generic_9x" + ((ContainerChest) menu).getLowerChestInventory().getSizeInventory() / 9;
        }
        return "unknown";
    }

    @Override
    public String title() {
        Container menu = menu();
        if (menu == player().inventoryContainer || menu.inventorySlots.isEmpty()) return "";
        IInventory inventory = menu.getSlot(0).inventory;
        if (inventory instanceof InventoryPlayer) return "";
        String name = inventory.getInventoryName();
        return inventory.hasCustomInventoryName() ? name : StatCollector.translateToLocal(name);
    }

    /** {@code burn} and {@code cook}, 0-1000, of a furnace. */
    @Override
    public CgNbtCompound state() {
        CgNbtCompound out = new CgNbtCompound();
        Container menu = menu();
        if (menu instanceof ContainerFurnace && menu.getSlot(0).inventory instanceof TileEntityFurnace) {
            TileEntityFurnace furnace = (TileEntityFurnace) menu.getSlot(0).inventory;
            int fuel = furnace.currentItemBurnTime == 0 ? 200 : furnace.currentItemBurnTime;
            out.put("burn", furnace.furnaceBurnTime * 1000 / fuel);
            out.put("cook", furnace.furnaceCookTime * 1000 / 200);
        }
        return out;
    }
}
