package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.item.CgItemIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
//? if <1.10 {
/*import net.minecraft.client.resources.model.IBakedModel;
*///?} else {
import net.minecraft.client.renderer.block.model.IBakedModel;
//?}

/**
 * {@link CgItemIcons}' host on Forge 1.8.9 to 1.12.2: a stack's look is its stamp with the model its overrides resolve
 * to; its icon as the inventory draws a slot.
 */
public final class ItemIconsLegacy implements CgItemIcons.Host {

    @Override
    public String absent() {
        return null;
    }

    private final CgItemIcons.Looks looks = new CgItemIcons.Looks();

    @Override
    public CgItemIcons.Look look(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getMinecraft();
        //? if <1.9 {
        /*// No item overrides before 1.9: the mesher's model is the stack's
        IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
        *///?} else {
        IBakedModel model = mc.getRenderItem().getItemModelWithOverrides(stack, ClientLegacy.world(), ClientLegacy.player());
        //?}
        int stamp = CgItems.stampOf(stack);
        CgItemIcons.Look known = looks.find(stamp, 0, model);
        if (known != null) return known;
        String why = stack.hasEffect() ? "glint"
                : model.isBuiltInRenderer() ? "custom renderer"
                : model.getParticleTexture().getFrameCount() > 1 ? "animated sprite"
                : null;
        return looks.of(stamp, 0, model, why != null, why);
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI((ItemStack) hostStack, x, y);
    }

    /** Its decorations: the stack's data with its count, drawn again every frame while the item cools down (1.10+). */
    @Override
    public CgItemIcons.Look overlayLook(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        int count = count(stack);
        boolean cooling = cooldown(stack) > 0f;
        if (count <= 1 && !stack.getItem().showDurabilityBar(stack) && !cooling) return null;
        return looks.of(CgItems.stampOf(stack), count, null, cooling, cooling ? "cooldown" : null);
    }

    @Override
    public void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        //? if <1.12 {
        /*mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, (ItemStack) hostStack, x, y);
        *///?} else {
        mc.getRenderItem().renderItemOverlays(mc.fontRenderer, (ItemStack) hostStack, x, y);
        //?}
    }

    private static int count(ItemStack stack) {
        //? if <1.11 {
        /*return stack.stackSize;
        *///?} else {
        return stack.getCount();
        //?}
    }

    private static float cooldown(ItemStack stack) {
        //? if <1.10 {
        /*return 0f;
        *///?} else {
        return ClientLegacy.player() == null ? 0f : ClientLegacy.player().getCooldownTracker().getCooldown(stack.getItem(), 0f);
        //?}
    }
}
