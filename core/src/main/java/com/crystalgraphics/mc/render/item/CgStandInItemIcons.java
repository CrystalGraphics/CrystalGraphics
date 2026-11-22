package com.crystalgraphics.mc.render.item;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.render.CgFrameClock;

/**
 * The host of {@link CgItemIcons} where no Minecraft runs (the GL debug harness, a test), beside
 * {@code CgStandInPictures}: each item a square of its own colour, an enchanted one with a band that moves, so pages,
 * looks, animation and the budget run as in a game.
 *
 * <pre>{@code
 * CgPlatform.provide(CgItemIcons.HOST, new CgStandInItemIcons());
 * }</pre>
 */
public final class CgStandInItemIcons implements CgItemIcons.Host {

    @Override
    public String absent() {
        return null;
    }

    /** The stack itself: the stand-in draws from the value. */
    @Override
    public Object toHost(CgItemStack stack) {
        return stack;
    }

    @Override
    public CgItemIcons.Look look(Object hostStack) {
        CgItemStack s = (CgItemStack) hostStack;
        boolean glint = !s.enchantments().isEmpty();
        return glint ? new CgItemIcons.Look(s.item() + "+glint", true, "glint") : CgItemIcons.Look.still(s.item());
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        CgItemStack s = (CgItemStack) hostStack;
        CgCanvas canvas = (CgCanvas) ctx.gui();
        canvas.fill(x, y, 16f, 16f, colour(s.item()));
        if (s.enchantments().isEmpty()) return;
        // A band crossing it every two seconds, as a glint moves
        float t = (float) (CgFrameClock.seconds() % 2.0) / 2f;
        canvas.fill(x, y + t * 12f, 16f, 4f, 0xC0B060FF);
    }

    /** An item's colour: opaque, its own, the same each run. */
    public static int colour(String item) {
        return 0xFF000000 | item.hashCode() & 0xFFFFFF;
    }
}
