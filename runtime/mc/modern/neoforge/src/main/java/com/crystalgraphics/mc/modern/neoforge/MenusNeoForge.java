package com.crystalgraphics.mc.modern.neoforge;

//? if >=1.20.6 {
/*import com.crystalgraphics.mc.modern.platform.HostMenu;
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
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.RegisterEvent;

/^*
 * {@code CgMenus} on NeoForge: a type whose open carries the spec ({@code IMenuTypeExtension}), opened through
 * {@code openMenu(provider, Consumer)}, which sends it in the open itself so the client builds its menu before the
 * contents arrive. Types are registered in {@code RegisterEvent}, after every mod declared its own; each gets
 * {@link PlainMenuScreen}, which an engine screen replaces where one is registered.
 ^/
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
            modBus.addListener((RegisterMenuScreensEvent event) -> {
                for (MenuType<HostMenu> type : MenusModern.types()) event.register(type, PlainMenuScreen::new);
            });
        }
    }

    @Override
    public MenuType<HostMenu> type(MenusModern.Factory factory) {
        return IMenuTypeExtension.create((windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
    }

    @Override
    public boolean open(ServerPlayer player, MenuProvider provider, byte[] spec) {
        return player.openMenu(provider, data -> data.writeByteArray(spec)).isPresent();
    }
}
*///?} else {
// NeoForge before 1.20.6 has no menu opener here yet (GS9B): CgMenus types do not register or open
final class MenusNeoForge {

    private MenusNeoForge() {
    }

    static void install(Object modBus) {
    }
}
//?}
