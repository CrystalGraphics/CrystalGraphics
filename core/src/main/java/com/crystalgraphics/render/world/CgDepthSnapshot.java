package com.crystalgraphics.render.world;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * The scene's depth as the host left it, for {@code cg_DepthBuffer}: blitted from the host's main framebuffer by a
 * callback pass, and bound by every world pass at {@code CgBindingPoints.DEPTH_TEXTURE_UNIT}. Its storage follows the
 * source's depth format (26.2's main target is {@code D32_FLOAT}, earlier ones 24-bit), since a depth blit between
 * formats fails; the texture a pass binds is this object, so a format change never leaves a pass holding a deleted one.
 *
 * <ul>
 *   <li>Render thread. {@link #run} after {@link #source}: the pair is the callback a pass records.</li>
 *   <li>Screen-sized: the framebuffer registry resizes it with the window.</li>
 * </ul>
 */
final class CgDepthSnapshot implements CgTexture, Runnable {

    private CgFrameBuffer storage;
    private CgTextureType type = CgTextureType.DEPTH24;
    private int sourceFbo;
    /** The source the format and blit mask were last matched to; -1 before the first. */
    private int probedSource = -1;
    private int mask = CgGL.GL_DEPTH_BUFFER_BIT;

    /** The framebuffer the next {@link #run} blits from. */
    CgDepthSnapshot source(int sourceFbo) {
        this.sourceFbo = sourceFbo;
        return this;
    }

    /** Copies the source's depth in. A callback pass's body. */
    @Override
    public void run() {
        if (sourceFbo != probedSource) {
            CgTextureType sourceType = CgFrameBuffer.depthTypeOf(sourceFbo);
            if (sourceType != null && sourceType != type) {
                if (storage != null) storage.delete();
                storage = null;
                type = sourceType;
            }
            mask = CgFrameBuffer.optimalDepthBlitMask(sourceFbo);
            probedSource = sourceFbo;
        }
        if (storage == null) storage = CgFrameBuffer.createScreenSized("cg_depth_snapshot", format(type));
        storage.blitFrom(sourceFbo, mask);
    }

    private static CgFrameBufferFormat format(CgTextureType depth) {
        return depth == CgTextureType.DEPTH24
                ? CgFrameBufferFormat.DEPTH
                : CgFrameBufferFormat.builder("depth_" + depth.name()).depth(depth).build();
    }

    /** Forgets the storage, which the framebuffer registry frees at teardown. */
    void dropStorage() {
        storage = null;
        probedSource = -1;
    }

    private CgTexture depth() {
        return storage == null ? null : storage.getDepthTexture();
    }

    @Override
    public void bind() {
        CgTexture depth = depth();
        if (depth != null) depth.bind();
    }

    @Override
    public void bind(int unit) {
        CgTexture depth = depth();
        if (depth != null) depth.bind(unit);
    }

    @Override
    public int getId() {
        CgTexture depth = depth();
        return depth == null ? 0 : depth.getId();
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
        CgTexture depth = depth();
        return depth == null ? CgGL.GL_TEXTURE_2D : depth.getTarget();
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    /** Refused: the snapshot belongs to the world renderer. */
    @Override
    public void delete() {
        throw new UnsupportedOperationException("the depth snapshot belongs to CgWorldRenderer");
    }
}
