package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's open container as data, and the player's actions into it: the platform half of every screen made of
 * slots (the inventory, a chest, a furnace, an anvil, a merchant). Client only, on the client thread; a host answers
 * it through {@link Host}.
 *
 * <pre>{@code
 * ContainerMirror mirror = CgContainers.mirror();                   // one per screen
 * ContainerMirror.Update update = mirror.poll();                     // every frame: null unless something changed
 * if (update != null) for (int i : update.slots()) show(i, update.menu().slot(i).stack());
 *
 * CgContainers.Menu menu = mirror.menu();                            // the open container, else the player's inventory
 * menu.slot(36).stack();                                             // the hotbar's first slot, a CgItemStack
 * menu.state().getInt("cost");                                       // an anvil's level cost: per kind, MenuTypes
 *
 * CgContainers.act(menu.id(), new Action.Click(20, 0, ClickType.QUICK_MOVE));    // shift-click slot 20
 * CgContainers.act(menu.id(), new Action.Rename("Excalibur"));                  // the anvil's name
 * }</pre>
 *
 * <ul>
 *   <li><b>An action goes through the game's own interaction call</b>, so the server sees the packet vanilla's own
 *       screen would send: hoppers, sorting mods, recipe viewers and anti-cheat see an ordinary player. Nothing here
 *       changes a slot on the client: the server's answer does.</li>
 *   <li>An action names the menu it was made against, {@link Menu#id}. The game drops one for a menu that has since
 *       closed.</li>
 *   <li>The menu's {@link Menu#slots} are in the game's own order, the container's own slots first and the player's
 *       after, as vanilla's menus lay them out; the player's own inventory menu is {@link Menu#PLAYER}: 0 result,
 *       1-4 crafting, 5-8 armour (head first), 9-35 main, 36-44 hotbar, 45 offhand.</li>
 *   <li>Without a host (the harness, a server) there is no menu: {@link #present} is false and an action does nothing.</li>
 * </ul>
 *
 * A host, from its client install:
 *
 * <pre>{@code
 * CgPlatform.provide(CgContainers.HOST, ContainersModern.INSTANCE);
 * }</pre>
 */
public final class CgContainers {

    /** The slot a click on nothing, outside the window, names: it drops the cursor's stack. */
    public static final int OUTSIDE = -999;

    /**
     * Where a slot is and what it is, fixed while its menu is open.
     *
     * @param container      which container of the menu holds it: the player's inventory is one, a chest another, a result its own
     * @param containerIndex its place in that container
     * @param x              its place in the menu's picture, in vanilla's GUI units from the panel's corner
     * @param kind           what the slot is
     * @param maxStack       the most its container holds in it
     * @param emptyIcon      the picture vanilla draws in it while it is empty (an armour slot's outline), as a texture
     *                       {@code CgIO} reads ({@code minecraft:textures/item/empty_armor_slot_helmet.png}), so a
     *                       pack's shows; null for none
     */
    public record Layout(int container, int containerIndex, int x, int y, SlotKind kind, int maxStack,
                         @Nullable String emptyIcon) {
    }

    /** One slot at {@code index} of the menu, what it holds, and whether the player may take it. */
    public record Slot(int index, CgItemStack stack, Layout layout, boolean canTake) {

        public SlotKind kind() {
            return layout.kind();
        }

        public Slot withStack(CgItemStack stack, boolean canTake) {
            return new Slot(index, stack, layout, canTake);
        }
    }

    /**
     * The open container at one moment.
     *
     * @param id      the menu's window id: {@code 0} is the player's own inventory
     * @param type    the menu's kind, {@code namespace:path} as vanilla names it ({@code minecraft:player},
     *                {@code minecraft:furnace}, {@code minecraft:generic_9x3}); {@code unknown} for a menu the host
     *                cannot name
     * @param title   the container's name as the player reads it; empty when the game has not opened a screen of it
     * @param slots   every slot, by index
     * @param carried what the cursor holds
     * @param state   the menu's own values, the schema of its kind ({@link MenuTypes}): {@code burn} and {@code cook}
     *                (0-1000) of a furnace, {@code cost} of an anvil, {@code offers} of a merchant. Empty for a menu with none
     */
    public record Menu(int id, String type, String title, List<Slot> slots, CgItemStack carried, CgNbtCompound state) {

        /** The type of the player's own inventory. */
        public static final String PLAYER = "minecraft:player";

        public Menu {
            slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }

        /** The slot at {@code index}. */
        public Slot slot(int index) {
            return slots.get(index);
        }

        /** Whether this is the player's own inventory rather than a container opened over it. */
        public boolean isPlayerInventory() {
            return id == 0;
        }
    }

    /**
     * What a host answers, one class per era, {@code Containers<Era>}: primitives only, no copy of a stack that did not
     * change. Called on the client thread. A slot index is the menu's.
     */
    public interface Host {

        /** Whether there is a player and so a menu. */
        boolean present();

        /** The menu's window id. */
        int id();

        /** The menu's registry name, {@link Menu#PLAYER} for the player's own, {@code unknown} when the host cannot name it. */
        String type();

        /** The container's name, empty when none is known. */
        String title();

        /** How many slots the menu has. */
        int size();

        Layout layout(int slot);

        /** A hash of the slot's item, its data and its count, taken without a copy ({@link com.crystalgraphics.mc.item.CgItems#stampOf}). */
        int stamp(int slot);

        CgItemStack stack(int slot);

        /** Whether the player may take the slot's stack. */
        boolean canTake(int slot);

        /** {@link #stamp} of what the cursor carries. */
        int carriedStamp();

        CgItemStack carried();

        /** Whether the slot accepts {@code stack}, the game's own {@code mayPlace}. */
        boolean mayPlace(int slot, CgItemStack stack);

        /** The menu's own values, {@link Menu#state}; a fresh empty compound for a kind with none. */
        CgNbtCompound state();

        /** Carries {@code action} out against the menu {@code id}; false for one this version cannot carry. */
        boolean act(int id, Action action);

        Capabilities capabilities();

        Host NONE = new Host() {
            @Override public boolean present() { return false; }
            @Override public int id() { return 0; }
            @Override public String type() { return "unknown"; }
            @Override public String title() { return ""; }
            @Override public int size() { return 0; }
            @Override public Layout layout(int slot) { throw new IndexOutOfBoundsException(slot); }
            @Override public int stamp(int slot) { return 0; }
            @Override public CgItemStack stack(int slot) { return CgItemStack.EMPTY; }
            @Override public boolean canTake(int slot) { return false; }
            @Override public int carriedStamp() { return 0; }
            @Override public CgItemStack carried() { return CgItemStack.EMPTY; }
            @Override public boolean mayPlace(int slot, CgItemStack stack) { return false; }
            @Override public CgNbtCompound state() { return new CgNbtCompound(); }
            @Override public boolean act(int id, Action action) { return false; }
            @Override public Capabilities capabilities() { return Capabilities.NONE; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:containers", Host.NONE);

    private CgContainers() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Whether there is a player, and so a menu. */
    public static boolean present() {
        return host().present();
    }

    /** A mirror of the open menu for one screen: ask it for what changed. */
    public static ContainerMirror mirror() {
        return new ContainerMirror(host());
    }

    /** The open menu in full, as a snapshot, or null with no player. It copies every slot: a screen uses {@link #mirror}. */
    @Nullable
    public static Menu menu() {
        ContainerMirror mirror = new ContainerMirror(host());
        mirror.poll();
        return mirror.menu();
    }

    /** Whether the slot of the open menu accepts {@code stack}. */
    public static boolean mayPlace(int slot, CgItemStack stack) {
        return host().mayPlace(slot, stack);
    }

    /** Carries {@code action} out against the menu {@code containerId}, through the game's own call; false for one this version cannot carry. */
    public static boolean act(int containerId, Action action) {
        return host().act(containerId, action);
    }

    /** What this version's host can carry. */
    public static Capabilities capabilities() {
        return host().capabilities();
    }
}
