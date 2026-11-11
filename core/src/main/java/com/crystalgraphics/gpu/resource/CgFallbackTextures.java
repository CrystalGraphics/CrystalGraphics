package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.gl.driver.GLC;

import java.nio.ByteBuffer;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * Engine-managed 1×1 fallback textures covering the four most common "unset sampler" cases.
 *
 * <p>Must be initialised once on the GL thread via {@link #init()} (typically called from
 * {@code CgEngineLifecycle.ensureGraphicsContext()}), and freed via {@link #destroy()} during
 * context teardown (called automatically by {@code CgEngineLifecycle.shutdown()}).</p>
 *
 * <p>Fields are {@code null} before {@link #init()} and after {@link #destroy()}.</p>
 */
public final class CgFallbackTextures {

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    public static CgTextureAbstract WHITE_1x1;

    /** 1×1 fully opaque black ({@code RGBA = 0, 0, 0, 255}). */
    public static CgTextureAbstract BLACK_1x1;

    /**
     * 1×1 flat (neutral) normal map ({@code RGBA = 128, 128, 255, 255}).
     * Encodes the unit normal {@code (0, 0, 1)} in tangent space.
     */
    public static CgTextureAbstract FLAT_NORMAL;

    /** 1×1 fully transparent black ({@code RGBA = 0, 0, 0, 0}). */
    public static CgTextureAbstract TRANSPARENT;

    /**
     * 1×1 unsigned integer 0 (R8UI): what an unset {@code usampler2D} reads, as a stencil no draw wrote. A float
     * texture under an integer sampler is undefined (a validation error on Vulkan).
     */
    public static CgTextureAbstract ZERO_UINT;

    /** A 1×1×1 opaque white volume: what a pass with no world media reads through {@code cg_Media}, everything let through. */
    public static CgTextureAbstract WHITE_VOLUME;

    /** A 1×1×1 black volume: what a pass with no world lights on transparent surfaces reads through {@code cg_LocalLights}. */
    public static CgTextureAbstract BLACK_VOLUME;

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
        ByteBuffer zeroByte = ByteBuffer.allocateDirect(1);
        zeroByte.put((byte) 0).flip();
        ZERO_UINT = CgTexture2D.createFromPixels(1, 1, zeroByte, CgFormat.R8_UINT);
        CgTexture3D volume = CgTexture3D.createEmpty(1, 1, 1, CgFormat.RGBA8_UNORM);
        ByteBuffer white = ByteBuffer.allocateDirect(4);
        white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        volume.uploadRegion(0, 0, 0, 0, 1, 1, 1, white, GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE);
        WHITE_VOLUME = volume;
        CgTexture3D black = CgTexture3D.createEmpty(1, 1, 1, CgFormat.RGBA8_UNORM);
        ByteBuffer zero = ByteBuffer.allocateDirect(4);
        zero.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).flip();
        black.uploadRegion(0, 0, 0, 0, 1, 1, 1, zero, GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE);
        BLACK_VOLUME = black;
    }

    /**
     * Frees the fallback textures and sets the fields to {@code null}.
     * Called automatically by {@code CgEngineLifecycle.shutdown()}.
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
        deleteAndNull(ZERO_UINT);
        ZERO_UINT = null;
        deleteAndNull(WHITE_VOLUME);
        WHITE_VOLUME = null;
        deleteAndNull(BLACK_VOLUME);
        BLACK_VOLUME = null;
    }

    private static CgTextureAbstract createPixel(int r, int g, int b, int a) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        buf.flip();
        return CgTexture2D.createFromPixels(1, 1, buf, CgFormat.RGBA8_UNORM);
    }

    private static void deleteAndNull(CgTextureAbstract tex) {
        if (tex != null) tex.delete();
    }
}
