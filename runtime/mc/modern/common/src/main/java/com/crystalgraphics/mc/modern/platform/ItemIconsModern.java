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
//? if >=1.21.6 {
/*import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import java.util.ArrayList;
import java.util.List;
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

    private final CgItemIcons.Looks looks = new CgItemIcons.Looks();
    //? if >=1.21.6 {
    /*private final IdentityState state = new IdentityState();
    *///?}

    @Override
    public CgItemIcons.Look look(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        //? if <1.14 {
        /*return looks.of(CgItems.stampOf(stack), 0, null, true, "unknown");
        *///?} elif <1.17 {
        /*return look(stack, mc.getItemRenderer().getModel(stack, mc.level, mc.player));
        *///?} elif <1.21.4 {
        return look(stack, mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0));
        //?} elif <1.21.6 {
        /*return looks.of(CgItems.stampOf(stack), 0, null, true, "unknown");
        *///?} else {
        /*// Mojang's own key, the model identity its GUI atlas keys by, recorded into one state: a list it fills
        state.clear();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
        boolean animated = state.isAnimated();
        return looks.of(0, 0, state.identity, animated, animated ? "animated model" : null);
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
    public CgItemIcons.Look overlayLook(Object hostStack, String countText) {
        ItemStack stack = (ItemStack) hostStack;
        float cooldown = cooldown(stack);
        boolean decorated = countText != null || stack.getCount() > 1 || bar(stack) || cooldown > 0f || DECORATORS;
        if (!decorated) return null;
        boolean cooling = cooldown > 0f;
        return looks.of(CgItems.stampOf(stack), stack.getCount(), countText, cooling, cooling ? "cooldown" : null);
    }

    @Override
    public void drawOverlay(CgHostDrawContext ctx, Object hostStack, int x, int y, String countText) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        //? if >=1.14 <1.19.4 {
        /*mc.getItemRenderer().renderGuiItemDecorations(mc.font, stack, x, y, countText);
        *///?} elif >=1.19.4 <1.20 {
        /*mc.getItemRenderer().renderGuiItemDecorations((PoseStack) ctx.poseStack(), mc.font, stack, x, y, countText);
        *///?} elif >=1.20 <26.1 {
        ((GuiGraphics) ctx.gui()).renderItemDecorations(mc.font, stack, x, y, countText);
        //?} elif >=26.1 {
        /*((GuiGraphicsExtractor) ctx.gui()).itemDecorations(mc.font, stack, x, y, countText);
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
    // The stack's stamp (item and data, count left out) with the model its overrides resolve to, which fix how it
    // looks: a clock's frame is a model of its own
    private CgItemIcons.Look look(ItemStack stack, BakedModel model) {
        int stamp = CgItems.stampOf(stack);
        CgItemIcons.Look known = looks.find(stamp, 0, model);
        if (known != null) return known;
        String why = stack.hasFoil() ? "glint"
                : model.isCustomRenderer() ? "custom renderer"
                : animatedSprite(model) ? "animated sprite"
                : null;
        return looks.of(stamp, 0, model, why != null, why);
    }

    private static boolean animatedSprite(BakedModel model) {
        //? if <1.17 {
        /*return model.getParticleIcon().getFrameCount() > 1;
        *///?} elif <1.19.3 {
        /*return false; // no public frame count on a vanilla sprite here
        *///?} else {
        return model.getParticleIcon().contents().getUniqueFrames().count() > 1;
        //?}
    }
    //?}

    //? if >=1.21.6 {
    /*/^* A render state recording the model identity into a list of its own, emptied with the state: one, reused. ^/
    private static final class IdentityState extends ItemStackRenderState {
        final List<Object> identity = new ArrayList<>();

        @Override
        public void appendModelIdentityElement(Object element) {
            identity.add(element);
        }

        @Override
        public void clear() {
            super.clear();
            identity.clear();
        }
    }
    *///?}
}
