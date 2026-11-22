package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.item.CgItemIcons;
import com.crystalgraphics.platform.service.CgModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
//? if >=1.14 <1.21.4 {
import net.minecraft.client.resources.model.BakedModel;
//?}
//? if >=1.19.4 <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.20 <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}
//? if >=1.21.6 <1.21.8 {
/*import net.minecraft.client.renderer.item.ItemStackRenderState;
*///?}
//? if >=1.21.8 {
/*import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
*///?}
//? if >=1.21.6 {
/*import net.minecraft.world.item.ItemDisplayContext;
*///?}

/**
 * {@link CgItemIcons}' host on the modern tree: a stack's look as Minecraft resolves it, and its icon as the inventory
 * draws a slot. From 1.21.6 the look is Mojang's own (the model identity its GUI atlas keys by, and its animated flag);
 * 1.14 to 1.21.3 the stack's stamp with the model its overrides resolve to; 1.21.4 and 1.21.5 resolve models through a
 * render state with no identity to read, so their icons count as animated.
 */
public final class ItemIconsModern implements CgItemIcons.Host {

    @Override
    public String absent() {
        //? if <1.14 {
        /*return "1.13.2's item stacks cannot be made yet: CgItems writes no NBT there (NR7)";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public CgItemIcons.Look look(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        //? if <1.14 {
        /*return new CgItemIcons.Look(stack, true, "unknown");
        *///?} elif <1.17 {
        /*BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player);
        return look(stack, model, model.getParticleIcon().getFrameCount() > 1);
        *///?} elif <1.19.3 {
        /*// No public frame count on a vanilla sprite here: a sprite's animation is not seen
        BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        return look(stack, model, false);
        *///?} elif <1.21.4 {
        BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        return look(stack, model, model.getParticleIcon().contents().getUniqueFrames().count() > 1);
        //?} elif <1.21.6 {
        /*return new CgItemIcons.Look(CgItems.stampOf(stack), true, "unknown");
        *///?} elif <1.21.8 {
        /*ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
        return mojang(state.getModelIdentity(), state.isAnimated());
        *///?} else {
        /*TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
        return mojang(state.getModelIdentity(), state.isAnimated());
        *///?}
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        ItemStack stack = (ItemStack) hostStack;
        //? if >=1.14 <1.19.4 {
        /*Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(stack, x, y);
        *///?} elif >=1.19.4 <1.20 {
        /*Minecraft.getInstance().getItemRenderer().renderAndDecorateItem((PoseStack) ctx.poseStack(), stack, x, y);
        *///?} elif >=1.20 <26.1 {
        ((GuiGraphics) ctx.gui()).renderItem(stack, x, y);
        //?} elif >=26.1 {
        /*((GuiGraphicsExtractor) ctx.gui()).item(stack, x, y);
        *///?}
    }

    /**
     * Its decorations: the stack's data (its bar follows it) with its count, drawn again every frame while the item cools
     * down. A loader with item decorators (Forge, NeoForge) may decorate any stack, so only Fabric's undecorated stacks
     * draw none.
     */
    @Override
    public CgItemIcons.Look overlayLook(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        float cooldown = cooldown(stack);
        boolean decorated = stack.getCount() > 1 || bar(stack) || cooldown > 0f || DECORATORS;
        if (!decorated) return null;
        Long key = (long) CgItems.stampOf(stack) << 32 | stack.getCount() & 0xFFFFFFFFL;
        return cooldown > 0f ? new CgItemIcons.Look(key, true, "cooldown") : CgItemIcons.Look.still(key);
    }

    @Override
    public void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        //? if >=1.14 <1.19.4 {
        /*mc.getItemRenderer().renderGuiItemDecorations(mc.font, stack, x, y);
        *///?} elif >=1.19.4 <1.20 {
        /*mc.getItemRenderer().renderGuiItemDecorations((PoseStack) ctx.poseStack(), mc.font, stack, x, y);
        *///?} elif >=1.20 <26.1 {
        ((GuiGraphics) ctx.gui()).renderItemDecorations(mc.font, stack, x, y);
        //?} elif >=26.1 {
        /*((GuiGraphicsExtractor) ctx.gui()).itemDecorations(mc.font, stack, x, y);
        *///?}
    }

    /** Whether the loader has item decorators: Forge's and NeoForge's {@code IItemDecorator}. */
    private static final boolean DECORATORS = CgModLoader.name().toLowerCase(Locale.ROOT).contains("forge");

    // The bar vanilla draws: a durability bar, or from 1.17 any item's own
    private static boolean bar(ItemStack stack) {
        //? if >=1.17 {
        return stack.isBarVisible();
        //?} else {
        /*return stack.isDamaged();
        *///?}
    }

    private static float cooldown(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0f;
        //? if <1.21.3 {
        return mc.player.getCooldowns().getCooldownPercent(stack.getItem(), 0f);
        //?} else {
        /*return mc.player.getCooldowns().getCooldownPercent(stack, 0f);
        *///?}
    }

    //? if >=1.14 <1.21.4 {
    // The stack's stamp (item and data, count left out) with the model its overrides resolve to: a clock's frame is a
    // model of its own
    private static CgItemIcons.Look look(ItemStack stack, BakedModel model, boolean animatedSprite) {
        Key key = new Key(CgItems.stampOf(stack), model);
        if (stack.hasFoil()) return new CgItemIcons.Look(key, true, "glint");
        if (model.isCustomRenderer()) return new CgItemIcons.Look(key, true, "custom renderer");
        if (animatedSprite) return new CgItemIcons.Look(key, true, "animated sprite");
        return CgItemIcons.Look.still(key);
    }

    /** A look below 1.21.4: the model compared by identity, as a baked model has no equality of its own. */
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
    //?}

    //? if >=1.21.6 {
    /*private static CgItemIcons.Look mojang(Object identity, boolean animated) {
        return new CgItemIcons.Look(identity, animated, animated ? "animated model" : null);
    }
    *///?}
}
