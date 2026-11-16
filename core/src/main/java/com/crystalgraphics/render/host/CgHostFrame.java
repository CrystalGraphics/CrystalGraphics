package com.crystalgraphics.render.host;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.view.CgView;

import javax.annotation.Nullable;

/**
 * What a host says about a stage it fires: its timing, its target, where it is seen from and the world's sky. Each stage
 * a host fires has one ({@link CgStageHost#frame}), refilled before every firing, so firing allocates nothing.
 *
 * <pre>{@code
 * CgHostFrame frame = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
 * frame.set(partialTick, width, height, mainFramebufferId)               // the host's GL name for its target
 *         .camera().set(camera.x, camera.y, camera.z, viewRotation, projection);
 * frame.environment().clear().weather(rain, thunder, flash);               // and its other groups
 * CgStageHost.fire(CgStageHost.WORLD_OPAQUE);
 * }</pre>
 *
 * <ul>
 *   <li>The hosts' alone. A renderer reads the view it draws ({@code frame.view().camera()}, {@code environment()}),
 *       and anything else the game's view ({@code CgView.main()}).</li>
 *   <li>A world callback copies its camera, size, environment and textures into its view when it fires.</li>
 * </ul>
 */
public final class CgHostFrame {

    private final CgCamera camera = new CgCamera();
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
    private boolean drawsWorld = true;
    private CgView view = CgView.main();

    /**
     * The view this stage's firings draw: {@link CgView#main()} unless the host names another. A host drawing the world
     * twice a frame (an anaglyph's eyes, a portal) names one for each, or the second draws as {@link CgView#unkept()}.
     */
    public CgView view() {
        return view;
    }

    /** The view the next firings draw, kept until named again. */
    public CgHostFrame view(CgView view) {
        this.view = view;
        return this;
    }

    /**
     * Whether the host draws a world of its own into its target, the engine's drawn over it (Minecraft's terrain and
     * entities): true unless it says otherwise. A host whose world is wholly the engine's (the harness) says false, and its
     * views may then draw below 100% ({@code view.screenPercentage}); a host's own world cannot be drawn smaller.
     */
    public boolean drawsWorld() {
        return drawsWorld;
    }

    /** Whether the host draws a world of its own ({@link #drawsWorld()}), kept until said again. */
    public CgHostFrame drawsWorld(boolean draws) {
        this.drawsWorld = draws;
        return this;
    }

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

    /** The host's main target, imported: the same object while the host's target and size are the same. */
    public CgExternalTarget target() {
        if (target == null) target = CgExternalTarget.host(Math.max(framebuffer, 0), width, height);
        return target;
    }

    /** The format of the host target's depth, null where it has none. */
    @Nullable
    public CgFormat depthFormat() {
        CgTextureView depth = target().depth();
        return depth == null ? null : depth.texture().desc().format();
    }

    /** This frame's interpolation alone, its size and target kept. */
    public CgHostFrame partialTick(float partialTick) {
        this.partialTick = partialTick;
        return this;
    }

    /** The host GUI's device pixels per GUI pixel (Minecraft's GUI scale), 1 where the host has none. */
    public float guiScale() {
        return guiScale;
    }

    /** The GUI's scale, set by {@link CgStageHost#fireOverlay}. */
    public CgHostFrame guiScale(float scale) {
        this.guiScale = scale > 0f ? scale : 1f;
        return this;
    }

    /** Where the frame is seen from: the host's camera, its view relative to the camera's position. */
    public CgCamera camera() {
        return camera;
    }

    /** The world's sky, weather and fog, the camera, the player's settings and the clock this frame. */
    public CgHostEnvironment environment() {
        return environment;
    }

    /** The host's lightmap and block atlas, borrowed for sampling; 0 where the host gives none. */
    public CgHostTextures textures() {
        return textures;
    }

    /** This frame's timing and target, by the host's own name for it (a GL framebuffer, 0 the window's). */
    public CgHostFrame set(float partialTick, int width, int height, int mainFramebuffer) {
        if (mainFramebuffer != framebuffer || width != this.width || height != this.height) target = null;
        this.partialTick = partialTick;
        this.width = width;
        this.height = height;
        this.framebuffer = mainFramebuffer;
        return this;
    }

    /** This frame's timing and a target given whole: a framebuffer of ours ({@code framebuffer.external()}). */
    public CgHostFrame set(float partialTick, int width, int height, CgExternalTarget target) {
        this.partialTick = partialTick;
        this.width = width;
        this.height = height;
        this.framebuffer = -1;
        this.target = target;
        return this;
    }
}
