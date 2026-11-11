package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgScreens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.19.2 {
import net.minecraft.network.chat.Component;
//?} elif >=1.14.3 {
/*import net.minecraft.network.chat.TextComponent;
*///?}
//? if >=1.21.9 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
*///?}
//? if >=26.3 {
/*import net.minecraft.client.input.PreeditEvent;
import javax.annotation.Nullable;
*///?}

/**
 * {@link CgScreens} on the modern tree: the engine's screen is one empty {@code Screen} holding its owner, which draws
 * nothing (the {@code SCREEN} stage draws over it), pauses nothing and gives Escape to its owner. Client only, from
 * {@code PlatformServiceModern.installClient}.
 *
 * <ul>
 *   <li>A close lands at the screen's next tick: 26.1+ fires the GUI stages at the frame's end, and a close inside a
 *       frame being drawn would take the screen from under it.</li>
 *   <li>1.21.6 to 26.0 draw the HUD at the frame's end, over every screen: an owner that hides it gets the player's own
 *       setting back on close. From 26.1 the GUI stages draw over the HUD.</li>
 *   <li>26.3's SDL types into a screen only while it holds text input, and opens an input method's candidate list where
 *       the screen says; its run in progress goes to the {@code SCREEN} input stage as a composition.</li>
 * </ul>
 */
public final class ScreensModern implements CgScreens.Host {

    public static final ScreensModern INSTANCE = new ScreensModern();

    private ScreensModern() {
    }

    @Override
    public void open(CgScreens.Owner owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && front() != owner) CgMinecraftClient.setScreen(mc, new Owned(owner));
    }

    @Override
    public void close(CgScreens.Owner owner) {
        Screen screen = screen();
        if (screen instanceof Owned && ((Owned) screen).owner == owner) ((Owned) screen).closing = true;
    }

    @Override
    public CgScreens.Owner front() {
        Screen screen = screen();
        return screen instanceof Owned ? ((Owned) screen).owner : null;
    }

    @Override
    public boolean anyFront() {
        return screen() != null;
    }

    @Override
    public void giveBack(CgSystemInput.Keyboard.Event key) {
        Screen screen = screen();
        if (screen instanceof Owned) {
            if (key.pressed() && key.key() == CgKeyCodes.KEY_ESCAPE) ((Owned) screen).owner.escape();
        } else if (screen != null) {
            give(screen, key);
        }
    }

    private static Screen screen() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : CgMinecraftClient.screen(mc);
    }

    /** {@code key} through {@code screen}'s own handlers: a key code to {@code keyPressed}/{@code keyReleased}, a character to {@code charTyped}. */
    private static void give(Screen screen, CgSystemInput.Keyboard.Event key) {
        if (key.character() != 0) {
            //? if >=26.1 {
            /*screen.charTyped(new CharacterEvent(key.character()));
            *///?} elif >=1.21.9 {
            /*screen.charTyped(new CharacterEvent(key.character(), 0));
            *///?} else {
            screen.charTyped(key.character(), 0);
            //?}
            return;
        }
        int code = HostKeys.key(key.key());
        //? if >=1.21.9 {
        /*KeyEvent event = new KeyEvent(code, 0, 0);
        if (key.pressed()) screen.keyPressed(event);
        else screen.keyReleased(event);
        *///?} else {
        if (key.pressed()) screen.keyPressed(code, 0, 0);
        else screen.keyReleased(code, 0, 0);
        //?}
    }

    /** The engine's screen: empty, its owner's. */
    private static final class Owned extends Screen {

        final CgScreens.Owner owner;
        /** Set by a close: taken down at the next tick. */
        boolean closing;
        //? if >=1.21.6 <26.1 {
        /*/^* The player's own HUD setting, kept once an opening: init runs again on every resize. ^/
        private Boolean hudHiddenBefore;
        *///?}
        //? if >=26.3 {
        /*private final float[] area = new float[4];
        private int placedLeft = -1, placedTop, placedRight, placedBottom;
        *///?}

        Owned(CgScreens.Owner owner) {
            //? if >=1.19.2 {
            super(Component.empty());
            //?} elif >=1.14.3 {
            /*super(new TextComponent(""));
            *///?} else {
            /*super();
            *///?}
            this.owner = owner;
        }

        @Override
        protected void init() {
            //? if >=26.3 {
            /*minecraft.onTextInputFocusChange(this, true);
            *///?}
            //? if >=1.21.6 <26.1 {
            /*if (owner.hidesHud()) {
                if (hudHiddenBefore == null) hudHiddenBefore = CgMinecraftClient.hudHidden(minecraft);
                CgMinecraftClient.setHudHidden(minecraft, true);
            }
            *///?}
            owner.shown();
        }

        @Override
        public void removed() {
            //? if >=26.3 {
            /*minecraft.onTextInputFocusChange(this, false);
            *///?}
            //? if >=1.21.6 <26.1 {
            /*if (hudHiddenBefore != null) CgMinecraftClient.setHudHidden(minecraft, hudHiddenBefore);
            hudHiddenBefore = null;
            *///?}
            owner.hidden();
        }

        @Override
        public void tick() {
            super.tick();
            if (!closing) return;
            closing = false;
            onClose();
        }

        /** Escape that no input listener took: its owner's. {@link #shouldCloseOnEsc} keeps vanilla from closing first. */
        @Override
        //? if >=1.21.9 {
        /*public boolean keyPressed(KeyEvent event) {
            int keyCode = event.key();
        *///?} else {
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        //?}
            if (CgInput.key(keyCode) != CgKeyCodes.KEY_ESCAPE) return false;
            owner.escape();
            return true;
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        /** The game runs under it: a paused integrated server pumps no connection. */
        @Override
        public boolean isPauseScreen() {
            return false;
        }

        // Nothing drawn: widgets, background and blur are none of its own, and the SCREEN stage draws over it.
        //? if >=26.3 {
        /*@Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            placeTextInputArea();
        }
        *///?} elif >=26.1 {
        /*@Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        }
        *///?} elif >=1.20 {
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }
        //?} elif >=1.16 {
        /*@Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        }
        *///?} else {
        /*@Override
        public void render(int mouseX, int mouseY, float partialTick) {
        }
        *///?}

        //? if >=26.1 {
        /*@Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        }
        *///?} elif >=1.21.6 {
        /*@Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }
        *///?}

        //? if >=26.3 {
        /*/^* An input method's run in progress: Minecraft hands it to the screen, and a null event ends it. ^/
        @Override
        public boolean preeditUpdated(@Nullable PreeditEvent event) {
            return event == null ? ScreenInputModern.composition("", 0)
                    : ScreenInputModern.composition(event.fullText(), event.caretPosition());
        }

        /^*
         * Where the owner's text is, to SDL: in GUI units, as two corners, as {@code EditBox} passes them. Sent only on a
         * change, since SDL passes each to the OS.
         ^/
        private void placeTextInputArea() {
            if (!owner.textInputArea(area)) return;
            float scale = Math.max(1, (float) Windows.of(minecraft).getGuiScale());
            int left = (int) Math.floor(area[0] / scale), top = (int) Math.floor(area[1] / scale);
            int right = Math.max(left + 1, (int) Math.ceil((area[0] + area[2]) / scale));
            int bottom = Math.max(top + 1, (int) Math.ceil((area[1] + area[3]) / scale));
            if (left == placedLeft && top == placedTop && right == placedRight && bottom == placedBottom) return;
            placedLeft = left;
            placedTop = top;
            placedRight = right;
            placedBottom = bottom;
            minecraft.textInputManager().setTextInputArea(left, top, right, bottom);
        }
        *///?}
    }
}
