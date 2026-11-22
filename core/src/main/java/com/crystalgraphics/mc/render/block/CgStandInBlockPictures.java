package com.crystalgraphics.mc.render.block;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;

/**
 * The host of {@link CgBlockPictures} where no Minecraft runs (the GL debug harness, a test): a block's three visible
 * faces in the block's own colour, lighter on top, a band across the front where the state has block entity data.
 *
 * <pre>{@code
 * CgPlatform.provide(CgBlockPictures.HOST, new CgStandInBlockPictures());
 * }</pre>
 */
public final class CgStandInBlockPictures implements CgBlockPictures.Host {

    @Override
    public String absent() {
        return null;
    }

    /** The state itself: the stand-in draws from the value. */
    @Override
    public Object prepare(CgBlockState state, Object previous) {
        return state;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object prepared, float x, float y, float size) {
        CgBlockState state = (CgBlockState) prepared;
        CgCanvas canvas = (CgCanvas) ctx.gui();
        int colour = 0xFF000000 | (state.block().hashCode() * 0x9E3779B1 >>> 8) & 0x7F7F7F;
        float s = size * 0.8f, ox = x + (size - s) / 2, oy = y + (size - s) / 2;
        canvas.fill(ox, oy, s, s * 0.3f, colour | 0x00404040);
        canvas.fill(ox, oy + s * 0.3f, s / 2, s * 0.7f, colour);
        canvas.fill(ox + s / 2, oy + s * 0.3f, s / 2, s * 0.7f, colour & 0xFF5F5F5F);
        if (state.hasBlockEntity()) canvas.fill(ox, oy + s * 0.6f, s, s * 0.08f, 0xFFE0C060);
    }
}
