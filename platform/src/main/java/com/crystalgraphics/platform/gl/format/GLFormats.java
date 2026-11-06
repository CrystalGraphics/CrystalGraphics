package com.crystalgraphics.platform.gl.format;

import com.crystalgraphics.platform.rhi.format.CgAttribFormat;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.format.CgFormat;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/** RHI formats in GL's terms: a texture's internal format, and the format and type its texels move in. */
public final class GLFormats {

    private static final int[] INTERNAL = new int[CgFormat.values().length];
    private static final int[] FORMAT = new int[CgFormat.values().length];
    private static final int[] TYPE = new int[CgFormat.values().length];

    static {
        put(CgFormat.R8_UNORM, GL_R8, GL_RED, GL_UNSIGNED_BYTE);
        put(CgFormat.R8_SNORM, GL_R8_SNORM, GL_RED, GL_BYTE);
        put(CgFormat.R8_UINT, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE);
        put(CgFormat.R8_SINT, GL_R8I, GL_RED_INTEGER, GL_BYTE);
        put(CgFormat.RG8_UNORM, GL_RG8, GL_RG, GL_UNSIGNED_BYTE);
        put(CgFormat.RG8_UINT, GL_RG8UI, GL_RG_INTEGER, GL_UNSIGNED_BYTE);
        put(CgFormat.RG8_SINT, GL_RG8I, GL_RG_INTEGER, GL_BYTE);
        put(CgFormat.RGBA8_UNORM, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
        put(CgFormat.RGBA8_SNORM, GL_RGBA8_SNORM, GL_RGBA, GL_BYTE);
        put(CgFormat.RGBA8_UINT, GL_RGBA8UI, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE);
        put(CgFormat.RGBA8_SINT, GL_RGBA8I, GL_RGBA_INTEGER, GL_BYTE);
        put(CgFormat.RGBA8_SRGB, GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE);
        put(CgFormat.BGRA8_UNORM, GL_RGBA8, GL_BGRA, GL_UNSIGNED_BYTE);
        put(CgFormat.R16_FLOAT, GL_R16F, GL_RED, GL_HALF_FLOAT);
        put(CgFormat.R16_UINT, GL_R16UI, GL_RED_INTEGER, GL_UNSIGNED_SHORT);
        put(CgFormat.R16_SINT, GL_R16I, GL_RED_INTEGER, GL_SHORT);
        put(CgFormat.RG16_FLOAT, GL_RG16F, GL_RG, GL_HALF_FLOAT);
        put(CgFormat.RG16_UINT, GL_RG16UI, GL_RG_INTEGER, GL_UNSIGNED_SHORT);
        put(CgFormat.RG16_SINT, GL_RG16I, GL_RG_INTEGER, GL_SHORT);
        put(CgFormat.RGBA16_FLOAT, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT);
        put(CgFormat.RGBA16_UINT, GL_RGBA16UI, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT);
        put(CgFormat.RGBA16_SINT, GL_RGBA16I, GL_RGBA_INTEGER, GL_SHORT);
        put(CgFormat.R32_FLOAT, GL_R32F, GL_RED, GL_FLOAT);
        put(CgFormat.R32_UINT, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT);
        put(CgFormat.R32_SINT, GL_R32I, GL_RED_INTEGER, GL_INT);
        put(CgFormat.RG32_FLOAT, GL_RG32F, GL_RG, GL_FLOAT);
        put(CgFormat.RG32_UINT, GL_RG32UI, GL_RG_INTEGER, GL_UNSIGNED_INT);
        put(CgFormat.RG32_SINT, GL_RG32I, GL_RG_INTEGER, GL_INT);
        put(CgFormat.RGBA32_FLOAT, GL_RGBA32F, GL_RGBA, GL_FLOAT);
        put(CgFormat.RGBA32_UINT, GL_RGBA32UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT);
        put(CgFormat.RGBA32_SINT, GL_RGBA32I, GL_RGBA_INTEGER, GL_INT);
        put(CgFormat.RGB10A2_UNORM, GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV);
        put(CgFormat.RGB10A2_UINT, GL_RGB10_A2UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT_2_10_10_10_REV);
        put(CgFormat.RG11B10_UFLOAT, GL_R11F_G11F_B10F, GL_RGB, GL_UNSIGNED_INT_10F_11F_11F_REV);
        put(CgFormat.RGBA4_UNORM, GL_RGBA4, GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4);
        put(CgFormat.RGB5A1_UNORM, GL_RGB5_A1, GL_RGBA, GL_UNSIGNED_SHORT_5_5_5_1);
        put(CgFormat.DEPTH16_UNORM, GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT);
        put(CgFormat.DEPTH24_PLUS, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT);
        put(CgFormat.DEPTH32_FLOAT, GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT);
        put(CgFormat.DEPTH24_PLUS_STENCIL8, GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8);
        put(CgFormat.DEPTH32_FLOAT_STENCIL8, GL_DEPTH32F_STENCIL8, GL_DEPTH_STENCIL, GL_FLOAT_32_UNSIGNED_INT_24_8_REV);
        put(CgFormat.STENCIL8, GL_STENCIL_INDEX8, GL_STENCIL_INDEX, GL_UNSIGNED_BYTE);
        put(CgFormat.BC7_RGBA_UNORM, GL_COMPRESSED_RGBA_BPTC_UNORM, GL_RGBA, GL_UNSIGNED_BYTE);
        put(CgFormat.BC7_RGBA_UNORM_SRGB, GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM, GL_RGBA, GL_UNSIGNED_BYTE);
        for (CgFormat f : CgFormat.values()) {
            if (INTERNAL[f.ordinal()] == 0) throw new ExceptionInInitializerError(f + " has no GL format");
        }
    }

    private GLFormats() {}

    private static void put(CgFormat f, int internal, int format, int type) {
        INTERNAL[f.ordinal()] = internal;
        FORMAT[f.ordinal()] = format;
        TYPE[f.ordinal()] = type;
    }

    public static int internal(CgFormat f) { return INTERNAL[f.ordinal()]; }

    /**
     * The format a GL internal format names, the first in {@link CgFormat}'s order where two share one. A host's
     * format the RHI has none for (a GL-era Minecraft's unsized or 32-bit integer ones) answers the format it draws
     * and samples the same as: {@code GL_DEPTH_COMPONENT32} is {@link CgFormat#DEPTH24_PLUS}, depth of 24 bits or more.
     *
     * <pre>{@code
     * GLFormats.of(GL_RGBA16F);               // RGBA16_FLOAT
     * GLFormats.of(GL_DEPTH_COMPONENT32);     // DEPTH24_PLUS, as imported from a host
     * }</pre>
     */
    public static CgFormat of(int internal) {
        for (CgFormat f : CgFormat.values()) if (INTERNAL[f.ordinal()] == internal) return f;
        CgFormat host = hostFormat(internal);
        if (host != null) return host;
        throw new IllegalArgumentException("no CgFormat for GL internal format 0x" + Integer.toHexString(internal));
    }

    /** A host's internal format no {@link CgFormat} names, as the one it is drawn and sampled as; null for none. */
    private static CgFormat hostFormat(int internal) {
        return switch (internal) {
            case GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT -> CgFormat.DEPTH24_PLUS;
            case GL_DEPTH_STENCIL -> CgFormat.DEPTH24_PLUS_STENCIL8;
            case GL_RGBA, GL_RGB8, GL_RGB -> CgFormat.RGBA8_UNORM;
            default -> null;
        };
    }

    public static int format(CgFormat f) { return FORMAT[f.ordinal()]; }

    public static int type(CgFormat f) { return TYPE[f.ordinal()]; }

    /** The framebuffer attachment point a format attaches at, colour attachment {@code index} for a colour one. */
    public static int attachment(CgFormat f, int index) {
        return switch (f.aspect()) {
            case COLOR -> GL_COLOR_ATTACHMENT0 + index;
            case DEPTH -> GL_DEPTH_ATTACHMENT;
            case STENCIL -> GL_STENCIL_ATTACHMENT;
            case DEPTH_STENCIL -> GL_DEPTH_STENCIL_ATTACHMENT;
        };
    }

    public static int compare(CgCompare c) {
        return switch (c) {
            case NEVER -> GL_NEVER;
            case LESS -> GL_LESS;
            case EQUAL -> GL_EQUAL;
            case LESS_EQUAL -> GL_LEQUAL;
            case GREATER -> GL_GREATER;
            case NOT_EQUAL -> GL_NOTEQUAL;
            case GREATER_EQUAL -> GL_GEQUAL;
            case ALWAYS -> GL_ALWAYS;
        };
    }

    /** A vertex attribute's component count. */
    public static int attribSize(CgAttribFormat f) {
        String n = f.name();
        int x = n.lastIndexOf('X');
        return x < 0 ? 1 : n.charAt(x + 1) - '0';
    }

    /** A vertex attribute's component type. */
    public static int attribType(CgAttribFormat f) {
        String n = f.name();
        if (n.startsWith("FLOAT32")) return GL_FLOAT;
        if (n.startsWith("FLOAT16")) return GL_HALF_FLOAT;
        boolean signed = n.startsWith("S");
        if (n.contains("8")) return signed ? GL_BYTE : GL_UNSIGNED_BYTE;
        if (n.contains("16")) return signed ? GL_SHORT : GL_UNSIGNED_SHORT;
        return signed ? GL_INT : GL_UNSIGNED_INT;
    }

    /** Whether the attribute reaches the shader as an integer ({@code glVertexArrayAttribIFormat}). */
    public static boolean attribInteger(CgAttribFormat f) {
        return f.name().startsWith("UINT") || f.name().startsWith("SINT");
    }

    /** Whether integer components are normalised to [0, 1] or [-1, 1]. */
    public static boolean attribNormalized(CgAttribFormat f) {
        return f.name().startsWith("UNORM") || f.name().startsWith("SNORM");
    }
}
