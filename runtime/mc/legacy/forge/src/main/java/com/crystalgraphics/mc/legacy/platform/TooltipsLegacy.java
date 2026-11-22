package com.crystalgraphics.mc.legacy.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.tooltip.CgTooltips;
import com.crystalgraphics.text.richtext.CgMinecraftColorCodeParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
//? if >=1.12 {
import net.minecraft.client.util.ITooltipFlag;
//?}

/**
 * {@link CgTooltips}' host on Forge 1.8.9 to 1.12.2: a stack's tooltip as a screen measures and draws it, through
 * Forge's tooltip events; its lines read as their colour codes.
 */
public final class TooltipsLegacy implements CgTooltips.Host {

    /** {@code renderToolTip} is a screen's: one never shown, sized as the picture. */
    private static final class Tips extends GuiScreen {
        void tip(ItemStack stack, int x, int y) {
            renderToolTip(stack, x, y);
        }
    }

    private final Tips tips = new Tips();
    /** Minecraft's colour code for grey: a tooltip's lines under its name. */
    private static final String GREY = "§7";

    @Override
    public String absent() {
        return null;
    }

    // A screen's box: the widest line, 10 units a line and 2 more under the first, 4 around the text
    @Override
    public int[] measure(Object hostStack) {
        List<String> lines = coded((ItemStack) hostStack);
        if (lines.isEmpty()) return null;
        int w = 0;
        for (String line : lines) w = Math.max(w, font().getStringWidth(line));
        return new int[] {w + 8, lines.size() * 10 + (lines.size() > 1 ? 2 : 0) - 2 + 8};
    }

    // Text 12 units right of and 12 above the pointer, the box 4 around it: a box at (x, y) is a pointer at (x - 8, y + 16)
    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        // The game's own GUI size, never the picture's: Forge wraps a tooltip to what the screen leaves it, and a first
        // measure's 1x1 picture wrapped forever
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution screen = new ScaledResolution(mc);
        tips.setWorldAndResolution(mc, screen.getScaledWidth(), screen.getScaledHeight());
        tips.tip((ItemStack) hostStack, x - 8, y + 16);
        RenderHelper.disableStandardItemLighting();
    }

    @Override
    public List<CgStyledText> lines(Object hostStack) {
        CgMinecraftColorCodeParser parser = new CgMinecraftColorCodeParser();
        List<CgStyledText> out = new ArrayList<>();
        for (String line : coded((ItemStack) hostStack)) out.add(parser.parse(line));
        return out;
    }

    // The lines renderToolTip draws, coloured as it colours them: the name by rarity, the rest grey
    private static List<String> coded(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        //? if >=1.12 {
        List<String> lines = new ArrayList<>(stack.getTooltip(ClientLegacy.player(), mc.gameSettings.advancedItemTooltips
                ? ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL));
        //?} else {
        /*List<String> lines = new ArrayList<>(stack.getTooltip(ClientLegacy.player(), mc.gameSettings.advancedItemTooltips));
        *///?}
        for (int i = 0; i < lines.size(); i++) lines.set(i, (i == 0 ? rarity(stack) : GREY) + lines.get(i));
        return lines;
    }

    private static String rarity(ItemStack stack) {
        //? if <1.12 {
        /*return stack.getRarity().rarityColor.toString();
        *///?} else {
        return stack.getRarity().color.toString();
        //?}
    }

    private static FontRenderer font() {
        //? if <1.12 {
        /*return Minecraft.getMinecraft().fontRendererObj;
        *///?} else {
        return Minecraft.getMinecraft().fontRenderer;
        //?}
    }
}
