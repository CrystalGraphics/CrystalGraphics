package com.crystalgraphics.render.stage;

/**
 * What a host knows about a stage's frame: its timing, its target and where it is seen from. Each
 * {@link CgRenderStage} owns one, which the host refills before every {@link CgRenderStage#fire}, so firing allocates
 * nothing.
 *
 * <pre>{@code
 * // the host
 * CgRenderStage.WORLD_OPAQUE.host().set(partialTick, width, height, mainFramebufferId)
 *         .view().set(camera.x, camera.y, camera.z, viewRotation, projection);
 * CgRenderStage.WORLD_OPAQUE.fire();
 *
 * // a renderer, or anything on the render thread afterwards
 * CgHostView camera = CgRenderStage.WORLD_OPAQUE.host().view();
 * }</pre>
 *
 * <ul>
 *   <li>Every field is the host's to set; anything else only reads, and copies what it keeps.</li>
 *   <li>It holds the stage's latest frame until the next: a world stage's camera is still the world's while the host
 *       draws its GUI under another projection.</li>
 * </ul>
 */
public final class CgHostFrame {

    private final CgHostView view = new CgHostView();
    private float partialTick;
    private int width;
    private int height;
    private int mainFramebuffer;

    /** The frame's interpolation between game ticks, 0 to 1. */
    public float partialTick() {
        return partialTick;
    }

    /** The host's main target, in pixels. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The GL framebuffer the host draws its world into, which holds the scene's depth. */
    public int mainFramebuffer() {
        return mainFramebuffer;
    }

    /** Where the frame is seen from: the host's camera, for a world stage. Filled by the host through its setters. */
    public CgHostView view() {
        return view;
    }

    /** Host side: this frame's timing and target. */
    public CgHostFrame set(float partialTick, int width, int height, int mainFramebuffer) {
        this.partialTick = partialTick;
        this.width = width;
        this.height = height;
        this.mainFramebuffer = mainFramebuffer;
        return this;
    }
}
