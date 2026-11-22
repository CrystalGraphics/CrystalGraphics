package com.crystalgraphics.mc.render.custom;

/**
 * A mod's own Minecraft code drawing a {@link CgCustomPicture}. Unreal's {@code UThumbnailRenderer::Draw}; Mojang's
 * {@code PictureInPictureRenderer}.
 *
 * <pre>{@code
 * CgPictureRenderer radar = ctx -> RadarScreen.draw((GuiGraphics) ctx.gui(), ctx.width(), ctx.height());
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, at the draw point, before the engine's device section: Minecraft's state is its own.</li>
 *   <li>A throw marks the picture failed: it shows the missing texture and is logged once, retried after a resource
 *       reload. Whatever it changed through Minecraft's API is put back.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgPictureRenderer {

    /** Draws the picture into the target {@code ctx} points Minecraft's GUI at. */
    void draw(CgHostDrawContext ctx);
}
