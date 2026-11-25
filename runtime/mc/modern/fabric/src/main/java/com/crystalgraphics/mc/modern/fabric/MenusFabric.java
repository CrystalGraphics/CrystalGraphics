package com.crystalgraphics.mc.modern.fabric;

//? if >=1.16.5 {
import com.crystalgraphics.mc.modern.platform.HostMenu;
import com.crystalgraphics.mc.modern.platform.MenusModern;
import com.crystalgraphics.mc.modern.platform.PlainMenuScreen;
import com.crystalgraphics.mc.modern.platform.ResourceIds;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
*///?} else {
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
//?}
//? if >=1.18.2 <26.1 {
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
//?}
//? if >=1.20.5 {
/*import net.minecraft.network.codec.ByteBufCodecs;
*///?} else {
import net.minecraft.network.FriendlyByteBuf;
//?}
//? if >=1.19.3 {
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
//?} elif >=1.18.2 {
/*import net.minecraft.core.Registry;
*///?} else {
/*import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
*///?}
//? if >=1.18.2 {
import net.minecraft.client.gui.screens.MenuScreens;
//?}

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code CgMenus} on Fabric 1.16.5-26.3: a type whose open carries the spec (Fabric's extended screen handler type,
 * {@code ExtendedMenuType} on 26.x), opened through the matching provider, so the client builds its menu before the
 * contents arrive. Fabric has no registration event: a type is registered when it is declared, while the registries
 * are open (mod initialization), and each gets {@link PlainMenuScreen} on a client.
 *
 * <ul>
 *   <li>The spec rides a {@code FriendlyByteBuf} to 1.20.4 and a {@code byte[]} codec from 1.20.6.</li>
 *   <li>1.16.5-1.17.1 make and register a type in one call ({@code ScreenHandlerRegistry.registerExtended}).</li>
 *   <li>1.14.4-1.15.2 have no type whose open carries data: nothing registers or opens there.</li>
 * </ul>
 */
final class MenusFabric implements MenusModern.Loader {

    private static final MenusFabric INSTANCE = new MenusFabric();
    /** Types whose screen is registered; a client's, once {@link Screens#install} ran. */
    private static final Set<MenuType<?>> SCREENED = ConcurrentHashMap.newKeySet();
    private static volatile boolean client;

    private MenusFabric() {
    }

    /** The common entry: types declared before it are registered now, the rest as they are declared. */
    static void install() {
        MenusModern.loader(INSTANCE);
        INSTANCE.declared(null);
    }

    @Override
    public void declared(String id) {
        //? if >=1.19.3 {
        MenusModern.register((made, type) -> Registry.register(BuiltInRegistries.MENU, ResourceIds.parse(made), type));
        //?} elif >=1.18.2 {
        /*MenusModern.register((made, type) -> Registry.register(Registry.MENU, ResourceIds.parse(made), type));
        *///?} else {
        /*MenusModern.register((made, type) -> { });   // registerExtended registered it
        *///?}
        if (client) Screens.register();
    }

    @Override
    public MenuType<HostMenu> type(String id, MenusModern.Factory factory) {
        //? if >=26.1 {
        /*return new ExtendedMenuType<HostMenu, byte[]>((windowId, inventory, spec) -> factory.create(windowId, inventory, spec),
                ByteBufCodecs.BYTE_ARRAY);
        *///?} elif >=1.20.5 {
        /*return new ExtendedScreenHandlerType<HostMenu, byte[]>((windowId, inventory, spec) -> factory.create(windowId, inventory, spec),
                ByteBufCodecs.BYTE_ARRAY);
        *///?} elif >=1.18.2 {
        return new ExtendedScreenHandlerType<HostMenu>((windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
        //?} else {
        /*return ScreenHandlerRegistry.registerExtended(ResourceIds.parse(id),
                (windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
        *///?}
    }

    @Override
    public boolean open(ServerPlayer player, MenuProvider provider, byte[] spec) {
        //? if >=26.1 {
        /*return player.openMenu(new ExtendedMenuProvider<byte[]>() {
            @Override
            public byte[] getScreenOpeningData(ServerPlayer to) {
                return spec;
            }
        *///?} elif >=1.20.5 {
        /*return player.openMenu(new ExtendedScreenHandlerFactory<byte[]>() {
            @Override
            public byte[] getScreenOpeningData(ServerPlayer to) {
                return spec;
            }
        *///?} else {
        return player.openMenu(new ExtendedScreenHandlerFactory() {
            @Override
            public void writeScreenOpeningData(ServerPlayer to, FriendlyByteBuf data) {
                data.writeByteArray(spec);
            }
        //?}

            @Override
            public Component getDisplayName() {
                return provider.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player who) {
                return provider.createMenu(windowId, inventory, who);
            }
        }).isPresent();
    }

    /** Client only: names the client's screen registry. */
    static final class Screens {

        /** The client entry: every type made so far, and each made after, gets its screen. */
        static void install() {
            client = true;
            register();
        }

        static void register() {
            for (MenuType<HostMenu> type : MenusModern.types()) {
                if (!SCREENED.add(type)) continue;
                //? if >=1.18.2 {
                MenuScreens.register(type, PlainMenuScreen::new);
                //?} else {
                /*ScreenRegistry.register(type, PlainMenuScreen::new);
                *///?}
            }
        }
    }
}
//?} else {
/*// Fabric 1.14.4-1.15.2 have no menu type whose open carries data: CgMenus types do not register or open there
final class MenusFabric {

    private MenusFabric() {
    }

    static void install() {
    }

    static final class Screens {

        static void install() {
        }
    }
}
*///?}
