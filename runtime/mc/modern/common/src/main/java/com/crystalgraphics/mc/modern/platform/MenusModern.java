package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 {
import com.crystalgraphics.mc.container.CgItemStorage;
import com.crystalgraphics.mc.container.CgMenus;
import com.crystalgraphics.mc.container.ClickType;
import com.crystalgraphics.mc.item.CgItemStack;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
//? if <1.19 {
/*import net.minecraft.network.chat.TextComponent;
*///?}

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * {@link CgMenus} on the modern hosts: each declared type a real {@code MenuType} whose menus are {@link HostMenu}s, and
 * storages over the game's own containers. What a loader alone can do (a type whose open carries bytes, and that open)
 * is its {@link Loader}, set by its mod class; without one nothing registers and nothing opens.
 */
public final class MenusModern implements CgMenus.Host {

    public static final MenusModern INSTANCE = new MenusModern();

    /** A loader's half: a menu type whose open carries the spec's bytes, and that open. */
    public interface Loader {

        /** Type {@code id}'s menu type; a loader whose call also registers it registers it here. */
        MenuType<HostMenu> type(String id, Factory factory);

        /** Type {@code id} was declared: a loader with no registry event registers it now (Fabric). */
        default void declared(String id) {
        }

        boolean open(ServerPlayer player, MenuProvider provider, byte[] spec);
    }

    /** A client's menu, made from the open's bytes. */
    @FunctionalInterface
    public interface Factory {
        HostMenu create(int windowId, Inventory inventory, byte[] spec);
    }

    @Nullable
    private static volatile Loader loader;
    private static final Map<String, MenuType<HostMenu>> TYPES = new ConcurrentHashMap<>();

    private MenusModern() {
    }

    /** The loader's half; its mod class, at construction. */
    public static void loader(Loader half) {
        loader = half;
    }

    /** Makes a type for each declared one not yet made and hands it to the loader's registration: (id, type). */
    public static void register(BiConsumer<String, MenuType<HostMenu>> out) {
        Loader half = loader;
        if (half == null) return;
        for (String id : CgMenus.declared()) {
            if (TYPES.containsKey(id)) continue;
            MenuType<HostMenu> type = half.type(id, (windowId, inventory, spec) ->
                    new HostMenu(TYPES.get(id), windowId, CgMenus.type(id).client(spec, storage(inventory))));
            TYPES.put(id, type);
            out.accept(id, type);
        }
    }

    @Override
    public void declared(String id) {
        Loader half = loader;
        if (half != null) half.declared(id);
    }

    /** Every type made, for the loader's screen registration. */
    public static Collection<MenuType<HostMenu>> types() {
        return Collections.unmodifiableCollection(TYPES.values());
    }

    @Override
    public CgItemStorage create(int size) {
        return storage(new SimpleContainer(size));
    }

    @Override
    @Nullable
    public CgItemStorage wrap(Object hostContainer) {
        if (hostContainer instanceof Player) {
            //? if >=1.17 {
            return storage(((Player) hostContainer).getInventory());
            //?} else {
            /*return storage(((Player) hostContainer).inventory);
            *///?}
        }
        return hostContainer instanceof Container ? storage((Container) hostContainer) : null;
    }

    @Override
    public boolean open(Object player, String type, String title, CgMenus.Logic logic, byte[] spec) {
        MenuType<HostMenu> made = TYPES.get(type);
        Loader half = loader;
        if (made == null || half == null || !(player instanceof ServerPlayer)) return false;
        MenuProvider provider = new SimpleMenuProvider((id, inventory, who) -> new HostMenu(made, id, logic), text(title));
        return half.open((ServerPlayer) player, provider, spec);
    }

    @Override
    public boolean close(Object player, int menuId) {
        if (!(player instanceof ServerPlayer) || ((ServerPlayer) player).containerMenu.containerId != menuId) return false;
        ((ServerPlayer) player).closeContainer();
        return true;
    }

    private static Component text(String title) {
        //? if >=1.19 {
        return Component.literal(title);
        //?} else {
        /*return new TextComponent(title);
        *///?}
    }

    /** {@code container} as a storage: the game's own stacks, changed in place by a menu as vanilla's are. */
    static CgItemStorage storage(Container container) {
        return new CgItemStorage() {
            @Override
            public int size() {
                return container.getContainerSize();
            }

            @Override
            public CgItemStack get(int slot) {
                return stack(container.getItem(slot));
            }

            @Override
            public void set(int slot, CgItemStack stack) {
                container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.toHost());
            }

            @Override
            public void changed() {
                container.setChanged();
            }

            @Override
            public Object host() {
                return container;
            }
        };
    }

    /** The game's container behind {@code storage}; one written by hand gets a copy that writes back. */
    static Container container(CgItemStorage storage) {
        Object host = storage.host();
        return host instanceof Container ? (Container) host : new StorageContainer(storage);
    }

    static CgItemStack stack(ItemStack stack) {
        return stack.isEmpty() ? CgItemStack.EMPTY : CgItemStack.of(stack);
    }

    static ClickType clickType(String name) {
        return ClickType.valueOf(name);
    }
}
//?} else {
/*import com.crystalgraphics.mc.container.CgItemStorage;
import com.crystalgraphics.mc.container.CgMenus;

// 1.13.2 has no menu types: nothing registers, nothing opens
public final class MenusModern implements CgMenus.Host {

    public static final MenusModern INSTANCE = new MenusModern();

    private MenusModern() {
    }

    @Override
    public CgItemStorage create(int size) {
        return null;
    }

    @Override
    public CgItemStorage wrap(Object hostContainer) {
        return null;
    }

    @Override
    public boolean open(Object player, String type, String title, CgMenus.Logic logic, byte[] spec) {
        return false;
    }
}
*///?}
