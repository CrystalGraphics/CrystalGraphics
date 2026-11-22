package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.probe.CgPicturesProbe;
//? if >=1.12 {
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
//?}

/**
 * The legacy half of {@link CgPicturesProbe}: vanilla's GUI calls, into the main framebuffer from the HUD hook and into
 * a picture through the draw point. 1.12.2 only; 1.8.9 and 1.10.2 give the probe its absent value.
 */
public final class PicturesProbeLegacy {

    private PicturesProbeLegacy() {
    }

    public static CgPicturesProbe.Host create() {
        //? if >=1.12 {
        return new Gui1122();
        //?} else {
        /*return CgPicturesProbe.Host.NONE;
        *///?}
    }

    //? if >=1.12 {
    /** Immediate GL through {@code GlStateManager}, into whatever framebuffer is bound. */
    private static final class Gui1122 implements CgPicturesProbe.Host {
        private Tips tips;
        private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

        /** {@code renderToolTip} is a screen's: one never shown, sized as the window's GUI. */
        private static final class Tips extends GuiScreen {
            void tip(ItemStack stack, int x, int y) {
                renderToolTip(stack, x, y);
            }
        }

        @Override
        public void cooldown(String item) {
            //? if >=1.10 {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            Item it = Item.getByNameOrId(item);
            if (player == null || it == null) return;
            if (!player.getCooldownTracker().hasCooldown(it)) player.getCooldownTracker().setCooldown(it, 200);
            //?}
        }

        @Override
        public String absent() {
            return OpenGlHelper.isFramebufferEnabled() ? null : "framebuffers are off (Fast Render or an old driver)";
        }

        @Override
        public boolean knows(String item) {
            return Item.getByNameOrId(item) != null;
        }

        @Override
        public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                              Drawn out) {
            Minecraft mc = Minecraft.getMinecraft();
            long start = System.nanoTime();
            Gui.drawRect(x, y, x + w, y + h, background);
            Gui.drawRect(bx, by, bx + w, by + h, background);
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0f);
            for (Cell cell : cells) draw(mc, cell);
            GlStateManager.popMatrix();
            out.nanos = System.nanoTime() - start;
            out.drew = true;
        }

        @Override
        public void cells(CgHostDrawContext ctx, List<Cell> cells, Drawn out) {
            Minecraft mc = Minecraft.getMinecraft();
            // Measured in the parity phase only: a glGet stalls a legacy context
            int bound = out.checkTarget ? GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) : 0;
            for (Cell cell : cells) {
                draw(mc, cell);
                if (out.checkTarget && GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != bound) {
                    out.retargeted.add(cell.item().isEmpty() ? cell.kind().name() : cell.item());
                }
            }
        }

        private void draw(Minecraft mc, Cell cell) {
            switch (cell.kind()) {
                case ITEM:
                case DECORATED:
                    RenderHelper.enableGUIStandardItemLighting();
                    mc.getRenderItem().renderItemAndEffectIntoGUI(stack(cell), cell.x(), cell.y());
                    if (cell.kind() == CgPicturesProbe.Kind.DECORATED) {
                        mc.getRenderItem().renderItemOverlays(mc.fontRenderer, stack(cell), cell.x(), cell.y());
                    }
                    RenderHelper.disableStandardItemLighting();
                    break;
                case PLAYER:
                    if (mc.player != null) {
                        // Depth on, as in the inventory screen: the HUD draws with it off, which shows a model's far side
                        GlStateManager.enableDepth();
                        GuiInventory.drawEntityOnScreen(cell.x(), cell.y(), cell.count(), CgPicturesProbe.LOOK_X,
                                CgPicturesProbe.LOOK_Y, mc.player);
                        GlStateManager.disableDepth();
                    }
                    break;
                case TOOLTIP:
                    if (tips == null) tips = new Tips();
                    ScaledResolution res = new ScaledResolution(mc);
                    tips.setWorldAndResolution(mc, res.getScaledWidth(), res.getScaledHeight());
                    tips.tip(stack(cell), cell.x(), cell.y());
                    RenderHelper.disableStandardItemLighting();
                    break;
                default:
            }
        }

        private ItemStack stack(Cell cell) {
            if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
            ItemStack stack = stacks.get(cell);
            if (stack == null) {
                stack = new ItemStack(Item.getByNameOrId(cell.item()), Math.max(1, cell.count()));
                if (cell.damage() > 0) stack.setItemDamage(cell.damage());
                if (cell.glint()) stack.addEnchantment(Enchantments.SHARPNESS, 1);
                stacks.put(cell, stack);
            }
            return stack;
        }

        @Override
        public CgHostDrawContext hudContext(Object gui) {
            return CgPicturesProbe.GL_STATE;
        }
    }
    //?}
}
