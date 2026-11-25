package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 {
import com.crystalgraphics.mc.container.CgItemStorage;
import com.crystalgraphics.mc.container.CgMenus;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
/*import net.minecraft.world.inventory.ContainerInput;
*///?} else {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu of a mod's own ({@link CgMenus}): a real menu of the game whose slots sit over {@link CgItemStorage}s and whose
 * every rule is its {@link CgMenus.Logic}'s. Clicks, drags, sync and the carried stack's return are the game's own code.
 * Shift-click asks the logic where to send a stack and moves it with the game's own merge, as vanilla's menus do.
 */
public final class HostMenu extends AbstractContainerMenu {

    final CgMenus.Logic logic;
    private final List<StorageContainer> written = new ArrayList<>();

    HostMenu(MenuType<?> type, int windowId, CgMenus.Logic logic) {
        super(type, windowId);
        this.logic = logic;
        logic.opened(windowId);
        Map<CgItemStorage, Container> containers = new IdentityHashMap<>();
        for (int i = 0; i < logic.size(); i++) {
            Container container = containers.computeIfAbsent(logic.storage(i), storage -> {
                Container made = MenusModern.container(storage);
                if (made instanceof StorageContainer) written.add((StorageContainer) made);
                return made;
            });
            addSlot(new LogicSlot(container, logic.index(i), i));
        }
        for (int i = 0; i < logic.dataCount(); i++) {
            int at = i;
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return logic.data(at);
                }

                @Override
                public void set(int value) {
                    logic.data(at, value);
                }
            });
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        int[] ranges = logic.quickMove(index);
        boolean moved = false;
        for (int r = 0; r + 2 < ranges.length && !stack.isEmpty(); r += 3) {
            moved |= moveItemStackTo(stack, ranges[r], ranges[r + 1], ranges[r + 2] != 0);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == before.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return before;
    }

    //? if >=26.1 {
    /*@Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (logic.click(slot, button, MenusModern.clickType(input.name()))) super.clicked(slot, button, input, player);
    }
    *///?} elif >=1.17.1 {
    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (logic.click(slot, button, MenusModern.clickType(type.name()))) super.clicked(slot, button, type, player);
    }
    //?} else {
    /*@Override
    public ItemStack clicked(int slot, int button, ClickType type, Player player) {
        return logic.click(slot, button, MenusModern.clickType(type.name())) ? super.clicked(slot, button, type, player) : ItemStack.EMPTY;
    }
    *///?}

    @Override
    public boolean clickMenuButton(Player player, int id) {
        return logic.button(id);
    }

    @Override
    public boolean stillValid(Player player) {
        return logic.stillValid();
    }

    @Override
    public void broadcastChanges() {
        for (StorageContainer each : written) each.refresh();
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        logic.closed();
    }

    /** A slot whose rules are the logic's, at its place {@code at} in the menu. */
    private final class LogicSlot extends Slot {

        private final int at;

        LogicSlot(Container container, int index, int at) {
            super(container, index, 0, 0);
            this.at = at;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return logic.accepts(at, MenusModern.stack(stack));
        }

        @Override
        public boolean mayPickup(Player player) {
            return logic.takes(at, MenusModern.stack(getItem()));
        }

        @Override
        public int getMaxStackSize() {
            return logic.maxStack(at);
        }
    }
}
//?} else {
/*final class HostMenu {

    private HostMenu() {
    }
}
*///?}
