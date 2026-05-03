package io.github.somehussar.crystalgraphics.api.texture;

import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgTextureFormatSpec;

import lombok.Builder;
import lombok.Getter;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.util.logging.Logger;

/**
 * Immutable specification for a GL texture: pixel format + sampler params.
 *
 * <p>Built via Lombok {@link Builder}. Reuses {@link CgTextureFormatSpec} for
 * the (internalFormat, pixelFormat, pixelType) triple and {@link CgMipmapConfig}
 * for mipmap generation. Filter and wrap parameters are raw GL constants and
 * default to safe choices: {@code GL_LINEAR} filtering and
 * {@code GL_CLAMP_TO_EDGE} wrapping.</p>
 *
 * <p>Three pre-built specs cover the most common cases:</p>
 * <ul>
 *   <li>{@link #RGBA8_LINEAR} — 8-bit RGBA, linear filter, clamp wrap</li>
 *   <li>{@link #RGBA8_NEAREST} — 8-bit RGBA, nearest filter (pixel art)</li>
 *   <li>{@link #RGBA16F_LINEAR} — 16-bit float RGBA, linear filter (HDR)</li>
 * </ul>
 *
 * <p>Also exposes two GL-side helpers used by the texture impls:
 * {@link #applyTo(int)} writes this spec's filter/wrap params to the bound
 * texture, and the static {@link #generateMipmaps(int)} probes for GL30 and
 * either calls {@code glGenerateMipmap} or warns once and no-ops.</p>
 */
@Getter
@Builder(toBuilder = true)
public final class CgTextureSpec {

    // ── GL constants (filter / wrap defaults) ──────────────────────
    /** {@code GL_LINEAR} filter. */
    private static final int GL_LINEAR = 0x2601;
    /** {@code GL_NEAREST} filter. */
    private static final int GL_NEAREST = 0x2600;
    
    /** {@code GL_CLAMP_TO_EDGE} wrap. */
    private static final int GL_CLAMP_TO_EDGE = 0x812F;     
    /** {@code GL_CLAMP_TO_BORDER} wrap. */
    private static final int GL_CLAMP_TO_BORDER = 0x812D;    
    /** {@code GL_REPEAT} wrap. */
    private static final int GL_REPEAT = 0x2901; 
    /** {@code GL_MIRRORED_REPEAT} wrap. */
    private static final int GL_MIRRORED_REPEAT = 0x8370;
    
    /** {@code GL_R8} internal format. */
    private static final int GL_R8   = 0x8229;
    /** {@code GL_RED} pixel format (single-channel upload). */
    private static final int GL_RED  = 0x1903;
    /** {@code GL_RGB} pixel format. */
    private static final int GL_RGB  = 0x1907;
    /** {@code GL_RGBA8} internal format. */
    private static final int GL_RGBA8 = 0x8058;
    /** {@code GL_RGBA} pixel format. */
    private static final int GL_RGBA = 0x1908;
    /** {@code GL_UNSIGNED_BYTE} pixel type. */
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    /** {@code GL_RGBA16F} internal format. */
    private static final int GL_RGBA16F = 0x881A;
    /** {@code GL_HALF_FLOAT} pixel type. */
    private static final int GL_HALF_FLOAT = 0x140B;

    // ── GL constants (used by applyTo / generateMipmaps) ───────────
    private static final int GL_TEXTURE_MIN_FILTER  = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER  = 0x2800;
    private static final int GL_TEXTURE_WRAP_S      = 0x2802;
    private static final int GL_TEXTURE_WRAP_T      = 0x2803;
    private static final int GL_TEXTURE_WRAP_R      = 0x8072;
    private static final int GL_TEXTURE_3D          = 0x806F;
    private static final int GL_TEXTURE_2D_ARRAY    = 0x8C1A;

    /** Pixel format triple (internalFormat, pixelFormat, pixelType). */
    private final CgTextureFormatSpec format;

    /** Mipmap policy. Defaults to {@link CgMipmapConfig#disabled()}. */
    @Builder.Default
    private final CgMipmapConfig mipmaps = CgMipmapConfig.disabled();

    /** Minification filter. Default {@code GL_LINEAR}. */
    @Builder.Default
    private final int minFilter = GL_LINEAR;

    /** Magnification filter. Default {@code GL_LINEAR}. */
    @Builder.Default
    private final int magFilter = GL_LINEAR;

    /** Wrap mode for the S coordinate. Default {@code GL_CLAMP_TO_EDGE}. */
    @Builder.Default
    private final int wrapS = GL_CLAMP_TO_EDGE;

    /** Wrap mode for the T coordinate. Default {@code GL_CLAMP_TO_EDGE}. */
    @Builder.Default
    private final int wrapT = GL_CLAMP_TO_EDGE;

    /** Wrap mode for the R coordinate (3D / 2D-array only). */
    @Builder.Default
    private final int wrapR = GL_CLAMP_TO_EDGE;

    // ── Pre-built specs ────────────────────────────────────────────

    /** 8-bit RGBA, linear filtering, clamp-to-edge. The general default. */
    public static final CgTextureSpec RGBA8_LINEAR = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .build();

