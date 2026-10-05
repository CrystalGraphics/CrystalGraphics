package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gpu.CgDeferral;

import lombok.Getter;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlSlot;

/**
 * Shared base for all owned GL texture implementations.
 *
 * <p>Owns a single GL texture id (allocated by the subclass factory and
 * released via {@link #delete()}). Provides the full lifecycle boilerplate
 * that is identical across every texture type: id/spec storage, bind/unbind,
 * mipmap generation, and delete. Subclasses supply only:
 *
 * <ul>
 *   <li>{@link #getTarget()} — the GL texture target constant</li>
 *   <li>their own static factory methods</li>
 *   <li>any type-specific fields (e.g. {@code depth})</li>
 * </ul>
 *
 * <p>All its device work goes through {@link #gpu}, so a texture is made, filled and deleted from any thread: where
 * the device may not be driven, the work waits for the render thread. {@link #getId()} and {@link #bind} run it first
 * there.</p>
 *
 * <p>Not part of the public API surface; lives in {@code gl/texture/} alongside
 * the concrete implementations.</p>
 */
public abstract class CgTextureAbstract implements CgTexture {

    // ── Upload-format GL constants (shared by all subclass factories) ─
    private static final int GL_RED = 0x1903;
    private static final int GL_RGB = 0x1907;
    protected static final int GL_RGBA = 0x1908;
    protected static final int GL_UNSIGNED_BYTE = 0x1401;

    // ── Instance state ──────────────────────────────────────────────
    /** GL texture object id: 0 until made, and again once deleted. Written only by {@link #gpu}'s work. */
    protected int textureId;
    /** This texture's GL work, in order, each piece handing back the binding it moved. */
    protected final CgDeferral gpu = new CgDeferral(CgGlSlot.TEXTURES);
    /** Width of the texture at mip level 0, in pixels. */
    @Getter
    protected int width;
    /** Height of the texture at mip level 0, in pixels. */
    @Getter
    protected int height;
    /** Immutable spec: format, filter, wrap, and mipmap policy. */
    protected final CgTextureSpec spec;
    /** False for a texture adopted from its owner: {@link #delete()} forgets it without deleting it. */
    private final boolean owned;
    private boolean deleted;

    protected CgTextureAbstract(int textureId, int width, int height, CgTextureSpec spec) {
        this(textureId, width, height, spec, true);
    }

    protected CgTextureAbstract(int textureId, int width, int height, CgTextureSpec spec, boolean owned) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.spec = spec;
        this.owned = owned;
    }

    // ── CgTexture — binding ─────────────────────────────────────────

    @Override
    public void bind() {
        checkNotDeleted();
        gpu.flush();
        CgTexture.bind(getTarget(),textureId);
    }

    @Override
    public void bind(int unit) {
        checkNotDeleted();
        gpu.flush();
        CgTexture.active(unit);
        CgTexture.bind(getTarget(),textureId);
    }

    // ── CgTexture — accessors ───────────────────────────────────────

    @Override
    public int getId() {
        gpu.flush();
        return textureId;
    }

    @Override
    public boolean isDeleted() {return deleted;}

    // ── CgTexture — lifecycle ───────────────────────────────────────

    /** Any thread. What was queued is dropped: a texture about to go needs none of it. */
    @Override
    public void delete() {
        if (deleted) return;
        deleted = true;
        gpu.clear();
        gpu.run(() -> {
            if (owned && textureId != 0) CgGL.glDeleteTextures(textureId);
            textureId = 0;
        });
    }

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Guards every bind/upload/mipmap call. Throws {@link IllegalStateException}
     * if this texture has already been deleted.
     */
    protected void checkNotDeleted() {
        if (deleted) throw new IllegalStateException(getClass().getSimpleName() + " has been deleted");
    }

    /** Guards every write: an adopted texture's storage and parameters are its owner's. */
    protected void checkOwned() {
        if (!owned) throw new IllegalStateException("A wrapped " + getClass().getSimpleName() + " belongs to its host");
    }

    protected static int pixelFormatForChannels(int channels) {
        switch (channels) {
            case 1: return GL_RED;
            case 3: return GL_RGB;
            default: return GL_RGBA;
        }
    }
}
