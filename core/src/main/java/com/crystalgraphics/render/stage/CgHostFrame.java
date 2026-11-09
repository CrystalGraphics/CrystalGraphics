package com.crystalgraphics.render.stage;

/**
 * What a host knows about a stage's frame: its timing, its target, where it is seen from and the world's sky. Each
 * {@link CgRenderStage} owns one, which the host refills before every {@link CgRenderStage#fire}, so firing allocates
 * nothing.
 *
 * <pre>{@code
 * // the host
 * CgRenderStage.WORLD_OPAQUE.host().set(partialTick, width, height, mainFramebufferId)
 *         .view().set(camera.x, camera.y, camera.z, viewRotation, projection);
 * CgRenderStage.WORLD_OPAQUE.fire();
 *
 * CgRenderStage.WORLD_OPAQUE.host().environment().clear().weather(rain, thunder, flash);   // and its other groups
 *
 * // a renderer, or anything on the render thread afterwards
 * CgHostView camera = CgRenderStage.WORLD_OPAQUE.host().view();
 * CgHostEnvironment world = CgRenderStage.WORLD_OPAQUE.host().environment();
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
    private final CgHostEnvironment environment = new CgHostEnvironment();
    private final CgHostTextures textures = new CgHostTextures();
    private float partialTick;
    private int width;
    private int height;
    private int mainFramebuffer;
    private float guiScale = 1f;

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

    /**
     * The host GUI's device pixels per GUI pixel (Minecraft's GUI scale), 1 where the host has none. A GUI stage's
     * canvas is in device pixels; {@code CgStageFrame.guiScaledCanvas()} draws in GUI-scaled pixels by it, and a size in
     * GUI-scaled pixels is this many window pixels:
     *
     * <pre>{@code
     * float hotbarHeight = 22 * frame.host().guiScale();   // in the window's pixels
     * }</pre>
     */
    public float guiScale() {
        return guiScale;
    }

    /** Host side: the GUI's scale, set by {@link CgRenderStage#fireOverlay}. */
    public CgHostFrame guiScale(float scale) {
        this.guiScale = scale > 0f ? scale : 1f;
        return this;
    }

    /** Where the frame is seen from: the host's camera, for a world stage. Filled by the host through its setters. */
    public CgHostView view() {
        return view;
    }

    /** The world's sky, weather and fog, the camera, the player's settings and the clock this frame; absent where unknown. */
    public CgHostEnvironment environment() {
        return environment;
    }

    /** The host's lightmap and block atlas, borrowed for sampling; 0 where the host gives none. */
    public CgHostTextures textures() {
        return textures;
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
