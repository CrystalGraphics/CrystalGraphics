package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;

/**
 * Engine-managed 1×1 fallback textures covering the four most common "unset sampler" cases.
 *
 * <p>Must be initialised once on the GL thread via {@link #init()} (typically called from
 * {@code CgGraphicsLifecycle.initContext()}), and freed via {@link #destroy()} during
 * context teardown (called automatically by {@code CgGraphicsLifecycle.destroyContext()}).</p>
 *
 * <p>Fields are {@code null} before {@link #init()} and after {@link #destroy()}.</p>
 */
public final class CgFallbackTextures {

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    public static CgTexture WHITE_1x1;

    /** 1×1 fully opaque black ({@code RGBA = 0, 0, 0, 255}). */
    public static CgTexture BLACK_1x1;

    /**
     * 1×1 flat (neutral) normal map ({@code RGBA = 128, 128, 255, 255}).
     * Encodes the unit normal {@code (0, 0, 1)} in tangent space.
     */
    public static CgTexture FLAT_NORMAL;

    /** 1×1 fully transparent black ({@code RGBA = 0, 0, 0, 0}). */
    public static CgTexture TRANSPARENT;

    /** A 1×1×1 opaque white volume: what a pass with no world media reads through {@code cg_Media}, everything let through. */
    public static CgTexture WHITE_VOLUME;

    /** A 1×1×1 black volume: what a pass with no world lights on transparent surfaces reads through {@code cg_LocalLights}. */
    public static CgTexture BLACK_VOLUME;

    private CgFallbackTextures() {}

    /**
     * Allocates the fallback textures. Must be called once on the GL thread
     * after the GL context is available, before any material bind that may need fallbacks.
     */
    public static void init() {
        WHITE_1x1 = createPixel(255, 255, 255, 255);
        BLACK_1x1 = createPixel(0, 0, 0, 255);
        FLAT_NORMAL = createPixel(128, 128, 255, 255);
        TRANSPARENT = createPixel(0, 0, 0, 0);
        CgTexture3D volume = CgTexture3D.createEmpty(1, 1, 1, CgTextureSpec.RGBA8_LINEAR);
        ByteBuffer white = ByteBuffer.allocateDirect(4);
        white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        volume.uploadRegion(0, 0, 0, 0, 1, 1, 1, white, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        WHITE_VOLUME = volume;
        CgTexture3D black = CgTexture3D.createEmpty(1, 1, 1, CgTextureSpec.RGBA8_LINEAR);
        ByteBuffer zero = ByteBuffer.allocateDirect(4);
        zero.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).flip();
        black.uploadRegion(0, 0, 0, 0, 1, 1, 1, zero, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        BLACK_VOLUME = black;
    }

    /**
     * Frees the fallback textures and sets the fields to {@code null}.
     * Called automatically by {@code CgGraphicsLifecycle.destroyContext()}.
     */
    public static void destroy() {
        deleteAndNull(WHITE_1x1);
        WHITE_1x1 = null;
        deleteAndNull(BLACK_1x1);
        BLACK_1x1 = null;
        deleteAndNull(FLAT_NORMAL);
        FLAT_NORMAL = null;
        deleteAndNull(TRANSPARENT);
        TRANSPARENT = null;
        deleteAndNull(WHITE_VOLUME);
        WHITE_VOLUME = null;
        deleteAndNull(BLACK_VOLUME);
        BLACK_VOLUME = null;
    }

    private static CgTexture createPixel(int r, int g, int b, int a) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        buf.flip();
        return CgTexture2D.createFromPixels(1, 1, buf, CgTextureSpec.RGBA8_LINEAR);
    }

    private static void deleteAndNull(CgTexture tex) {
        if (tex != null) tex.delete();
    }
}
