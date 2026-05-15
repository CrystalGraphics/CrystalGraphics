package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;

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

    private CgFallbackTextures() {}

    /**
     * Allocates all four fallback textures. Must be called once on the GL thread
     * after the GL context is available, before any material bind that may need fallbacks.
     */
    public static void init() {
        WHITE_1x1 = createPixel(255, 255, 255, 255);
        BLACK_1x1 = createPixel(0, 0, 0, 255);
        FLAT_NORMAL = createPixel(128, 128, 255, 255);
        TRANSPARENT = createPixel(0, 0, 0, 0);
    }

    /**
     * Frees all four fallback textures and sets the fields to {@code null}.
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