    /** 8-bit RGBA with nearest filtering (pixel-art / sharp sprites). */
    public static final CgTextureSpec RGBA8_NEAREST = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .minFilter(GL_NEAREST).magFilter(GL_NEAREST)
            .build();
    
    
    /** 8-bit RGBA, linear filtering, clamp-to-border. */
    public static final CgTextureSpec RGBA8_LINEAR_CLAMP_BORDER = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .wrapS(GL_CLAMP_TO_BORDER).wrapT(GL_CLAMP_TO_BORDER).wrapR(GL_CLAMP_TO_BORDER)                                                                   
            .build();

    /** 8-bit RGBA with nearest filtering (pixel-art / sharp sprites), clamp-to-border. */
    public static final CgTextureSpec RGBA8_NEAREST_CLAMP_BORDER = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .minFilter(GL_NEAREST).magFilter(GL_NEAREST)
            .wrapS(GL_CLAMP_TO_BORDER).wrapT(GL_CLAMP_TO_BORDER).wrapR(GL_CLAMP_TO_BORDER)                                                                 
            .build();
    
    
    /** 8-bit RGBA, linear filtering, repeat. */
    public static final CgTextureSpec RGBA8_LINEAR_REPEAT = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT)
            .build();
    
    /** 8-bit RGBA with nearest filtering (pixel-art / sharp sprites), repeat. */
    public static final CgTextureSpec RGBA8_NEAREST_REPEAT = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .minFilter(GL_NEAREST).magFilter(GL_NEAREST)
            .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT)                                              
            .build();
    
    
    /** 8-bit RGBA, linear filtering, mirrored-repeat. */
    public static final CgTextureSpec RGBA8_LINEAR_MIRRORED = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .wrapS(GL_MIRRORED_REPEAT).wrapT(GL_MIRRORED_REPEAT).wrapR(GL_MIRRORED_REPEAT)
            .build();

    /** 8-bit RGBA with nearest filtering (pixel-art / sharp sprites), mirrored-repeat. */
    public static final CgTextureSpec RGBA8_NEAREST_MIRRORED = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE))
            .minFilter(GL_NEAREST).magFilter(GL_NEAREST)
            .wrapS(GL_MIRRORED_REPEAT).wrapT(GL_MIRRORED_REPEAT).wrapR(GL_MIRRORED_REPEAT)                                              
            .build();

    
    
   /** 8-bit R, linear filtering, repeat. For single-channel data (noise, masks, heightmaps). */
    public static final CgTextureSpec R8_LINEAR_REPEAT = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_R8, GL_RED, GL_UNSIGNED_BYTE))
            .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT)
            .build();
    
       /** 8-bit R, nearest filtering, repeat. For single-channel data (noise, masks, heightmaps). */
    public static final CgTextureSpec R8_NEAREST_REPEAT = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_R8, GL_RED, GL_UNSIGNED_BYTE))
            .minFilter(GL_NEAREST).magFilter(GL_NEAREST)               
            .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT)
            .build();

    
    
    /** 16-bit float RGBA with linear filtering (HDR/intermediate targets). */
    public static final CgTextureSpec RGBA16F_LINEAR = CgTextureSpec.builder()
            .format(new CgTextureFormatSpec(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT))
            .build();

    // ── GL-side helpers ────────────────────────────────────────────

    /**
     * Applies this spec's filter + wrap parameters to the currently bound
     * texture at {@code target}. Wrap-R is only applied for 3D / 2D-array targets.
     *
     * <p>If the spec has explicit mipmap filters, the min/mag filters are
     * overridden after the base filters are written so sampled mip lookups
     * behave as the user requested.</p>
     */
    public void applyTo(int target) {
        GL11.glTexParameteri(target, GL_TEXTURE_MIN_FILTER, minFilter);
        GL11.glTexParameteri(target, GL_TEXTURE_MAG_FILTER, magFilter);
        GL11.glTexParameteri(target, GL_TEXTURE_WRAP_S, wrapS);
        GL11.glTexParameteri(target, GL_TEXTURE_WRAP_T, wrapT);
        if (target == GL_TEXTURE_3D || target == GL_TEXTURE_2D_ARRAY) {
            GL11.glTexParameteri(target, GL_TEXTURE_WRAP_R, wrapR);
        }
        if (mipmaps != null && mipmaps.isEnabled()) {
            GL11.glTexParameteri(target, GL_TEXTURE_MIN_FILTER, mipmaps.getMinFilter());
            GL11.glTexParameteri(target, GL_TEXTURE_MAG_FILTER, mipmaps.getMagFilter());
        }
    }

    /**
     * Calls {@code GL30.glGenerateMipmap(target)} if the current GL context supports
     * OpenGL 3.0; otherwise logs a one-time warning and no-ops.
     * The texture must be bound to {@code target} before calling.
     */
    public static void generateMipmaps(int target) {
        if (GLContext.getCapabilities().OpenGL30) {
            GL30.glGenerateMipmap(target);
        } else {
            if (!warnedNoGenerateMipmap) {
                warnedNoGenerateMipmap = true;
                LOGGER.warning("CgTextureSpec.generateMipmaps: GL30 unavailable, skipping (warn-once)");
            }
        }
    }
    private static volatile boolean warnedNoGenerateMipmap = false;
    private static final Logger LOGGER = Logger.getLogger(CgTextureSpec.class.getName());
}
