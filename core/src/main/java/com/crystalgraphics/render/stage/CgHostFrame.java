package com.crystalgraphics.render.stage;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.annotation.Nullable;

/**
 * What a host knows about a stage's frame: its timing, its target, where it is seen from and the world's sky. Each
 * {@link CgRenderStage} owns one, which the host refills before every {@link CgRenderStage#fire}, so firing allocates
 * nothing.
 *
 * <pre>{@code
 * // the host
 * CgRenderStage.WORLD_OPAQUE.host().set(partialTick, width, height, mainFramebufferId)   // the host's GL name
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
    /** The host's name for its target, -1 where {@link #target} was given whole. */
    private int framebuffer = -1;
    @Nullable
    private CgExternalTarget target;
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

    /**
     * The host's main target, imported: what a world stage draws into and what holds the scene's depth. The same
     * object while the host's target and size are the same. Render thread.
     *
     * <pre>{@code
     * if (frame.host().target().lendsAttachments()) { ... }   // a scene of ours may draw beside its depth
     * }</pre>
     */
    public CgExternalTarget target() {
        if (target == null) target = CgExternalTarget.host(Math.max(framebuffer, 0), width, height);
        return target;
    }

    /**
     * The type of the host target's depth, null where it has none: what a scene of ours copies that depth into where the
     * target lends none ({@code target().lendsAttachments()}), since a depth blit refuses formats that differ.
     */
    @Nullable
    public CgTextureType depthType() {
        CgTextureView depth = target().depth();
        return depth == null ? null : CgTextureType.of(depth.texture().desc().format());
    }

    /** The host's name for its target, -1 where it was given whole: what a rehearsal hands back unchanged. */
    int framebuffer() {
        return framebuffer;
    }

    /** Host side: this frame's interpolation alone, its size and target kept. */
    public CgHostFrame partialTick(float partialTick) {
        this.partialTick = partialTick;
        return this;
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

    /** Host side: this frame's timing and target, by the host's own name for it (a GL framebuffer, 0 the window's). */
    public CgHostFrame set(float partialTick, int width, int height, int mainFramebuffer) {
        if (mainFramebuffer != framebuffer || width != this.width || height != this.height) target = null;
        this.partialTick = partialTick;
        this.width = width;
        this.height = height;
        this.framebuffer = mainFramebuffer;
        return this;
    }

    /**
     * Host side: this frame's timing and a target given whole: a framebuffer of ours a viewport fires a stage into
     * ({@code framebuffer.external()}).
     */
    public CgHostFrame set(float partialTick, int width, int height, CgExternalTarget target) {
        this.partialTick = partialTick;
        this.width = width;
        this.height = height;
        this.framebuffer = -1;
        this.target = target;
        return this;
    }
}
