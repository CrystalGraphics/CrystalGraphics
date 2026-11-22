package com.crystalgraphics.mc.v1710.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.tooltip.CgTooltips;
import com.crystalgraphics.text.richtext.CgMinecraftColorCodeParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

/**
 * {@link CgTooltips}' host on 1.7.10: a stack's tooltip as a screen measures and draws it, through Forge's tooltip
 * event; its lines read as their colour codes.
 */
public final class Tooltips1710 implements CgTooltips.Host {

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
        for (String line : lines) w = Math.max(w, Minecraft.getMinecraft().fontRenderer.getStringWidth(line));
        return new int[] {w + 8, lines.size() * 10 + (lines.size() > 1 ? 2 : 0) - 2 + 8};
    }

    // Text 12 units right of and 12 above the pointer, the box 4 around it: a box at (x, y) is a pointer at (x - 8, y + 16)
    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        // The game's own GUI size, never the picture's: where a tooltip goes on screen is the screen's to decide
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution screen = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
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
    @SuppressWarnings("unchecked")
    private static List<String> coded(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        List<String> lines = new ArrayList<>(stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips));
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, (i == 0 ? stack.getRarity().rarityColor.toString() : GREY) + lines.get(i));
        }
        return lines;
    }
}
