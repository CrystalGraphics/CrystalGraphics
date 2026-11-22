package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.item.CgItemIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import org.lwjgl.opengl.GL11;

/**
 * {@link CgItemIcons}' host on 1.7.10: a stack's look is its stamp with its first pass's icon (a clock's and a
 * compass's are sprites of many frames); its icon as the inventory draws a slot.
 */
public final class ItemIcons1710 implements CgItemIcons.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public CgItemIcons.Look look(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        IIcon icon = stack.getItem().getIcon(stack, 0);
        Key key = new Key(CgItems.stampOf(stack), icon);
        if (stack.hasEffect(0)) return new CgItemIcons.Look(key, true, "glint");
        if (MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.INVENTORY) != null) {
            return new CgItemIcons.Look(key, true, "custom renderer");
        }
        if (icon instanceof TextureAtlasSprite && ((TextureAtlasSprite) icon).getFrameCount() > 1) {
            return new CgItemIcons.Look(key, true, "animated sprite");
        }
        return CgItemIcons.Look.still(key);
    }

    /** As the inventory lights and depth-tests a slot's item. */
    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        try {
            RenderItem.getInstance().renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(),
                    (ItemStack) hostStack, x, y);
        } finally {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderHelper.disableStandardItemLighting();
        }
    }

    /** A look: the icon compared by identity. */
    private static final class Key {
        private final int stamp;
        private final Object icon;

        Key(int stamp, Object icon) {
            this.stamp = stamp;
            this.icon = icon;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).stamp == stamp && ((Key) o).icon == icon;
        }

        @Override
        public int hashCode() {
            return stamp * 31 + System.identityHashCode(icon);
        }
    }
}
