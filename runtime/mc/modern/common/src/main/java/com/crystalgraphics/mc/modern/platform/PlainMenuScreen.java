package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 {
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}

/**
 * The game's screen for a {@link HostMenu}: what the game opens for every {@code CgMenus} type, and what an engine
 * screen replaces where one is registered for the type ({@code OwnedContainer.instead}). Without one, the game's own
 * container screen with no picture: the slots in rows of nine, so the menu stays usable.
 */
public final class PlainMenuScreen extends AbstractContainerScreen<HostMenu> {

    private static final int COLUMNS = 9, CELL = 18, MARGIN = 8, TOP = 18;

    public PlainMenuScreen(HostMenu menu, Inventory inventory, Component title) {
        //? if >=26.1.2 {
        /*super(menu, inventory, title, MARGIN * 2 + COLUMNS * CELL, TOP + MARGIN + rows(menu) * CELL);
        *///?} else {
        super(menu, inventory, title);
        //?}
        int i = 0;
        for (Slot slot : menu.slots) {
            if (slot instanceof HostSlotPlace) ((HostSlotPlace) slot).crystalgraphics$place(MARGIN + i % COLUMNS * CELL, TOP + i / COLUMNS * CELL);
            i++;
        }
        //? if <26.1.2 {
        imageWidth = MARGIN * 2 + COLUMNS * CELL;
        imageHeight = TOP + MARGIN + rows(menu) * CELL;
        //?}
        //? if >=1.16 {
        inventoryLabelY = imageHeight + 2;
        //?}
    }

    private static int rows(HostMenu menu) {
        return (menu.slots.size() + COLUMNS - 1) / COLUMNS;
    }

    //? if >=26.1 {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?} elif >=1.20 {
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }
    //?} elif >=1.16 {
    /*@Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
    }
    *///?} else {
    /*@Override
    protected void renderBg(float partialTick, int mouseX, int mouseY) {
    }
    *///?}
}
//?} else {
/*final class PlainMenuScreen {

    private PlainMenuScreen() {
    }
}
*///?}
