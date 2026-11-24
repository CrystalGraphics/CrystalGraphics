package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.label.CgLabels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
//? if >=1.16 <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.20 <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
*///?}

/** {@link CgLabels}' host on the modern tree: a string in the game's font, as a screen draws its title. */
public final class LabelsModern implements CgLabels.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public int width(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    @Override
    public int lineHeight() {
        return Minecraft.getInstance().font.lineHeight;
    }

    @Override
    public String translate(String key) {
        return I18n.get(key);
    }

    @Override
    public void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow) {
        //? if <1.16 {
        /*if (shadow) Minecraft.getInstance().font.drawShadow(text, 0f, 0f, argb);
        else Minecraft.getInstance().font.draw(text, 0f, 0f, argb);
        *///?} elif <1.20 {
        /*PoseStack pose = (PoseStack) ctx.poseStack();
        if (shadow) Minecraft.getInstance().font.drawShadow(pose, text, 0f, 0f, argb);
        else Minecraft.getInstance().font.draw(pose, text, 0f, 0f, argb);
        *///?} elif <26.1 {
        ((GuiGraphics) ctx.gui()).drawString(Minecraft.getInstance().font, text, 0, 0, argb, shadow);
        //?} else {
        /*((GuiGraphicsExtractor) ctx.gui()).text(Minecraft.getInstance().font, Component.literal(text), 0, 0, argb, shadow);
        *///?}
    }
}
