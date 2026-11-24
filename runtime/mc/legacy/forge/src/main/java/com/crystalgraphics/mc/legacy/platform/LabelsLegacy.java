package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.label.CgLabels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;

/** {@link CgLabels}' host on legacy Forge: a string in the game's font, drawn at once as its GUI code draws. */
public final class LabelsLegacy implements CgLabels.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public int width(String text) {
        return font().getStringWidth(text);
    }

    @Override
    public int lineHeight() {
        return font().FONT_HEIGHT;
    }

    @Override
    public String translate(String key) {
        return I18n.format(key);
    }

    @Override
    public void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow) {
        font().drawString(text, 0f, 0f, argb, shadow);
    }

    private static FontRenderer font() {
        //? if <1.12 {
        /*return Minecraft.getMinecraft().fontRendererObj;
        *///?} else {
        return Minecraft.getMinecraft().fontRenderer;
        //?}
    }
}
