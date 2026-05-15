package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;

import lombok.Getter;

import org.lwjgl.opengl.*;

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
    /** GL texture object id; zeroed out on {@link #delete()}. */
    protected int textureId;
    /** Width of the texture at mip level 0, in pixels. */
    @Getter
    protected int width;
    /** Height of the texture at mip level 0, in pixels. */
    @Getter
    protected int height;
    /** Immutable spec: format, filter, wrap, and mipmap policy. */
    protected final CgTextureSpec spec;
    private boolean deleted;

    protected CgTextureAbstract(int textureId, int width, int height, CgTextureSpec spec) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.spec = spec;
    }

    // ── CgTexture — binding ─────────────────────────────────────────

    @Override
    public void bind() {
        checkNotDeleted();
        CgTexture.bind(getTarget(),textureId);
    }

    @Override
    public void bind(int unit) {
        checkNotDeleted();
        CgTexture.active(unit);
        CgTexture.bind(getTarget(),textureId);
    }

    // ── CgTexture — accessors ───────────────────────────────────────

    @Override
    public int getId() {return textureId;}

    @Override
    public boolean isDeleted() {return deleted;}

    // ── CgTexture — lifecycle ───────────────────────────────────────

    @Override
    public void delete() {
        if (deleted) return;
        GL11.glDeleteTextures(textureId);
        textureId = 0;
        deleted = true;
    }

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Guards every bind/upload/mipmap call. Throws {@link IllegalStateException}
     * if this texture has already been deleted.
     */
    protected void checkNotDeleted() {
        if (deleted) throw new IllegalStateException(getClass().getSimpleName() + " has been deleted");
    }

    protected static int pixelFormatForChannels(int channels) {
        switch (channels) {
            case 1: return GL_RED;
            case 3: return GL_RGB;
            default: return GL_RGBA;
        }
    }
}
