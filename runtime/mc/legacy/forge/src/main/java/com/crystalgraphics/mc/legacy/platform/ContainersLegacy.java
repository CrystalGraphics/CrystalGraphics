package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.Capabilities;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.inventory.SlotFurnaceOutput;
import net.minecraft.entity.player.InventoryPlayer;
//? if >=1.9 {
import net.minecraft.inventory.ClickType;
//?}
import net.minecraft.item.ItemStack;

/**
 * {@link CgContainers} on Forge 1.8.9-1.12.2: the client player's open container read slot by slot, and actions made
 * through {@code PlayerControllerMP}, which sends vanilla's packets. Client only.
 *
 * <ul>
 *   <li>A click is {@code windowClick}; its type is an int mode on 1.8.9 and {@code ClickType} from 1.9, mapped by name,
 *       the protocol's order. A button is {@code sendEnchantPacket}: the enchanting table's, the only menu button these
 *       versions have. Rename and select-trade are not carried.</li>
 *   <li>An empty slot holds null to 1.10.2 and an empty stack from 1.11; the count is a field to 1.10.2 and
 *       {@code getCount()} from 1.11.</li>
 *   <li>These menus have no registry, so the kind is named from the class: the player's, a furnace, and a chest as
 *       {@code minecraft:generic_9xN} for its rows. Their state is the furnace's only.</li>
 *   <li>A slot's kind is the player's inventory by its index (hotbar 0-8, main to 35, armour to 39, the offhand 40), a
 *       crafting or furnace result, or the container's own. Its title is its inventory's name.</li>
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
        //? if >=1.10 {
        return new CgContainers.Layout(group, inContainer, slot.xPos, slot.yPos, kind(slot, inContainer), slot.getSlotStackLimit(),
                emptyIcon(slot));
        //?} else {
        /*return new CgContainers.Layout(group, inContainer, slot.xDisplayPosition, slot.yDisplayPosition, kind(slot, inContainer), slot.getSlotStackLimit(),
                emptyIcon(slot));
        *///?}
    }

    // GuiContainer.drawSlot's sprite for an empty slot, as its texture: ns:items/x is ns:textures/items/x.png
    private static String emptyIcon(Slot slot) {
        String name = slot.getSlotTexture();
        if (name == null) return null;
        int colon = name.indexOf(':');
        String ns = colon < 0 ? "minecraft" : name.substring(0, colon);
        return ns + ":textures/" + name.substring(colon + 1) + ".png";
    }

    private static SlotKind kind(Slot slot, int inContainer) {
        if (slot.inventory instanceof InventoryPlayer) {
            return inContainer < 9 ? SlotKind.HOTBAR : inContainer < 36 ? SlotKind.INVENTORY : inContainer < 40 ? SlotKind.ARMOR : SlotKind.OFFHAND;
        }
        if (slot.inventory instanceof InventoryCraftResult || slot instanceof SlotCrafting || slot instanceof SlotFurnaceOutput) {
            return SlotKind.RESULT;
        }
        return SlotKind.CONTAINER;
    }

    @Override
    public int stamp(int slot) {
        return stampOf(menu().getSlot(slot).getStack());
    }

    private static boolean empty(ItemStack stack) {
        //? if >=1.11 {
        return stack.isEmpty();
        //?} else {
        /*return stack == null || stack.stackSize <= 0;
        *///?}
    }

    private static int count(ItemStack stack) {
        //? if >=1.11 {
        return stack.getCount();
        //?} else {
        /*return stack.stackSize;
        *///?}
    }

    private static int stampOf(ItemStack stack) {
        return empty(stack) ? 0 : 31 * CgItems.stampOf(stack) + count(stack);
    }

    private static CgItemStack stack(ItemStack stack) {
        return empty(stack) ? CgItemStack.EMPTY : CgItemStack.of(stack);
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
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = player();
        if (player == null || mc.playerController == null) return false;
        if (action instanceof Action.Click) {
            Action.Click click = (Action.Click) action;
            //? if >=1.9 {
            mc.playerController.windowClick(id, click.slot(), click.button(), ClickType.valueOf(click.type().name()), player);
            //?} else {
            /*mc.playerController.windowClick(id, click.slot(), click.button(), click.type().ordinal(), player);
            *///?}
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
        return inventory instanceof InventoryPlayer ? "" : inventory.getDisplayName().getUnformattedText();
    }

    /** {@code burn} and {@code cook}, 0-1000, of a furnace: its fields are burn time, the fuel's, cook time, its total. */
    @Override
    public CgNbtCompound state() {
        CgNbtCompound out = new CgNbtCompound();
        Container menu = menu();
        if (menu instanceof ContainerFurnace) {
            IInventory furnace = menu.getSlot(0).inventory;
            int fuel = furnace.getField(1) == 0 ? 200 : furnace.getField(1);
            int total = furnace.getField(3);
            out.put("burn", furnace.getField(0) * 1000 / fuel);
            out.put("cook", total == 0 ? 0 : furnace.getField(2) * 1000 / total);
        }
        return out;
    }
}
