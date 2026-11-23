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

    private final CgItemIcons.Looks looks = new CgItemIcons.Looks();

    /** The stack's stamp with its icon, which fix how it looks. */
    @Override
    public CgItemIcons.Look look(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        IIcon icon = stack.getItem().getIcon(stack, 0);
        int stamp = CgItems.stampOf(stack);
        CgItemIcons.Look known = looks.find(stamp, 0, icon);
        if (known != null) return known;
        String why = stack.hasEffect(0) ? "glint"
                : MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.INVENTORY) != null ? "custom renderer"
                : icon instanceof TextureAtlasSprite && ((TextureAtlasSprite) icon).getFrameCount() > 1 ? "animated sprite"
                : null;
        return looks.of(stamp, 0, icon, why != null, why);
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

    /** Its decorations: the stack's data with its count; 1.7.10 has no cooldowns. */
    @Override
    public CgItemIcons.Look overlayLook(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        if (stack.stackSize <= 1 && !stack.getItem().showDurabilityBar(stack)) return null;
        return looks.of(CgItems.stampOf(stack), stack.stackSize, null, false, null);
    }

    @Override
    public void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderItem.getInstance().renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), (ItemStack) hostStack, x, y);
    }
}
