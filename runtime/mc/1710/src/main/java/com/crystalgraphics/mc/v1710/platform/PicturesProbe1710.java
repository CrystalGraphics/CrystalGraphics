package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.probe.CgPicturesProbe;
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import com.crystalgraphics.probe.CgPicturesProbe.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 1.7.10 half of {@link CgPicturesProbe}: vanilla's GUI calls in raw GL, into the main framebuffer from the HUD
 * hook and into a picture through the draw point. Items, decorations and the player; no tooltip.
 */
final class PicturesProbe1710 implements CgPicturesProbe.Host {

    private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

    @Override
    public String absent() {
        return OpenGlHelper.isFramebufferEnabled() ? null : "framebuffers are off (Fast Render or an old driver)";
    }

    @Override
    public boolean knows(String item) {
        ItemStack stack = CgItems.toHost(CgItemStack.of(item, 1));
        return stack != null && stack.getItem() != null;
    }

    @Override
    public boolean draws(Kind kind) {
        return kind != Kind.TOOLTIP;
    }

    @Override
    public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                          Drawn out) {
        Minecraft mc = Minecraft.getMinecraft();
        long start = System.nanoTime();
        Gui.drawRect(x, y, x + w, y + h, background);
        Gui.drawRect(bx, by, bx + w, by + h, background);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        for (Cell cell : cells) draw(mc, cell);
        GL11.glPopMatrix();
        out.nanos = System.nanoTime() - start;
        out.drew = true;
    }

    @Override
    public void cells(CgHostDrawContext ctx, List<Cell> cells, Drawn out) {
        Minecraft mc = Minecraft.getMinecraft();
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
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                RenderItem items = RenderItem.getInstance();
                items.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack(cell), cell.x(), cell.y());
                if (cell.kind() == Kind.DECORATED) {
                    items.renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack(cell), cell.x(), cell.y());
                }
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                RenderHelper.disableStandardItemLighting();
                break;
            case PLAYER:
                if (mc.thePlayer != null) {
                    // Depth on, as in the inventory screen
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GuiInventory.func_147046_a(cell.x(), cell.y(), cell.count(), CgPicturesProbe.LOOK_X,
                            CgPicturesProbe.LOOK_Y, mc.thePlayer);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                }
                break;
            default:
        }
    }

    private ItemStack stack(Cell cell) {
        if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
        ItemStack stack = stacks.get(cell);
        if (stack == null) {
            CgItemStack s = CgItemStack.of(cell.item(), Math.max(1, cell.count()));
            if (cell.damage() > 0) s = s.withDamage(cell.damage());
            // Before 1.13 an enchantment is the game's number: 16 is sharpness
            if (cell.glint()) s = s.withEnchantment("16", 1);
            stack = CgItems.toHost(s);
            stacks.put(cell, stack);
        }
        return stack;
    }

    @Override
    public CgHostDrawContext hudContext(Object gui) {
        return CgPicturesProbe.GL_STATE;
    }
}
