package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 {
import com.crystalgraphics.platform.service.CgScreens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;
*///?}

import javax.annotation.Nullable;

/**
 * The engine's screen over a menu the server opened: vanilla's container screen class, its slots the menu's, so the
 * game's own gestures, its close (which tells the server) and every mod reading a container screen's geometry work as
 * on vanilla's. It draws nothing; its owner draws at the {@code SCREEN} stage, and each frame its panel and hovered slot
 * become this screen's ({@code leftPos}, {@code topPos}, the image size, {@code hoveredSlot}).
 *
 * <ul>
 *   <li>Opened in place of the screen the game made for the menu, before that one's {@code init}
 *       ({@link #instead}), where {@link CgScreens#replaceMenu} names the menu's type. 1.13.2 has no menu types: a
 *       stub there replaces nothing.</li>
 *   <li>From 26.1.2 the image size is fixed at construction: vanilla's 176 by 166, the owner's panel placing the rest.</li>
 * </ul>
 */
final class OwnedContainer extends AbstractContainerScreen<AbstractContainerMenu> {

    /** Vanilla's container image, what a 26.1.2+ screen is made with. */
    private static final int IMAGE_WIDTH = 176, IMAGE_HEIGHT = 166;

    final OwnedState state;
    private final float[] panel = new float[4];

    private OwnedContainer(AbstractContainerMenu menu, Screen opening, CgScreens.Owner owner) {
        //? if >=26.1.2 {
        /*super(menu, Minecraft.getInstance().player.getInventory(), opening.getTitle(), IMAGE_WIDTH, IMAGE_HEIGHT);
        *///?} elif >=1.17 {
        super(menu, Minecraft.getInstance().player.getInventory(), opening.getTitle());
        //?} else {
        /*super(menu, Minecraft.getInstance().player.inventory, opening.getTitle());
        *///?}
        this.state = new OwnedState(owner);
    }

    /** The engine's container screen to open instead of {@code opening}; null to let the game's open. */
    @Nullable
    static Screen instead(@Nullable Screen opening) {
        if (!(opening instanceof AbstractContainerScreen) || opening instanceof OwnedContainer) return null;
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) opening).getMenu();
        String type = ContainersModern.typeOf(menu);
        CgScreens.MenuScreen screen = CgScreens.menuScreen(type);
        if (screen == null) return null;
        CgScreens.Owner owner = screen.open(type);
        return owner == null ? null : new OwnedContainer(menu, opening, owner);
    }

    @Nullable
    static OwnedState state(@Nullable Screen screen) {
        return screen instanceof OwnedContainer ? ((OwnedContainer) screen).state : null;
    }

    @Override
    protected void init() {
        super.init();
        state.init(this, minecraft);
    }

    @Override
    public void removed() {
        super.removed();
        state.removed(this, minecraft);
    }

    @Override
    //? if >=1.17.1 {
    protected void containerTick() {
        super.containerTick();
    //?} else {
    /*public void tick() {
        super.tick();
    *///?}
        if (state.takeClose()) onClose();
    }

    /** Escape is its owner's; any other key the input stage left is vanilla's (the inventory key closes it). */
    @Override
    //? if >=1.21.9 {
    /*public boolean keyPressed(KeyEvent event) {
        return state.escape(event.key()) || super.keyPressed(event);
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return state.escape(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    //?}
    }

    // The owner's panel and pointer as vanilla's geometry, once a frame
    private void follow() {
        if (state.owner.panel(panel)) {
            leftPos = Math.round(panel[0]);
            topPos = Math.round(panel[1]);
            //? if <26.1.2 {
            imageWidth = Math.round(panel[2]);
            imageHeight = Math.round(panel[3]);
            //?}
        }
        int slot = state.owner.hoveredSlot();
        hoveredSlot = slot >= 0 && slot < menu.slots.size() ? menu.slots.get(slot) : null;
    }

    // Nothing drawn: the owner draws at the SCREEN stage
    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        follow();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?} elif >=1.20 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        follow();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }
    //?} elif >=1.16 {
    /*@Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        follow();
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
    }
    *///?} else {
    /*@Override
    public void render(int mouseX, int mouseY, float partialTick) {
        follow();
    }

    @Override
    protected void renderBg(float partialTick, int mouseX, int mouseY) {
    }
    *///?}
}
//?} else {
/*import com.crystalgraphics.platform.service.CgScreens;
import net.minecraft.client.gui.screens.Screen;

// 1.13.2 has no menu types (no MenuScreens): its menus keep the game's screens
final class OwnedContainer {

    private OwnedContainer() {
    }

    static Screen instead(Screen opening) {
        return null;
    }

    static OwnedState state(Screen screen) {
        return null;
    }
}
*///?}
