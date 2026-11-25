package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.modern.platform.HostMenu;
import com.crystalgraphics.mc.modern.platform.MenusModern;
import com.crystalgraphics.mc.modern.platform.PlainMenuScreen;
import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.shared.FmlSide;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.RegisterEvent;
//? if >=1.20.4 {
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
//?} else {
/*import net.minecraft.client.gui.screens.MenuScreens;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.NetworkHooks;
*///?}

/**
 * {@code CgMenus} on NeoForge: a type whose open carries the spec ({@code IMenuTypeExtension}), opened by NeoForge's
 * own extra-data open, so the client builds its menu before the contents arrive. Types are registered in
 * {@code RegisterEvent}, after every mod declared its own; each gets {@link PlainMenuScreen}, which an engine screen
 * replaces where one is registered.
 *
 * <ul>
 *   <li>The open: {@code NetworkHooks.openScreen} on 1.20.2-1.20.3, {@code openMenu(provider, Consumer)} from 1.20.4.</li>
 *   <li>The screen: {@code RegisterMenuScreensEvent} from 1.20.4; before it {@code MenuScreens.register}, which
 *       NeoForge's access transformer opens (compiled through {@code useLoaderAccess}).</li>
 * </ul>
 */
final class MenusNeoForge implements MenusModern.Loader {

    private MenusNeoForge() {
    }

    static void install(IEventBus modBus) {
        MenusModern.loader(new MenusNeoForge());
        modBus.addListener((RegisterEvent event) -> event.register(Registries.MENU,
                helper -> MenusModern.register((id, type) -> helper.register(ResourceIds.parse(id), type))));
        if (FmlSide.isClient(FMLLoader.class)) Screens.register(modBus);
    }

    // Client only: names a client event
    private static final class Screens {

        static void register(IEventBus modBus) {
            //? if >=1.20.4 {
            modBus.addListener((RegisterMenuScreensEvent event) -> {
                for (MenuType<HostMenu> type : MenusModern.types()) event.register(type, PlainMenuScreen::new);
            });
            //?} else {
            /*modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
                for (MenuType<HostMenu> type : MenusModern.types()) MenuScreens.register(type, PlainMenuScreen::new);
            }));
            *///?}
        }
    }

    @Override
    public MenuType<HostMenu> type(String id, MenusModern.Factory factory) {
        return IMenuTypeExtension.create((windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
    }

    @Override
    public boolean open(ServerPlayer player, MenuProvider provider, byte[] spec) {
        //? if >=1.20.4 {
        return player.openMenu(provider, data -> data.writeByteArray(spec)).isPresent();
        //?} else {
        /*NetworkHooks.openScreen(player, provider, data -> data.writeByteArray(spec));
        return true;
        *///?}
    }
}
