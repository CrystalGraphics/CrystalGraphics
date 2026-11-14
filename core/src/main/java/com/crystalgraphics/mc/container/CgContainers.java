package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's open container as data, and clicks into it: the platform half of every screen made of slots (the
 * inventory, a chest, a furnace). Client only, on the client thread; a host answers it through {@link Host}.
 *
 * <pre>{@code
 * CgContainers.Menu menu = CgContainers.menu();                 // the open container, else the player's inventory; null with no player
 * CgItemStack held = menu.carried();                                // what the cursor carries
 * CgItemStack first = menu.slot(36).stack();                        // the hotbar's first slot, by InventoryMenu index
 *
 * CgContainers.click(menu.id(), 36, 0, CgContainers.Click.PICKUP);              // left-click it
 * CgContainers.click(menu.id(), 20, 0, CgContainers.Click.QUICK_MOVE);          // shift-click
 * CgContainers.click(menu.id(), 20, 2, CgContainers.Click.SWAP);                // number key 3: swap with hotbar slot 3
 * CgContainers.dragStart(menu.id(), 0);                                         // a left-button drag over three slots
 * CgContainers.dragAdd(menu.id(), 10, 0); CgContainers.dragAdd(menu.id(), 11, 0); CgContainers.dragAdd(menu.id(), 12, 0);
 * CgContainers.dragEnd(menu.id(), 0);
 * }</pre>
 *
 * <ul>
 *   <li><b>A click goes through the game's own interaction call</b>, so the server sees the packet vanilla's own screen
 *       would send: hoppers, sorting mods, recipe viewers and anti-cheat see an ordinary player. Nothing here sends a
 *       packet of ours, and nothing here changes a slot on the client: the server's answer does.</li>
 *   <li>A click names the menu it was made against, {@link Menu#id}. The game drops one for a menu that has since
 *       closed.</li>
 *   <li>A snapshot is a copy of what the slots held when it was taken; ask again for the next. It allocates, so ask on a
 *       tick or a change, not per frame.</li>
 *   <li>The menu's {@link Menu#slots} are in the game's own order, the container's own slots first and the player's
 *       after, as vanilla's menus lay them out; the player's own inventory menu is {@link Menu#PLAYER}: 0 result,
 *       1-4 crafting, 5-8 armour (head first), 9-35 main, 36-44 hotbar, 45 offhand.</li>
 *   <li>Without a host (the harness, a server) there is no menu: {@link #menu()} is null and a click does nothing.</li>
 * </ul>
 *
 * A host, from its client install:
 *
 * <pre>{@code
 * CgPlatform.provide(CgContainers.HOST, ContainersModern.INSTANCE);
 * }</pre>
 */
public final class CgContainers {

    /** What a click does; the numbering is vanilla's click type, the protocol's {@code mode}. */
    public enum Click {
        /** Pick up, put down, swap with the cursor, or split: button {@code 0} left, {@code 1} right. */
        PICKUP,
        /** Shift-click: move the stack to the menu's other side. */
        QUICK_MOVE,
        /** A number key or the offhand key: swap with a hotbar slot, button {@code 0-8}, or {@code 40} for the offhand. */
        SWAP,
        /** Middle-click in creative: a full stack onto the cursor. */
        CLONE,
        /** The drop key: button {@code 0} one, {@code 1} the whole stack. */
        THROW,
        /** A drag across slots, in three clicks: see {@link #dragStart}. */
        QUICK_CRAFT,
        /** Double-click: gather every matching stack onto the cursor. */
        PICKUP_ALL
    }

    /** The slot a click on nothing, outside the window, names: it drops the cursor's stack. */
    public static final int OUTSIDE = -999;

    /** One slot at {@code index} of the menu, and what it holds. */
    public record Slot(int index, CgItemStack stack) {
    }

    /**
     * The open container at one moment.
     *
     * @param id      the menu's window id: {@code 0} is the player's own inventory
     * @param type    the menu's kind, {@code namespace:path} as vanilla names it ({@code minecraft:player},
     *                {@code minecraft:furnace}, {@code minecraft:generic_9x3}); {@code unknown} for a menu the host
     *                cannot name
     * @param slots   every slot, by index
     * @param carried what the cursor holds
     * @param data    the menu's progress values by name, the schema of its kind, in whole numbers: {@code burn} and
     *                {@code cook} (0-1000) of a furnace, blast furnace and smoker. Empty for a menu with none
     */
    public record Menu(int id, String type, List<Slot> slots, CgItemStack carried, Map<String, Integer> data) {

        /** The type of the player's own inventory. */
        public static final String PLAYER = "minecraft:player";

        public Menu {
            slots = Collections.unmodifiableList(new ArrayList<>(slots));
            data = Collections.unmodifiableMap(new HashMap<>(data));
        }

        /** The slot at {@code index}. */
        public Slot slot(int index) {
            return slots.get(index);
        }

        /** The value {@code name} of {@link #data}, or {@code 0}. */
        public int data(String name) {
            return data.getOrDefault(name, 0);
        }

        /** Whether this is the player's own inventory rather than a container opened over it. */
        public boolean isPlayerInventory() {
            return id == 0;
        }
    }

    /** What a host answers, one class per era, {@code Containers<Era>}. Called on the client thread. */
    public interface Host {
        /** The open menu: the container the player has open, else their inventory; null with no player. */
        Menu menu();

        /** Makes the click through the game's own interaction call. */
        void click(int containerId, int slot, int button, Click type);

        /** Closes the open container as the player closing its screen does, telling the server; nothing if none is open. */
        void close();

        Host NONE = new Host() {
            @Override public Menu menu() { return null; }
            @Override public void click(int containerId, int slot, int button, Click type) { }
            @Override public void close() { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:containers", Host.NONE);

    private CgContainers() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The open menu, as a snapshot, or null with no player. */
    public static Menu menu() {
        return host().menu();
    }

    /** One click into the menu {@code containerId}, through the game's own interaction call. */
    public static void click(int containerId, int slot, int button, Click type) {
        host().click(containerId, slot, button, type);
    }

    /** Closes the open container, as the player does, so the server learns it. */
    public static void close() {
        host().close();
    }

    /**
     * Starts a drag across slots with mouse {@code button} ({@code 0} left: split evenly, {@code 1} right: one each,
     * {@code 2} middle, creative). Then one {@link #dragAdd} per slot crossed, then {@link #dragEnd}.
     */
    public static void dragStart(int containerId, int button) {
        click(containerId, OUTSIDE, quickCraft(0, button), Click.QUICK_CRAFT);
    }

    /** Adds {@code slot} to the drag begun with {@code button}. */
    public static void dragAdd(int containerId, int slot, int button) {
        click(containerId, slot, quickCraft(1, button), Click.QUICK_CRAFT);
    }

    /** Ends the drag begun with {@code button}: the stack is shared over the slots added. */
    public static void dragEnd(int containerId, int button) {
        click(containerId, OUTSIDE, quickCraft(2, button), Click.QUICK_CRAFT);
    }

    /** Vanilla's packing of a drag's stage (0 start, 1 slot, 2 end) and mouse button into one number. */
    static int quickCraft(int stage, int button) {
        return (stage & 3) | ((button & 3) << 2);
    }
}
