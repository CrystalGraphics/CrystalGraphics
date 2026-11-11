package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Method;

/**
 * {@link CgScreens} on Forge 1.8 to 1.12.2: the engine's screen is one empty {@code GuiScreen} holding its owner, which draws nothing
 * (the {@code SCREEN} stage draws over it), pauses nothing and gives Escape to its owner. Client only, from
 * {@code PlatformServiceLegacy.install}.
 *
 * <ul>
 *   <li>A close lands at the screen's next tick, which hands the mouse back to the game too: closing mid-frame left
 *       that frame's HUD painted by nobody.</li>
 *   <li>Held keys repeat while it is up, as a text field expects.</li>
 *   <li>A key given back reaches another screen through its {@code keyTyped}, protected, found by its development and
 *       SRG names; a release reaches none, since these screens hear presses alone.</li>
 * </ul>
 */
public final class ScreensLegacy implements CgScreens.Host {

    public static final ScreensLegacy INSTANCE = new ScreensLegacy();
    private static final Logger LOGGER = LogManager.getLogger(ScreensLegacy.class);
    private static final String[] KEY_TYPED = {"keyTyped", "func_73869_a"};

    private Method keyTyped;
    private boolean keyTypedMissing;

    private ScreensLegacy() {
    }

    @Override
    public void open(CgScreens.Owner owner) {
        if (front() != owner) Minecraft.getMinecraft().displayGuiScreen(new Owned(owner));
    }

    @Override
    public void close(CgScreens.Owner owner) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof Owned && ((Owned) screen).owner == owner) ((Owned) screen).closing = true;
    }

    @Override
    public CgScreens.Owner front() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        return screen instanceof Owned ? ((Owned) screen).owner : null;
    }

    @Override
    public boolean anyFront() {
        return Minecraft.getMinecraft().currentScreen != null;
    }

    @Override
    public void giveBack(CgSystemInput.Keyboard.Event key) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof Owned) {
            if (key.pressed() && key.key() == CgKeyCodes.KEY_ESCAPE) ((Owned) screen).owner.escape();
        } else if (screen != null && key.pressed()) {
            type(screen, key.character(), key.key());
        }
    }

    /** {@code screen.keyTyped(character, key)}; logged once if it cannot be reached. */
    private void type(GuiScreen screen, char character, int key) {
        Method method = keyTyped();
        if (method == null) return;
        try {
            method.invoke(screen, character, key);
        } catch (ReflectiveOperationException thrown) {
            LOGGER.warn("[crystalgraphics] could not give {} a key", screen.getClass().getName(), thrown);
        }
    }

    private Method keyTyped() {
        if (keyTyped != null || keyTypedMissing) return keyTyped;
        for (String name : KEY_TYPED) {
            try {
                Method found = GuiScreen.class.getDeclaredMethod(name, char.class, int.class);
                found.setAccessible(true);
                return keyTyped = found;
            } catch (NoSuchMethodException tryNext) {
                // the other name
            }
        }
        keyTypedMissing = true;
        LOGGER.warn("[crystalgraphics] GuiScreen.keyTyped not found: keys given back reach no screen");
        return null;
    }

    /** The engine's screen: empty, its owner's. */
    private static final class Owned extends GuiScreen {

        final CgScreens.Owner owner;
        /** Set by a close: taken down at the next tick. */
        boolean closing;

        Owned(CgScreens.Owner owner) {
            this.owner = owner;
        }

        @Override
        public void initGui() {
            Keyboard.enableRepeatEvents(true);
            owner.shown();
        }

        @Override
        public void onGuiClosed() {
            Keyboard.enableRepeatEvents(false);
            owner.hidden();
        }

        @Override
        public void updateScreen() {
            if (!closing) return;
            closing = false;
            mc.displayGuiScreen(null);
            // displayGuiScreen(null) gives the game the mouse only with a living player in a world: asked directly
            if (CgWorldQueries.clientLevel() != null && CgPlayers.local() != null) mc.setIngameFocus();
        }

        /** Nothing drawn: the SCREEN stage draws over it. */
        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        }

        /** Escape that no input listener took: its owner's. Never vanilla's, which closes at once. */
        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            if (keyCode == Keyboard.KEY_ESCAPE) owner.escape();
        }

        /** The game runs under it: a paused integrated server pumps no connection. */
        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }

    @Override
    public void closeFront() {
        Minecraft.getMinecraft().displayGuiScreen(null);
    }

    @Override
    public void openChat() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
    }
}
