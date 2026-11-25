package com.crystalgraphics.mc.modern.forge;

//? if >=1.19.3 <1.20.2 {
import com.crystalgraphics.mc.modern.platform.HostMenu;
import com.crystalgraphics.mc.modern.platform.MenusModern;
import com.crystalgraphics.mc.modern.platform.PlainMenuScreen;
import com.crystalgraphics.mc.modern.platform.ResourceIds;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.RegisterEvent;

/**
 * {@code CgMenus} on Forge: a type whose open carries the spec ({@code IForgeMenuType}), opened through
 * {@code NetworkHooks.openScreen}, which sends it in Forge's own open message so the client builds its menu before the
 * contents arrive. Types are registered in {@code RegisterEvent}, after every mod declared its own; each gets
 * {@link PlainMenuScreen}, which an engine screen replaces where one is registered.
 */
final class MenusForge implements MenusModern.Loader {

    private MenusForge() {
    }

    static void install(FMLJavaModLoadingContext context) {
        MenusModern.loader(new MenusForge());
        context.getModEventBus().addListener((RegisterEvent event) -> event.register(Registries.MENU,
                helper -> MenusModern.register((id, type) -> helper.register(ResourceIds.parse(id), type))));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.getModEventBus().addListener((FMLClientSetupEvent event) -> event.enqueueWork(MenusForge::screens));
        }
    }

    // Client only
    private static void screens() {
        for (MenuType<HostMenu> type : MenusModern.types()) MenuScreens.register(type, PlainMenuScreen::new);
    }

    @Override
    public MenuType<HostMenu> type(MenusModern.Factory factory) {
        return IForgeMenuType.create((windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
    }

    @Override
    public boolean open(ServerPlayer player, MenuProvider provider, byte[] spec) {
        NetworkHooks.openScreen(player, provider, data -> data.writeByteArray(spec));
        return true;
    }
}
//?} else {
/*// Forge outside 1.19.3-1.20.1 has no menu opener here yet (GS9B): CgMenus types do not register or open
final class MenusForge {

    private MenusForge() {
    }

    static void install(Object context) {
    }
}
*///?}
