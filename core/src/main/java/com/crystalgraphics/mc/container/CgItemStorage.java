package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Item slots something owns: a block entity's inventory, a backpack's, an author's own list. What a menu's slots are
 * made over ({@link CgMenus}); any storage a mod keeps can be one.
 *
 * <pre>{@code
 * CgItemStorage grinder = CgItemStorage.create(2);              // a container of the game's, two slots
 * CgItemStorage chest = CgItemStorage.of(blockEntity);          // a block entity's own container, as it is
 * grinder.set(0, CgItemStack.of("minecraft:iron_ore", 8));
 * grinder.get(1).count();
 * }</pre>
 *
 * Written by hand, for slots kept anywhere else:
 *
 * <pre>{@code
 * CgItemStorage mine = new CgItemStorage() {
 *     public int size() { return stacks.size(); }
 *     public CgItemStack get(int i) { return stacks.get(i); }
 *     public void set(int i, CgItemStack stack) { stacks.set(i, stack); markDirty(); }
 * };
 * }</pre>
 *
 * <ul>
 *   <li>{@link #create} and {@link #of} keep the game's own stacks, which a menu changes in place as vanilla's do. A
 *       storage written by hand is read and written whole through {@link #set} instead: each change a menu makes is
 *       written back when the menu marks the slot changed.</li>
 *   <li>Server thread, as the game's containers are.</li>
 * </ul>
 */
public interface CgItemStorage {

    int size();

    CgItemStack get(int slot);

    void set(int slot, CgItemStack stack);

    /** Something in it changed: a block entity marks itself to be saved. */
    default void changed() {
    }

    /** The game's container behind it, when it is one; null for a storage written by hand. Hosts. */
    @Nullable
    default Object host() {
        return null;
    }

    /** {@code size} empty slots in a container of the game's; a plain list where no game is running (tests). */
    static CgItemStorage create(int size) {
        CgItemStorage made = CgMenus.host().create(size);
        return made != null ? made : list(size);
    }

    /**
     * The game's container {@code hostContainer}, as it is: a block entity, a minecart's, a mod's own; a player's
     * inventory for a player.
     */
    static CgItemStorage of(Object hostContainer) {
        CgItemStorage made = CgMenus.host().wrap(hostContainer);
        if (made == null) throw new IllegalArgumentException("not a container this host knows: " + hostContainer);
        return made;
    }

    /**
     * {@code size} slots of {@code backing} from a start that moves: a page of a large storage, its slots re-pointed
     * while a menu is open. The menu's slots stay; what they show changes, and the game's sync sends it.
     *
     * <pre>{@code
     * CgItemStorage.Window page = CgItemStorage.window(archive, 27);
     * page.start(27);                                   // the second page
     * }</pre>
     */
    static Window window(CgItemStorage backing, int size) {
        return new Window(backing, size);
    }

    /** A page of another storage; see {@link #window}. Its slots past the backing's end are empty and take nothing. */
    final class Window implements CgItemStorage {

        private final CgItemStorage backing;
        private final int size;
        private volatile int start;

        private Window(CgItemStorage backing, int size) {
            this.backing = backing;
            this.size = size;
        }

        /** Shows the backing's slots from {@code start}. */
        public Window start(int start) {
            if (start < 0) throw new IllegalArgumentException("start " + start);
            this.start = start;
            return this;
        }

        public int start() {
            return start;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public CgItemStack get(int slot) {
            int at = start + slot;
            return at < backing.size() ? backing.get(at) : CgItemStack.EMPTY;
        }

        @Override
        public void set(int slot, CgItemStack stack) {
            int at = start + slot;
            if (at < backing.size()) backing.set(at, stack);
        }

        @Override
        public void changed() {
            backing.changed();
        }
    }

    /** {@code size} empty slots in plain memory, kept nowhere: a client's copy of a menu, or a test's. */
    static CgItemStorage list(int size) {
        CgItemStack[] stacks = new CgItemStack[size];
        Arrays.fill(stacks, CgItemStack.EMPTY);
        return new CgItemStorage() {
            @Override
            public int size() {
                return stacks.length;
            }

            @Override
            public CgItemStack get(int slot) {
                return stacks[slot];
            }

            @Override
            public void set(int slot, CgItemStack stack) {
                stacks[slot] = stack == null ? CgItemStack.EMPTY : stack;
            }
        };
    }
}
