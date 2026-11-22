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
        Key key = new Key(CgItems.stampOf(stack), model);
        if (stack.hasEffect()) return new CgItemIcons.Look(key, true, "glint");
        if (model.isBuiltInRenderer()) return new CgItemIcons.Look(key, true, "custom renderer");
        if (model.getParticleTexture().getFrameCount() > 1) return new CgItemIcons.Look(key, true, "animated sprite");
        return CgItemIcons.Look.still(key);
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI((ItemStack) hostStack, x, y);
    }

    /** A look: the model compared by identity, as a baked model has no equality of its own. */
    private static final class Key {
        private final int stamp;
        private final Object model;

        Key(int stamp, Object model) {
            this.stamp = stamp;
            this.model = model;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).stamp == stamp && ((Key) o).model == model;
        }

        @Override
        public int hashCode() {
            return stamp * 31 + System.identityHashCode(model);
        }
    }
}
