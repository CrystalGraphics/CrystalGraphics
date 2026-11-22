package com.crystalgraphics.mc.render.custom;

import javax.annotation.Nullable;

/**
 * What a {@link CgPictureRenderer} draws with: Minecraft's own GUI objects, pointed at the picture. Minecraft's types
 * cross as {@code Object}; cast to the era's, as a mod's GUI code already is per era.
 *
 * <pre>{@code
 * // 1.20.1 - 1.21.11
 * GuiGraphics g = (GuiGraphics) ctx.gui();
 * g.fill(0, 0, (int) ctx.width(), (int) ctx.height(), 0x80000000);
 * g.drawString(Minecraft.getInstance().font, "Radar", 4, 4, 0xFFFFFFFF);
 *
 * // 26.1 on: the GUI records, then renders
 * GuiGraphicsExtractor g = (GuiGraphicsExtractor) ctx.gui();
 *
 * // 1.15.2 - 1.19.4: no GuiGraphics; a pose stack and a buffer source
 * PoseStack pose = (PoseStack) ctx.poseStack();
 * MultiBufferSource buffers = (MultiBufferSource) ctx.buffers();
 * }</pre>
 *
 * <ul>
 *   <li>The units are the rect's on the canvas that shows it: {@code (0, 0)} its top-left, {@link #width} by
 *       {@link #height}, {@link #scale} pixels a unit. Minecraft's own GUI scale plays no part.</li>
 *   <li>Where an era has no such object the answer is null: legacy and 1.7.10 draw through {@code GlStateManager} and
 *       {@code Tessellator} at once, as their GUI code does, and so do 1.13.2 - 1.14.4.</li>
 *   <li>Valid only during the draw it was given to.</li>
 * </ul>
 */
public interface CgHostDrawContext {

    /** The era's GUI drawing object: {@code GuiGraphics} (1.20.1 - 1.21.11), {@code GuiGraphicsExtractor} (26.1 on); else null. */
    @Nullable
    Object gui();

    /** The era's {@code PoseStack} (1.15.2 on), already at the picture's origin; null before. */
    @Nullable
    Object poseStack();

    /** The era's buffer source ({@code MultiBufferSource.BufferSource}, 1.15.2 - 1.21.5); null where it has none. */
    @Nullable
    Object buffers();

    /** The picture's width, in units. */
    float width();

    /** The picture's height, in units. */
    float height();

    /** Pixels a unit. */
    float scale();

    /** How far the game is between its last tick and the next, 0 to 1. */
    float partialTick();
}
