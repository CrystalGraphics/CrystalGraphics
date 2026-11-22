package com.crystalgraphics.mc.legacy.platform;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.block.CgBlockPictures;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

/**
 * {@link CgBlockPictures} on legacy Forge, client: the block's item through the GUI's item path, which draws a chest by
 * its tile entity's item renderer, without the state's block entity data (NR4 decision 3).
 */
public final class BlockPicturesLegacy implements CgBlockPictures.Host {

    /** The block's item; null where it has none (a fluid, fire). */
    private static final class Prepared {
        @Nullable final ItemStack item;

        Prepared(@Nullable ItemStack item) {
            this.item = item;
        }
    }

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object prepare(CgBlockState s, @Nullable Object previous) {
        if (previous != null) return previous;
        CgBlocks.toHost(s);   // refused by name where the game has not got the block or a property
        ItemStack item = CgItems.toHost(CgItemStack.of(s.block(), 1));
        //? if <1.11 {
        /*return new Prepared(item);
        *///?} else {
        return new Prepared(item == null || item.isEmpty() ? null : item);
        //?}
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object prepared, float x, float y, float size) {
        ItemStack item = ((Prepared) prepared).item;
        if (item == null) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(size / 16f, size / 16f, 1);
        RenderHelper.enableGUIStandardItemLighting();
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(item, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();
    }
}
