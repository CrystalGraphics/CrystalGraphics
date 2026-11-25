package net.minecraft.client.gui.screens;

import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

// Compile only, never shipped (useLoaderAccess): the members of MenuScreens a from-parts node's loader opens by its
// access transformer, at that access. Forge 48-66 and NeoForge 20.2-20.3 list exactly these two (META-INF/
// accesstransformer.cfg of their universal jars); vanilla keeps both private, which the node's classpath shows.
public class MenuScreens {

    public static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(
            MenuType<? extends M> type, ScreenConstructor<M, U> constructor) {
        throw null;
    }

    public interface ScreenConstructor<T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> {
        U create(T menu, Inventory inventory, Component title);
    }
}
