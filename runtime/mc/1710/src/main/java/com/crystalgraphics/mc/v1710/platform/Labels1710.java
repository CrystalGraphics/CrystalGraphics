package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.label.CgLabels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;

/** {@link CgLabels}' host on 1.7.10: a string in the game's font, drawn at once as its GUI code draws. */
public final class Labels1710 implements CgLabels.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public int width(String text) {
        return Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
    }

    @Override
    public int lineHeight() {
        return Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT;
    }

    @Override
    public String translate(String key) {
        return I18n.format(key);
    }

    @Override
    public void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow) {
        Minecraft.getMinecraft().fontRenderer.drawString(text, 0, 0, argb, shadow);
    }
}
