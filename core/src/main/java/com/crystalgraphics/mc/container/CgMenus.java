package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Menus of a mod's own, as real menus of the game: their type registered, opened for a player by the server, their
 * slots synced, clicked and shift-clicked by the game's own code, and every rule asked of a {@link Logic}. What
 * CrystalGUI's {@code ContainerType} is made on; a mod can use it directly.
 *
 * <pre>{@code
 * // at mod construction, both sides: the type, and how a client builds its copy from the open's bytes
 * CgMenus.declare("mymod:grinder", (spec, inventory) -> new GrinderLogic(CgItemStorage.list(2), inventory));
 *
 * // the server, for a player
 * CgMenus.open(serverPlayer, "mymod:grinder", "Grinder", new GrinderLogic(blockEntityStorage), new byte[0]);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #declare} before the game's registries close: mod construction. A later one is refused with a log.</li>
 *   <li>The same {@link Logic} answers on both sides: the client predicts with it, as vanilla's client menu does,
 *       and the server decides. A rule only the server can answer (the block's state) answers {@code true} on the
 *       client; the server's refusal corrects the client as any mismatch is.</li>
 *   <li>Without a host (the harness, a test) nothing registers and {@link #open} answers false.</li>
 * </ul>
 */
public final class CgMenus {

    /** One open menu's rules, server or client. Slots are the menu's, in order. */
    public interface Logic {

        int size();

        /** Where slot {@code slot} keeps its stack, and at which index of it. */
        CgItemStorage storage(int slot);

        int index(int slot);

        /** Whether {@code stack} may be put in {@code slot}. */
        default boolean accepts(int slot, CgItemStack stack) {
            return true;
        }

        /** Whether the player may take {@code stack} out of {@code slot}. */
        default boolean takes(int slot, CgItemStack stack) {
            return true;
        }

        /** The most {@code slot} holds of one stack, at most the item's own. */
        default int maxStack(int slot) {
            return 64;
        }

        /**
         * Where a shift-click on {@code slot} sends its stack: slot ranges as {@code start, end, reverse} triples
         * ({@code end} exclusive, {@code reverse} 1 to fill from the end, as vanilla fills the player's inventory), tried
         * in order, each filled by the game's own merge (existing stacks first, then empty slots); empty for nowhere.
         */
        int[] quickMove(int slot);

        /** A click before the game runs it; false refuses it. */
        default boolean click(int slot, int button, ClickType type) {
            return true;
        }

        /** The menu's button {@code id} (the game's {@code clickMenuButton}); true when it did something. */
        default boolean button(int id) {
            return false;
        }

        /** Whether the menu may stay open; asked every tick on the server. */
        default boolean stillValid() {
            return true;
        }

        /** The game's menu made over it, with the id the game gave it ({@link CgContainers.Menu#id} on the client). */
        default void opened(int menuId) {
        }

        /** The menu closed, for whatever reason. The carried stack has gone back already. */
        default void closed() {
        }

        /** How many integers the game syncs for it (its data slots). */
        default int dataCount() {
            return 0;
        }

        /** Data slot {@code i}'s name, as the client's mirror reports it ({@link CgContainers.Menu#state}). */
        default String dataName(int i) {
            return "data" + i;
        }

        /** Data slot {@code i}: read on the server, written on the client. */
        default int data(int i) {
            return 0;
        }

        default void data(int i, int value) {
        }
    }

    /**
     * A declared type: how a client builds its copy of a menu from the bytes the server opened it with, over
     * {@code playerInventory} (the local player's own, which vanilla's client menus use for the player's slots) and
     * plain storage for the rest, which the server's sync fills.
     */
    @FunctionalInterface
    public interface Type {
        Logic client(byte[] spec, CgItemStorage playerInventory);
    }

    /** What a host answers. */
    public interface Host {

        /** A container of the game's with {@code size} empty slots; null without one. */
        @Nullable
        CgItemStorage create(int size);

        /** {@code hostContainer} as a storage (a player: its inventory); null when it is not one. */
        @Nullable
        CgItemStorage wrap(Object hostContainer);

        /** Type {@code id} was declared: a host whose game has no later registration point registers it now. */
        default void declared(String id) {
        }

        /** Opens {@code logic} as a menu of {@code type} for {@code player}; false when it cannot. Server thread. */
        boolean open(Object player, String type, String title, Logic logic, byte[] spec);

        /** Closes {@code player}'s menu {@code menuId} if it is the one open, telling the client; false otherwise. */
        default boolean close(Object player, int menuId) {
            return false;
        }

        Host NONE = new Host() {
            @Override public CgItemStorage create(int size) { return null; }
            @Override public CgItemStorage wrap(Object hostContainer) { return null; }
            @Override public boolean open(Object player, String type, String title, Logic logic, byte[] spec) { return false; }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:menus", Host.NONE);

    private static final Map<String, Type> TYPES = Collections.synchronizedMap(new LinkedHashMap<>());

    private CgMenus() {
    }

    static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Declares menu type {@code id} ({@code mymod:grinder}), registered by the host with the game's registries. */
    public static void declare(String id, Type type) {
        if (TYPES.putIfAbsent(id, type) != null) throw new IllegalStateException("menu type declared twice: " + id);
        host().declared(id);
    }

    /** The declared type {@code id}; null for one never declared. Hosts. */
    @Nullable
    public static Type type(String id) {
        return TYPES.get(id);
    }

    /** Every declared type's id, in declaration order. Hosts, at registration. */
    public static Set<String> declared() {
        synchronized (TYPES) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(TYPES.keySet()));
        }
    }

    /**
     * Opens {@code logic} for {@code player} (the host's server player) as a menu of {@code type}, titled
     * {@code title}; {@code spec} reaches the client's {@link Type#client}. False when there is no host, the type was
     * never declared, or this version has no way to open it. Server thread.
     */
    public static boolean open(Object player, String type, String title, Logic logic, byte[] spec) {
        if (!TYPES.containsKey(type)) throw new IllegalArgumentException("menu type never declared: " + type);
        return host().open(player, type, title, logic, spec);
    }

    /**
     * Closes {@code player}'s menu {@code menuId}, as its screen closing would, and tells its client: what ends a menu
     * the server opened with something else (an application's session). Nothing when another menu is open. Server
     * thread.
     *
     * <pre>{@code
     * CgMenus.close(player, menuId);    // the session ended
     * }</pre>
     */
    public static boolean close(Object player, int menuId) {
        return host().close(player, menuId);
    }
}
