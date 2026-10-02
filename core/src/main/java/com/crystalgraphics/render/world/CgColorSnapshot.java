package com.crystalgraphics.render.world;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * The scene's colour as it stands at a world stage, for {@code cg_SceneColor}: blitted from the host's main framebuffer
 * by a callback pass, and bound by every world pass at {@code CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT}. RGBA8 and
 * linearly filtered, so a distortion samples between pixels.
 *
 * <ul>
 *   <li>Render thread. {@link #run} after {@link #source}: the pair is the callback a pass records.</li>
 *   <li>Screen-sized: the framebuffer registry resizes it with the window.</li>
 * </ul>
 */
final class CgColorSnapshot implements CgTexture, Runnable {

    private static final CgFrameBufferFormat FORMAT =
            CgFrameBufferFormat.builder("cg_color_snapshot").color(0, CgTextureType.RGBA8).build();

    private CgFrameBuffer storage;
    private int sourceFbo;

    /** The framebuffer the next {@link #run} blits from. */
    CgColorSnapshot source(int sourceFbo) {
        this.sourceFbo = sourceFbo;
        return this;
    }

    /** Copies the source's colour in. A callback pass's body. */
    @Override
    public void run() {
        if (storage == null) storage = CgFrameBuffer.createScreenSized("cg_color_snapshot", FORMAT);
        storage.blitFrom(sourceFbo, CgGL.GL_COLOR_BUFFER_BIT);
    }

    /** Forgets the storage, which the framebuffer registry frees at teardown. */
    void dropStorage() {
        storage = null;
    }

    private CgTexture color() {
        return storage == null ? null : storage.getColorTexture(0);
    }

    @Override
    public void bind() {
        CgTexture color = color();
        if (color != null) color.bind();
    }

    @Override
    public void bind(int unit) {
        CgTexture color = color();
        if (color != null) color.bind(unit);
    }

    @Override
    public int getId() {
        CgTexture color = color();
        return color == null ? 0 : color.getId();
    }

    @Override
    public int getWidth() {
        return storage == null ? 0 : storage.getWidth();
    }

    @Override
    public int getHeight() {
        return storage == null ? 0 : storage.getHeight();
    }

    @Override
    public int getTarget() {
        return CgGL.GL_TEXTURE_2D;
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    /** Refused: the snapshot belongs to the world renderer. */
    @Override
    public void delete() {
        throw new UnsupportedOperationException("the colour snapshot belongs to CgWorldRenderer");
    }
}
