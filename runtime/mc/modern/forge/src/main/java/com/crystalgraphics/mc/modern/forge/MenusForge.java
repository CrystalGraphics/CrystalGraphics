package com.crystalgraphics.mc.modern.forge;

//? if >=1.14.3 {
import com.crystalgraphics.mc.modern.platform.HostMenu;
import com.crystalgraphics.mc.modern.platform.MenusModern;
import com.crystalgraphics.mc.modern.platform.PlainMenuScreen;
import com.crystalgraphics.mc.modern.platform.ResourceIds;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if >=1.20.2 {
/*import net.minecraftforge.common.extensions.IForgeServerPlayer;
*///?}
//? if >=1.18.2 {
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.network.IContainerFactory;
//?} else {
/*import net.minecraftforge.common.extensions.IForgeContainerType;
*///?}
//? if <1.16 {
/*import net.minecraftforge.fml.DeferredWorkQueue;
*///?}
//? if >=1.19.2 {
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
//?} else {
/*import net.minecraftforge.event.RegistryEvent;
*///?}
//? if >=1.19.2 <1.20.2 {
import net.minecraftforge.network.NetworkHooks;
//?} elif >=1.18.2 <1.19.2 {
/*import net.minecraftforge.network.NetworkHooks;
*///?} elif >=1.17.1 <1.18.2 {
/*import net.minecraftforge.fmllegacy.network.NetworkHooks;
*///?} elif <1.17.1 {
/*import net.minecraftforge.fml.network.NetworkHooks;
*///?}

/**
 * {@code CgMenus} on Forge 1.14.3-26.3: a type whose open carries the spec ({@code IForgeContainerType}, then
 * {@code IForgeMenuType}), opened by Forge's own extra-data open, so the client builds its menu before the contents
 * arrive. Types are registered when the menu registry is, after every mod declared its own; each gets
 * {@link PlainMenuScreen}, which an engine screen replaces where one is registered.
 *
 * <ul>
 *   <li>The open: {@code NetworkHooks.openGui} to 1.18.2, {@code openScreen} 1.19.2-1.20.1,
 *       {@code IForgeServerPlayer.openMenu} from 1.20.2.</li>
 *   <li>The screen: {@code MenuScreens.register}, which Forge's access transformer opens from 1.20.2 (compiled
 *       through {@code useLoaderAccess}, since a from-parts node's classpath keeps vanilla's access).</li>
 * </ul>
 */
final class MenusForge implements MenusModern.Loader {

    private MenusForge() {
    }

    static void install(FMLJavaModLoadingContext context) {
        MenusModern.loader(new MenusForge());
        //? if >=1.21.6 {
        /*RegisterEvent.getBus(context.getModBusGroup()).addListener(MenusForge::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(event -> event.enqueueWork(MenusForge::screens));
        }
        *///?} elif >=1.19.2 {
        context.getModEventBus().addListener(MenusForge::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.getModEventBus().addListener((FMLClientSetupEvent event) -> event.enqueueWork(MenusForge::screens));
        }
        //?} elif >=1.16 {
        /*context.getModEventBus().addGenericListener(MenuType.class, MenusForge::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.getModEventBus().addListener((FMLClientSetupEvent event) -> event.enqueueWork(MenusForge::screens));
        }
        *///?} else {
        /*// To 1.15.2 the setup event has no work queue of its own: Forge's deferred one runs on the main thread
        context.getModEventBus().addGenericListener(MenuType.class, MenusForge::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.getModEventBus().addListener((FMLClientSetupEvent event) -> DeferredWorkQueue.runLater(MenusForge::screens));
        }
        *///?}
    }

    //? if >=1.19.2 {
    private static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.MENU_TYPES,
                helper -> MenusModern.register((id, type) -> helper.register(ResourceIds.parse(id), type)));
    }
    //?} else {
    /*private static void register(RegistryEvent.Register<MenuType<?>> event) {
        MenusModern.register((id, type) -> {
            type.setRegistryName(ResourceIds.parse(id));
            event.getRegistry().register(type);
        });
    }
    *///?}

    // Client only
    private static void screens() {
        for (MenuType<HostMenu> type : MenusModern.types()) MenuScreens.register(type, PlainMenuScreen::new);
    }

    @Override
    public MenuType<HostMenu> type(String id, MenusModern.Factory factory) {
        //? if >=1.18.2 {
        // A class, not a lambda: an installed 1.20.2-1.20.4 Forge names its default create(int, Inventory) by SRG,
        // so on that classpath the interface has two abstract methods. The two-argument one is Forge's default's.
        return IForgeMenuType.create(new IContainerFactory<HostMenu>() {
            @Override
            public HostMenu create(int windowId, Inventory inventory, FriendlyByteBuf data) {
                return factory.create(windowId, inventory, data.readByteArray());
            }

            @Override
            public HostMenu create(int windowId, Inventory inventory) {
                return create(windowId, inventory, null);
            }
        });
        //?} else {
        /*return IForgeContainerType.create((windowId, inventory, data) -> factory.create(windowId, inventory, data.readByteArray()));
        *///?}
    }

    @Override
    public boolean open(ServerPlayer player, MenuProvider provider, byte[] spec) {
        //? if >=1.20.2 {
        /*((IForgeServerPlayer) player).openMenu(provider, data -> data.writeByteArray(spec));
        *///?} elif >=1.19.2 {
        NetworkHooks.openScreen(player, provider, data -> data.writeByteArray(spec));
        //?} else {
        /*NetworkHooks.openGui(player, provider, data -> data.writeByteArray(spec));
        *///?}
        return true;
    }
}
//?} else {
/*// 1.13.2 has no menu types: CgMenus types do not register or open there
final class MenusForge {

    private MenusForge() {
    }

    static void install(Object context) {
    }
}
*///?}
