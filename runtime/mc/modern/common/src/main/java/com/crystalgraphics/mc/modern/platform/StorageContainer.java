package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 {
import com.crystalgraphics.mc.container.CgItemStorage;
import com.crystalgraphics.mc.item.CgItemStack;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * A storage written by hand, as the game's container a menu needs: game stacks the menu changes in place, written back
 * whole at each {@code setChanged} (every slot change ends in one), and read again before each sync so a change made to
 * the storage outside the menu shows. Forge's {@code SlotItemHandler} keeps an {@code IItemHandler} the same way.
 */
final class StorageContainer extends SimpleContainer {

    private final CgItemStorage storage;
    private boolean reading;

    StorageContainer(CgItemStorage storage) {
        super(storage.size());
        this.storage = storage;
        refresh();
    }

    /** The storage's stacks where they differ from ours; what {@link HostMenu} asks before each sync. */
    void refresh() {
        reading = true;
        try {
            for (int i = 0; i < getContainerSize(); i++) {
                CgItemStack now = storage.get(i);
                if (!now.equals(MenusModern.stack(getItem(i)))) setItem(i, now.isEmpty() ? ItemStack.EMPTY : now.toHost());
            }
        } finally {
            reading = false;
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (reading) return;
        for (int i = 0; i < getContainerSize(); i++) storage.set(i, MenusModern.stack(getItem(i)));
        storage.changed();
    }
}
//?} else {
/*final class StorageContainer {

    private StorageContainer() {
    }
}
*///?}
