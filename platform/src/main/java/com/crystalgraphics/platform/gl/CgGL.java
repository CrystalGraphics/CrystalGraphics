package com.crystalgraphics.platform.gl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Static GL facade for the CrystalGraphics core module.
 *
 * This class carries zero {@code org.lwjgl.*} imports; all GL constants are raw {@code int}
 * literals taken directly from the OpenGL specification.</p>
 *
 * <h3>FBO naming convention</h3>
 * {@link CgGLBackend} exposes FBO methods without the {@code gl} prefix
 * ({@code bindFramebuffer}, {@code genFramebuffers}, etc.).  {@code CgGL} normalises all
 * methods to the {@code glXxx} form and delegates to the no-prefix counterpart internally.
 */
public final class CgGL {
    
    private static CgGLBackend backend;
    
    public static void init(CgGLBackend dispatch){ backend = dispatch; }
    
    private CgGL() {}

    // =========================================================================
    // GL Constants
    // =========================================================================

    // --- Primitives ----------------------------------------------------------
    public static final int GL_POINTS         = 0x0000;
    public static final int GL_LINES          = 0x0001;
    public static final int GL_LINE_LOOP = 0x0002;
    public static final int GL_LINE_STRIP     = 0x0003;
    public static final int GL_TRIANGLES      = 0x0004;
    public static final int GL_TRIANGLE_STRIP = 0x0005;
    public static final int GL_TRIANGLE_FAN   = 0x0006;
    public static final int GL_QUADS          = 0x0007;

    // --- Boolean -------------------------------------------------------------
    public static final int GL_TRUE  = 1;
    public static final int GL_FALSE = 0;

    // --- Data types ----------------------------------------------------------
    public static final int GL_BYTE              = 0x1400;
    public static final int GL_UNSIGNED_BYTE     = 0x1401;
    public static final int GL_SHORT             = 0x1402;
    public static final int GL_UNSIGNED_SHORT    = 0x1403;
    public static final int GL_INT               = 0x1404;
    public static final int GL_UNSIGNED_INT      = 0x1405;
    public static final int GL_FLOAT             = 0x1406;
    public static final int GL_DOUBLE            = 0x140A;
    public static final int GL_HALF_FLOAT        = 0x140B;
    public static final int GL_UNSIGNED_INT_24_8 = 0x84FA;

    // --- Texture targets -----------------------------------------------------
    public static final int GL_TEXTURE_1D                      = 0x0DE0;
    public static final int GL_TEXTURE_2D                      = 0x0DE1;
    public static final int GL_TEXTURE_3D                      = 0x806F;
    public static final int GL_TEXTURE_CUBE_MAP                = 0x8513;
    public static final int GL_TEXTURE_2D_ARRAY                = 0x8C1A;
    public static final int GL_TEXTURE_BUFFER                  = 0x8C2A;
    public static final int GL_TEXTURE_CUBE_MAP_POSITIVE_X     = 0x8515;
    public static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_X     = 0x8516;
    public static final int GL_TEXTURE_CUBE_MAP_POSITIVE_Y     = 0x8517;
    public static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_Y     = 0x8518;
    public static final int GL_TEXTURE_CUBE_MAP_POSITIVE_Z     = 0x8519;
    public static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_Z     = 0x851A;

    // --- Texture units GL_TEXTURE0..GL_TEXTURE31 ----------------------------
    public static final int GL_TEXTURE0  = 0x84C0;
    public static final int GL_TEXTURE1  = 0x84C1;
    public static final int GL_TEXTURE2  = 0x84C2;
    public static final int GL_TEXTURE3  = 0x84C3;
    public static final int GL_TEXTURE4  = 0x84C4;
    public static final int GL_TEXTURE5  = 0x84C5;
    public static final int GL_TEXTURE6  = 0x84C6;
    public static final int GL_TEXTURE7  = 0x84C7;
    public static final int GL_TEXTURE8  = 0x84C8;
    public static final int GL_TEXTURE9  = 0x84C9;
    public static final int GL_TEXTURE10 = 0x84CA;
    public static final int GL_TEXTURE11 = 0x84CB;
    public static final int GL_TEXTURE12 = 0x84CC;
    public static final int GL_TEXTURE13 = 0x84CD;
    public static final int GL_TEXTURE14 = 0x84CE;
    public static final int GL_TEXTURE15 = 0x84CF;
    public static final int GL_TEXTURE16 = 0x84D0;
    public static final int GL_TEXTURE17 = 0x84D1;
    public static final int GL_TEXTURE18 = 0x84D2;
    public static final int GL_TEXTURE19 = 0x84D3;
    public static final int GL_TEXTURE20 = 0x84D4;
    public static final int GL_TEXTURE21 = 0x84D5;
    public static final int GL_TEXTURE22 = 0x84D6;
    public static final int GL_TEXTURE23 = 0x84D7;
    public static final int GL_TEXTURE24 = 0x84D8;
    public static final int GL_TEXTURE25 = 0x84D9;
    public static final int GL_TEXTURE26 = 0x84DA;
    public static final int GL_TEXTURE27 = 0x84DB;
    public static final int GL_TEXTURE28 = 0x84DC;
    public static final int GL_TEXTURE29 = 0x84DD;
    public static final int GL_TEXTURE30 = 0x84DE;
    public static final int GL_TEXTURE31 = 0x84DF;

    // --- Texture parameters --------------------------------------------------
    public static final int GL_TEXTURE_MIN_FILTER   = 0x2801;
    public static final int GL_TEXTURE_MAG_FILTER   = 0x2800;
    public static final int GL_TEXTURE_WRAP_S       = 0x2802;
    public static final int GL_TEXTURE_WRAP_T       = 0x2803;
    public static final int GL_TEXTURE_WRAP_R       = 0x8072;
    public static final int GL_TEXTURE_BASE_LEVEL   = 0x813C;
    public static final int GL_TEXTURE_MAX_LEVEL    = 0x813D;
    public static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
    public static final int GL_TEXTURE_COMPARE_FUNC = 0x884D;
    public static final int GL_TEXTURE_MIN_LOD      = 0x813A;
    public static final int GL_TEXTURE_MAX_LOD      = 0x813B;

    // --- Filter / wrap values ------------------------------------------------
    public static final int GL_NEAREST                = 0x2600;
    public static final int GL_LINEAR                 = 0x2601;
    public static final int GL_LINEAR_MIPMAP_LINEAR   = 0x2703;
    public static final int GL_LINEAR_MIPMAP_NEAREST  = 0x2701;
    public static final int GL_NEAREST_MIPMAP_NEAREST = 0x2700;
    public static final int GL_NEAREST_MIPMAP_LINEAR  = 0x2702;
    public static final int GL_REPEAT                 = 0x2901;
    public static final int GL_CLAMP_TO_EDGE          = 0x812F;
    public static final int GL_MIRRORED_REPEAT        = 0x8370;
    public static final int GL_CLAMP_TO_BORDER        = 0x812D;
    public static final int GL_COMPARE_R_TO_TEXTURE   = 0x884E;
    public static final int GL_NONE                   = 0;

    // --- Internal texture formats --------------------------------------------
    public static final int GL_R8             = 0x8229;
    public static final int GL_R8_SNORM       = 0x8F94;
    public static final int GL_R8I            = 0x8231;
    public static final int GL_R8UI           = 0x8232;
    public static final int GL_R16F           = 0x822D;
    public static final int GL_R16I           = 0x8233;
    public static final int GL_R16UI          = 0x8234;
    public static final int GL_R32F           = 0x822E;
    public static final int GL_R32I           = 0x8235;
    public static final int GL_R32UI          = 0x8236;
    public static final int GL_RG8            = 0x822B;
    public static final int GL_RG8I           = 0x8237;
    public static final int GL_RG8UI          = 0x8238;
    public static final int GL_RG16F          = 0x822F;
    public static final int GL_RG16I          = 0x8239;
    public static final int GL_RG16UI         = 0x823A;
    public static final int GL_RG32F          = 0x8230;
    public static final int GL_RG32I          = 0x823B;
    public static final int GL_RG32UI         = 0x823C;
    public static final int GL_RGB8           = 0x8051;
    public static final int GL_RGB16F         = 0x881B;
    public static final int GL_RGB32F         = 0x8815;
    public static final int GL_R11F_G11F_B10F = 0x8C3A;
    public static final int GL_RGBA8          = 0x8058;
    public static final int GL_RGBA8_SNORM    = 0x8F97;
    public static final int GL_RGBA16F        = 0x881A;
    public static final int GL_RGBA32F        = 0x8814;
    public static final int GL_SRGB8_ALPHA8   = 0x8C43;
    public static final int GL_RGB10_A2       = 0x8059;
    public static final int GL_RGBA4          = 0x8056;
    public static final int GL_RGB5_A1        = 0x8057;
    public static final int GL_DEPTH_COMPONENT16  = 0x81A5;
    public static final int GL_DEPTH_COMPONENT24  = 0x81A6;
    public static final int GL_DEPTH_COMPONENT32  = 0x81A7;
    public static final int GL_DEPTH_COMPONENT32F = 0x8CAC;
    public static final int GL_DEPTH24_STENCIL8   = 0x88F0;
    public static final int GL_DEPTH32F_STENCIL8  = 0x8CAD;
    public static final int GL_STENCIL_INDEX8     = 0x8D48;

    // --- Integer-sampled internal formats (GL 3.0 / ARB_texture_integer) ----
    public static final int GL_RGBA8I    = 0x8D8E;
    public static final int GL_RGBA8UI   = 0x8D7C;
    public static final int GL_RGBA16I   = 0x8D88;
    public static final int GL_RGBA16UI  = 0x8D76;
    public static final int GL_RGBA32I   = 0x8D82;
    public static final int GL_RGBA32UI  = 0x8D70;
    public static final int GL_RGB10_A2UI = 0x906F;

    // --- Base formats --------------------------------------------------------
    public static final int GL_RED             = 0x1903;
    public static final int GL_RG              = 0x8227;
    public static final int GL_RGB             = 0x1907;
    public static final int GL_RGBA            = 0x1908;
    public static final int GL_DEPTH_COMPONENT = 0x1902;
    public static final int GL_DEPTH_STENCIL   = 0x84F9;
    public static final int GL_STENCIL_INDEX   = 0x1901;
    public static final int GL_LUMINANCE       = 0x1909;
    public static final int GL_LUMINANCE_ALPHA = 0x190A;
    public static final int GL_ALPHA           = 0x1906;

    // --- Integer base formats (GL 3.0) ----------------------------------------
    public static final int GL_RED_INTEGER  = 0x8D94;
    public static final int GL_RG_INTEGER   = 0x8228;
    public static final int GL_RGB_INTEGER  = 0x8D98;
    public static final int GL_RGBA_INTEGER = 0x8D99;

    // --- Packed pixel types ---------------------------------------------------
    public static final int GL_UNSIGNED_INT_2_10_10_10_REV         = 0x8368;
    public static final int GL_UNSIGNED_INT_10F_11F_11F_REV        = 0x8C3B;
    public static final int GL_FLOAT_32_UNSIGNED_INT_24_8_REV      = 0x8DAD;

    // --- Buffer targets ------------------------------------------------------
    public static final int GL_ARRAY_BUFFER          = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER  = 0x8893;
    public static final int GL_UNIFORM_BUFFER        = 0x8A11;
    public static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;
    public static final int GL_COPY_READ_BUFFER      = 0x8F36;
    public static final int GL_COPY_WRITE_BUFFER     = 0x8F37;
    public static final int GL_PIXEL_UNPACK_BUFFER   = 0x88EC;

    // --- Buffer usages -------------------------------------------------------
    public static final int GL_STATIC_DRAW = 0x88E4, 
        GL_STREAM_DRAW = 0x88E0,
		GL_DYNAMIC_DRAW = 0x88E8,
        GL_STATIC_READ = 0x88E5,
		GL_STREAM_READ = 0x88E1,
		GL_DYNAMIC_READ = 0x88E9,
		GL_STATIC_COPY = 0x88E6,
		GL_STREAM_COPY = 0x88E2,
		GL_DYNAMIC_COPY = 0x88EA;
    
    // --- Buffer map access bits ----------------------------------------------
 	public static final int GL_MAP_READ_BIT = 0x1,
		GL_MAP_WRITE_BIT = 0x2,
		GL_MAP_INVALIDATE_RANGE_BIT = 0x4,
		GL_MAP_INVALIDATE_BUFFER_BIT = 0x8,
		GL_MAP_FLUSH_EXPLICIT_BIT = 0x10,
		GL_MAP_UNSYNCHRONIZED_BIT = 0x20;

    // --- Buffer binding query params -----------------------------------------
    public static final int GL_UNIFORM_BUFFER_BINDING        = 0x8A28;
    public static final int GL_MAX_UNIFORM_BUFFER_BINDINGS   = 0x8A2F;
    public static final int GL_SHADER_STORAGE_BUFFER_BINDING = 0x90D3;

    // --- Framebuffer ---------------------------------------------------------
    public static final int GL_FRAMEBUFFER          = 0x8D40;
    public static final int GL_READ_FRAMEBUFFER     = 0x8CA8;
    public static final int GL_DRAW_FRAMEBUFFER     = 0x8CA9;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_COLOR_ATTACHMENT0    = 0x8CE0;
    public static final int GL_COLOR_ATTACHMENT1    = 0x8CE1;
    public static final int GL_COLOR_ATTACHMENT2    = 0x8CE2;
    public static final int GL_COLOR_ATTACHMENT3    = 0x8CE3;
    public static final int GL_COLOR_ATTACHMENT4    = 0x8CE4;
    public static final int GL_COLOR_ATTACHMENT5    = 0x8CE5;
    public static final int GL_COLOR_ATTACHMENT6    = 0x8CE6;
    public static final int GL_COLOR_ATTACHMENT7    = 0x8CE7;
    public static final int GL_COLOR_ATTACHMENT8    = 0x8CE8;
    public static final int GL_COLOR_ATTACHMENT9    = 0x8CE9;
    public static final int GL_COLOR_ATTACHMENT10   = 0x8CEA;
    public static final int GL_COLOR_ATTACHMENT11   = 0x8CEB;
    public static final int GL_COLOR_ATTACHMENT12   = 0x8CEC;
    public static final int GL_COLOR_ATTACHMENT13   = 0x8CED;
    public static final int GL_COLOR_ATTACHMENT14   = 0x8CEE;
    public static final int GL_COLOR_ATTACHMENT15   = 0x8CEF;
    public static final int GL_DEPTH_ATTACHMENT         = 0x8D00;
    public static final int GL_STENCIL_ATTACHMENT       = 0x8D20;
    public static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    public static final int GL_DEPTH                    = 0x1801;
    public static final int GL_STENCIL                  = 0x1802;
    public static final int GL_RENDERBUFFER             = 0x8D41;
    public static final int GL_FRAMEBUFFER_BINDING      = 0x8CA6;

    // --- Clear / blit bits ---------------------------------------------------
    public static final int GL_COLOR_BUFFER_BIT   = 0x00004000;
    public static final int GL_DEPTH_BUFFER_BIT   = 0x00000100;
    public static final int GL_STENCIL_BUFFER_BIT = 0x00000400;

    // --- Framebuffer attachment query ----------------------------------------
    public static final int GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE = 0x8CD0;

    // --- Shader types --------------------------------------------------------
    public static final int GL_VERTEX_SHADER   = 0x8B31;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_GEOMETRY_SHADER = 0x8DD9;

    // --- Shader / program query params ---------------------------------------
    public static final int GL_COMPILE_STATUS            = 0x8B81;
    public static final int GL_LINK_STATUS               = 0x8B82;
    public static final int GL_INFO_LOG_LENGTH           = 0x8B84;
    public static final int GL_ACTIVE_UNIFORMS           = 0x8B86;
    public static final int GL_ACTIVE_UNIFORM_BLOCKS     = 0x8A36;
    public static final int GL_ACTIVE_UNIFORM_MAX_LENGTH = 0x8B87;
    public static final int GL_ACTIVE_ATTRIBUTES         = 0x8B89;

    // --- Uniform buffer blocks -----------------------------------------------
    public static final int GL_UNIFORM_BLOCK_BINDING   = 0x8A3F;
    public static final int GL_UNIFORM_BLOCK_DATA_SIZE = 0x8A40;

    // --- SSBO ----------------------------------------------------------------
    public static final int GL_SHADER_STORAGE_BLOCK               = 0x92E6;
    public static final int GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = 0x90DD;

    // --- GL capability flags -------------------------------------------------
    public static final int GL_BLEND                     = 0x0BE2;
    public static final int GL_DEPTH_TEST                = 0x0B71;
    public static final int GL_CULL_FACE                 = 0x0B44;
    public static final int GL_SCISSOR_TEST              = 0x0C11;
    public static final int GL_STENCIL_TEST              = 0x0B90;
    public static final int GL_ALPHA_TEST                = 0x0BC0;
    public static final int GL_POLYGON_OFFSET_FILL       = 0x8037;
    public static final int GL_LINE_SMOOTH               = 0x0B20;
    public static final int GL_MULTISAMPLE               = 0x809D;
    public static final int GL_VERTEX_PROGRAM_POINT_SIZE = 0x8642;

    // --- Blend factors -------------------------------------------------------
    public static final int GL_ZERO                = 0;
    public static final int GL_ONE                 = 1;
    public static final int GL_SRC_COLOR           = 0x0300;
    public static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    public static final int GL_SRC_ALPHA           = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_DST_ALPHA           = 0x0304;
    public static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    public static final int GL_DST_COLOR           = 0x0306;
    public static final int GL_ONE_MINUS_DST_COLOR = 0x0307;
    public static final int GL_SRC_ALPHA_SATURATE  = 0x0308;

    // --- Blend equation ------------------------------------------------------
    public static final int GL_FUNC_ADD              = 0x8006;
    public static final int GL_FUNC_SUBTRACT         = 0x800A;
    public static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;
    public static final int GL_MIN = 0x8007;
    public static final int GL_MAX = 0x8008;

    // --- Depth / stencil funcs -----------------------------------------------
    public static final int GL_NEVER    = 0x0200;
    public static final int GL_LESS     = 0x0201;
    public static final int GL_EQUAL    = 0x0202;
    public static final int GL_LEQUAL   = 0x0203;
    public static final int GL_GREATER  = 0x0204;
    public static final int GL_NOTEQUAL = 0x0205;
    public static final int GL_GEQUAL   = 0x0206;
    public static final int GL_ALWAYS   = 0x0207;

    // --- Stencil ops ---------------------------------------------------------
    public static final int GL_KEEP      = 0x1E00;
    public static final int GL_REPLACE   = 0x1E01;
    public static final int GL_INCR      = 0x1E02;
    public static final int GL_DECR      = 0x1E03;
    public static final int GL_INVERT    = 0x150A;
    public static final int GL_INCR_WRAP = 0x8507;
    public static final int GL_DECR_WRAP = 0x8508;

    // --- Face / polygon ------------------------------------------------------
    public static final int GL_FRONT          = 0x0404;
    public static final int GL_BACK           = 0x0405;
    public static final int GL_FRONT_AND_BACK = 0x0408;
    public static final int GL_FILL           = 0x1B02;
    public static final int GL_LINE           = 0x1B01;
    public static final int GL_POINT          = 0x1B00;
    public static final int GL_CW             = 0x0900;
    public static final int GL_CCW            = 0x0901;

    // --- Sync ----------------------------------------------------------------
    public static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
    public static final int GL_SYNC_FLUSH_COMMANDS_BIT    = 0x00000001;
    public static final int GL_ALREADY_SIGNALED           = 0x911A;
    public static final int GL_TIMEOUT_EXPIRED            = 0x911B;
    public static final int GL_CONDITION_SATISFIED        = 0x911C;
    public static final int GL_WAIT_FAILED                = 0x911D;

    // --- Queries / gets ------------------------------------------------------
    public static final int GL_MAX_TEXTURE_SIZE                 = 0x0D33;
    public static final int GL_MAX_3D_TEXTURE_SIZE              = 0x8073;
    public static final int GL_MAX_ARRAY_TEXTURE_LAYERS         = 0x88FF;
    public static final int GL_MAX_TEXTURE_IMAGE_UNITS          = 0x8872;
    public static final int GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D;
    public static final int GL_VIEWPORT                         = 0x0BA2;
    public static final int GL_SCISSOR_BOX                      = 0x0C10;
    public static final int GL_CURRENT_PROGRAM                  = 0x8B8D;

    // --- Pixel pack/unpack ---------------------------------------------------
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    public static final int GL_PACK_ALIGNMENT   = 0x0D05;

    // --- Error codes ---------------------------------------------------------
    public static final int GL_NO_ERROR                      = 0;
    public static final int GL_INVALID_ENUM                  = 0x0500;
    public static final int GL_INVALID_VALUE                 = 0x0501;
    public static final int GL_INVALID_OPERATION             = 0x0502;
    public static final int GL_STACK_OVERFLOW                = 0x0503;
    public static final int	GL_STACK_UNDERFLOW               = 0x0504;
    public static final int GL_OUT_OF_MEMORY                 = 0x0505;
    public static final int GL_INVALID_FRAMEBUFFER_OPERATION = 0x0506;
    
    public static final int GL_INVALID_INDEX = 0xFFFFFFFF;

    // --- VAO / instancing ----------------------------------------------------
    public static final int GL_VERTEX_ATTRIB_ARRAY_DIVISOR = 0x88FE;

    // --- ARB constants that appear in core/ code -----------------------------
    public static final int GL_OBJECT_COMPILE_STATUS_ARB           = 0x8B81;
    public static final int GL_OBJECT_LINK_STATUS_ARB              = 0x8B82;
    public static final int GL_OBJECT_INFO_LOG_LENGTH_ARB          = 0x8B84;
    public static final int GL_OBJECT_ACTIVE_UNIFORMS_ARB          = 0x8B86;
    public static final int GL_OBJECT_ACTIVE_UNIFORM_MAX_LENGTH_ARB = 0x8B87;
    public static final int GL_PROGRAM_OBJECT_ARB                  = 0x8B40;
    public static final int GL_FRAMEBUFFER_EXT            = 0x8D40;
    public static final int GL_COLOR_ATTACHMENT0_EXT      = 0x8CE0;
    public static final int GL_DEPTH_ATTACHMENT_EXT       = 0x8D00;
    public static final int GL_STENCIL_ATTACHMENT_EXT     = 0x8D20;
    public static final int GL_FRAMEBUFFER_COMPLETE_EXT   = 0x8CD5;
    public static final int GL_RENDERBUFFER_EXT           = 0x8D41;

    // --- ARB shader type aliases (same token values as core GL_VERTEX_SHADER / GL_FRAGMENT_SHADER) --
    public static final int GL_VERTEX_SHADER_ARB   = 0x8B31;
    public static final int GL_FRAGMENT_SHADER_ARB = 0x8B30;

    // --- Multitexture (ARB legacy) -------------------------------------------
    public static final int GL_TEXTURE0_ARB = 0x84C0;

    // --- Multitexture / active texture queries --------------------------------
    public static final int GL_ACTIVE_TEXTURE       = 0x84E0;
    public static final int GL_ACTIVE_TEXTURE_ARB   = 0x84E0;

    // --- Texture binding query -----------------------------------------------
    public static final int GL_TEXTURE_BINDING_2D   = 0x8069;

    // --- VAO / buffer binding queries ----------------------------------------
    public static final int GL_VERTEX_ARRAY_BINDING          = 0x85B5;
    public static final int GL_ARRAY_BUFFER_BINDING          = 0x8894;
    public static final int GL_ELEMENT_ARRAY_BUFFER_BINDING  = 0x8895;

    // --- FBO binding queries (separate draw/read + EXT alias) ----------------
    public static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    public static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    public static final int GL_FRAMEBUFFER_BINDING_EXT  = 0x8CA6;

    // --- Blend state queries -------------------------------------------------
    public static final int GL_BLEND_DST_RGB        = 0x80C8;
    public static final int GL_BLEND_SRC_RGB        = 0x80C9;
    public static final int GL_BLEND_DST_ALPHA      = 0x80CA;
    public static final int GL_BLEND_SRC_ALPHA      = 0x80CB;
    public static final int GL_BLEND_EQUATION       = 0x8009;
    public static final int GL_BLEND_EQUATION_RGB   = 0x8009;
    public static final int GL_BLEND_EQUATION_ALPHA = 0x883D;

    // --- Depth state queries -------------------------------------------------
    public static final int GL_DEPTH_FUNC      = 0x0B74;
    public static final int GL_DEPTH_WRITEMASK = 0x0B72;

    // --- Cull / face state queries -------------------------------------------
    public static final int GL_CULL_FACE_MODE = 0x0B45;
    public static final int GL_FRONT_FACE     = 0x0B46;

    // --- Stencil state queries -----------------------------------------------
    public static final int GL_STENCIL_FUNC            = 0x0B92;
    public static final int GL_STENCIL_REF             = 0x0B97;
    public static final int GL_STENCIL_VALUE_MASK      = 0x0B93;
    public static final int GL_STENCIL_WRITEMASK       = 0x0B98;
    public static final int GL_STENCIL_FAIL            = 0x0B94;
    public static final int GL_STENCIL_PASS_DEPTH_FAIL = 0x0B95;
    public static final int GL_STENCIL_PASS_DEPTH_PASS = 0x0B96;

    // --- Color mask query ----------------------------------------------------
    public static final int GL_COLOR_WRITEMASK = 0x0C23;

    // --- Alpha test queries (fixed-function / compat) ------------------------
    public static final int GL_ALPHA_TEST_FUNC = 0x0BC1;
    public static final int GL_ALPHA_TEST_REF  = 0x0BC2;

    // --- Polygon offset queries ----------------------------------------------
    public static final int GL_POLYGON_OFFSET_LINE   = 0x2A02;
    public static final int GL_POLYGON_OFFSET_POINT  = 0x2A01;
    public static final int GL_POLYGON_OFFSET_FACTOR = 0x8038;
    public static final int GL_POLYGON_OFFSET_UNITS  = 0x2A00;

    // --- Polygon mode query --------------------------------------------------
    public static final int GL_POLYGON_MODE = 0x0B40;

    // --- Line / point size queries -------------------------------------------
    public static final int GL_LINE_WIDTH = 0x0B21;
    public static final int GL_POINT_SIZE = 0x0B11;

    // --- Capability limit queries --------------------------------------------
    public static final int GL_MAX_DRAW_BUFFERS   = 0x8824;
    public static final int GL_MAX_TEXTURE_UNITS  = 0x84E2;
    public static final int GL_MAX_VERTEX_ATTRIBS = 0x8869;
    
    // --- Timer queries (GPU timing) ------------------------------------------
    public static final int GL_TIME_ELAPSED = 0x88BF;
    public static final int GL_QUERY_RESULT = 0x8866;
    public static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;
    
    // =========================================================================

    /** @see CgGLBackend#copyImageSubData */
    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                           int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                           int srcWidth, int srcHeight, int srcDepth) {
        backend.copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ,
                dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
    }

    /** @see CgGLBackend#framebufferTextureLayer */
    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        backend.framebufferTextureLayer(target, attachment, texture, level, layer);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                          int dstX0, int dstY0, int dstX1, int dstY1,
                                          int mask, int filter) {
        backend.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public static int glGenFramebuffers() {
        return backend.genFramebuffers();
    }

    public static int glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        return backend.getFramebufferAttachmentParameteriv(target, attachment, pname);
    }

    public static void glDeleteFramebuffers(int fbo) {
        backend.deleteFramebuffers(fbo);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        backend.framebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    public static int glCheckFramebufferStatus(int target) {
        return backend.checkFramebufferStatus(target);
    }

    public static void glDrawBuffers(IntBuffer bufs) {
        backend.drawBuffers(bufs);
    }

    /** Platform-neutral FBO bind that routes through MC's compat helper on mc1710. */
    public static void glBindFramebufferCompat(int fbo) {
        backend.bindFramebufferCompat(fbo);
    }

    // --- Renderbuffer methods (same no-prefix pattern in CgGLBackend) -------

    public static int glGenRenderbuffers() {
        return backend.glGenRenderbuffers();
    }

    public static void glDeleteRenderbuffers(int rbo) {
        backend.glDeleteRenderbuffers(rbo);
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        backend.glBindRenderbuffer(target, renderbuffer);
    }

    public static void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        backend.glRenderbufferStorage(target, internalFormat, width, height);
    }

    public static void glFramebufferRenderbuffer(int target, int attachment,
                                                  int renderbufferTarget, int renderbuffer) {
        backend.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
    }

    // =========================================================================
    // Shaders
    // =========================================================================

    public static int glCreateShader(int type) {
        return backend.glCreateShader(type);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        backend.glShaderSource(shader, source);
    }

    public static void glCompileShader(int shader) {
        backend.glCompileShader(shader);
    }

    public static int glGetShaderi(int shader, int pname) {
        return backend.glGetShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return backend.glGetShaderInfoLog(shader, maxLength);
    }

    public static void glDeleteShader(int shader) {
        backend.glDeleteShader(shader);
    }

    public static int glCreateProgram() {
        return backend.glCreateProgram();
    }

    public static void glAttachShader(int program, int shader) {
        backend.glAttachShader(program, shader);
    }

    public static void glLinkProgram(int program) {
        backend.glLinkProgram(program);
    }

    public static int glGetProgrami(int program, int pname) {
        return backend.glGetProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return backend.glGetProgramInfoLog(program, maxLength);
    }

    public static void glUseProgram(int program) {
        backend.glUseProgram(program);
    }

    public static void glDeleteProgram(int program) {
        backend.glDeleteProgram(program);
    }

    /** ARBShaderObjects unified path: delete a shader or program handle without knowing which type. */
    public static void glDeleteObject(int handle) {
        backend.glDeleteObject(handle);
    }

    /** ARBShaderObjects unified path: query a compile/link parameter on any object handle. */
    public static int glGetObjectParameteri(int obj, int pname) {
        return backend.glGetObjectParameteri(obj, pname);
    }

    /** ARBShaderObjects unified path: get info log for any object handle (shader or program). */
    public static String glGetObjectInfoLog(int obj, int maxLength) {
        return backend.glGetObjectInfoLog(obj, maxLength);
    }

    /** ARBShaderObjects: get the currently bound object handle for the given target (e.g. {@link #GL_PROGRAM_OBJECT_ARB}). */
    public static int glGetHandle(int pname) {
        return backend.glGetHandle(pname);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return backend.glGetUniformLocation(program, name);
    }

    public static void glUniform1i(int location, int v0) {
        backend.glUniform1i(location, v0);
    }

    public static void glUniform1f(int location, float v0) {
        backend.glUniform1f(location, v0);
    }

    public static void glUniform2f(int location, float v0, float v1) {
        backend.glUniform2f(location, v0, v1);
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        backend.glUniform3f(location, v0, v1, v2);
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        backend.glUniform4f(location, v0, v1, v2, v3);
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        backend.glUniformMatrix4fv(location, transpose, value);
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        backend.glBindAttribLocation(program, index, name);
    }

    public static int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return backend.glGetProgramResourceIndex(program, programInterface, name);
    }

    public static void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        backend.glShaderStorageBlockBinding(program, storageBlockIndex, storageBlockBinding);
    }

    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return backend.glGetUniformBlockIndex(program, uniformBlockName);
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        backend.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    public static void glDetachShader(int program, int shader) {
        backend.glDetachShader(program, shader);
    }

    public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        backend.glGetAttachedShaders(program, count, shaders);
    }

    /** Returns the uniform name; fills {@code sizeTypeBuf[0]=size, [1]=type}. */
    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        return backend.glGetActiveUniform(program, index, maxLength, sizeTypeBuf);
    }

    /** Sets a float-array uniform ({@code glUniform1fv} semantics). */
    public static void glUniform1(int location, FloatBuffer values) {
        backend.glUniform1(location, values);
    }

    /** Sets an int-array uniform ({@code glUniform1iv} semantics). */
    public static void glUniform1(int location, IntBuffer values) {
        backend.glUniform1(location, values);
    }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        backend.glUniformMatrix3(location, transpose, value);
    }

    /** Equivalent to {@link #glUniformMatrix4fv}; present for LWJGL2 naming parity. */
    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        backend.glUniformMatrix4(location, transpose, value);
    }

    // =========================================================================
    // Buffers
    // =========================================================================

    public static int glGenBuffers() {
        return backend.glGenBuffers();
    }

    public static void glBindBuffer(int target, int buffer) {
        backend.glBindBuffer(target, buffer);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        backend.glBufferData(target, data, usage);
    }
    
    public static void glBufferData(int target, ShortBuffer data, int usage) {
        backend.glBufferData(target, data, usage);
    }

    public static void glBufferData(int target, long size, int usage) {
        backend.glBufferData(target, size, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        backend.glBufferSubData(target, offset, data);
    }

    public static void glDeleteBuffers(int buffer) {
        backend.glDeleteBuffers(buffer);
    }

    public static void glBindBufferBase(int target, int index, int buffer) {
        backend.glBindBufferBase(target, index, buffer);
    }

    public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        backend.glBindBufferRange(target, index, buffer, offset, size);
    }

    public static void glTexBuffer(int target, int internalFormat, int buffer) {
        backend.glTexBuffer(target, internalFormat, buffer);
    }

    // =========================================================================
    // Vertex Array Objects
    // =========================================================================

    public static int glGenVertexArrays() {
        return backend.glGenVertexArrays();
    }

    public static void glBindVertexArray(int array) {
        backend.glBindVertexArray(array);
    }

    public static void glDeleteVertexArrays(int array) {
        backend.glDeleteVertexArrays(array);
    }

    public static void glEnableVertexAttribArray(int index) {
        backend.glEnableVertexAttribArray(index);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        backend.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    public static void glVertexAttribDivisor(int index, int divisor) {
        backend.glVertexAttribDivisor(index, divisor);
    }

    // =========================================================================
    // Textures
    // =========================================================================

    public static int glGenTextures() {
        return backend.glGenTextures();
    }

    public static void glBindTexture(int target, int texture) {
        backend.glBindTexture(target, texture);
    }

    public static void glDeleteTextures(int texture) {
        backend.glDeleteTextures(texture);
    }

    public static void glTexImage2D(int target, int level, int internalFormat,
                                     int width, int height, int border,
                                     int format, int type, ByteBuffer pixels) {
        backend.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalFormat,
                                     int width, int height, int border,
                                     int format, int type, FloatBuffer pixels) {
        backend.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                     int width, int height, int depth, int border,
                                     int format, int type, ByteBuffer pixels) {
        backend.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                     int width, int height, int depth, int border,
                                     int format, int type, FloatBuffer pixels) {
        backend.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level,
                                        int xOffset, int yOffset, int width, int height,
                                        int format, int type, ByteBuffer pixels) {
        backend.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level,
                                        int xOffset, int yOffset, int width, int height,
                                        int format, int type, FloatBuffer pixels) {
        backend.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glGenerateMipmap(int target) {
        backend.glGenerateMipmap(target);
    }

    public static void glActiveTexture(int texture) {
        backend.glActiveTexture(texture);
    }

    public static void glTexParameteri(int target, int pname, int param) {
        backend.glTexParameteri(target, pname, param);
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        backend.glGetTexImage(target, level, format, type, pixels);
    }

    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, ByteBuffer pixels) {
        backend.glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, FloatBuffer pixels) {
        backend.glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    /** {@code short}-data variant — the natural fit for {@code GL_HALF_FLOAT} uploads. */
    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, ShortBuffer pixels) {
        backend.glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    // =========================================================================
    // Draw calls
    // =========================================================================

    public static void glDrawArrays(int mode, int first, int count) {
        backend.glDrawArrays(mode, first, count);
    }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        backend.glDrawElements(mode, count, type, indices);
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        backend.glDrawArraysInstanced(mode, first, count, instanceCount);
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        backend.glDrawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    // =========================================================================
    // GL state
    // =========================================================================

    public static void glEnable(int cap) {
        backend.glEnable(cap);
    }

    public static void glDisable(int cap) {
        backend.glDisable(cap);
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        backend.glBlendFunc(sfactor, dfactor);
    }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        backend.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    public static void glDepthMask(boolean flag) {
        backend.glDepthMask(flag);
    }

    public static void glCullFace(int mode) {
        backend.glCullFace(mode);
    }

    public static void glViewport(int x, int y, int width, int height) {
        backend.glViewport(x, y, width, height);
    }

    public static void glScissor(int x, int y, int width, int height) {
        backend.glScissor(x, y, width, height);
    }

    public static void glLineWidth(float width) {
        backend.glLineWidth(width);
    }

    public static void glPolygonMode(int face, int mode) {
        backend.glPolygonMode(face, mode);
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        backend.glColorMask(red, green, blue, alpha);
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        backend.glStencilFunc(func, ref, mask);
    }

    public static void glStencilOp(int sfail, int dpfail, int dppass) {
        backend.glStencilOp(sfail, dpfail, dppass);
    }

    public static void glAlphaFunc(int func, float ref) {
        backend.glAlphaFunc(func, ref);
    }

    public static void glDepthFunc(int func) {
        backend.glDepthFunc(func);
    }

    public static void glClear(int mask) {
        backend.glClear(mask);
    }

    public static void glClearDepth(double depth) {
        backend.glClearDepth(depth);
    }

    public static void glClearColor(float r, float g, float b, float a) {
        backend.glClearColor(r, g, b, a);
    }

    public static void glClearStencil(int s) {
        backend.glClearStencil(s);
    }

    public static void glStencilMask(int mask) {
        backend.glStencilMask(mask);
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        backend.glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    /** GL 3.0 per-draw-buffer color mask. */
    public static void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) {
        backend.glColorMaski(buf, r, g, b, a);
    }

    public static void glFrontFace(int mode) {
        backend.glFrontFace(mode);
    }

    public static void glPolygonOffset(float factor, float units) {
        backend.glPolygonOffset(factor, units);
    }

    public static void glPointSize(float size) {
        backend.glPointSize(size);
    }

    public static void glDrawBuffer(int mode) {
        backend.glDrawBuffer(mode);
    }

    public static void glReadBuffer(int mode) {
        backend.glReadBuffer(mode);
    }

    public static void glPixelStorei(int pname, int param) {
        backend.glPixelStorei(pname, param);
    }

    // =========================================================================
    // GL state — queries
    // =========================================================================

    public static int glGetInteger(int pname) {
        return backend.glGetInteger(pname);
    }

    public static void glGetInteger(int pname, IntBuffer params) {
        backend.glGetInteger(pname, params);
    }

    public static boolean glGetBoolean(int pname) {
        return backend.glGetBoolean(pname);
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        backend.glGetBoolean(pname, params);
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        backend.glGetFloat(pname, params);
    }

    public static float glGetFloat(int pname) {
        return backend.glGetFloat(pname);
    }

    // =========================================================================
    // Samplers
    // =========================================================================

    /** Binds a sampler object to a texture unit (ARB_sampler_objects / GL 3.3). */
    public static void glBindSampler(int unit, int sampler) {
        backend.glBindSampler(unit, sampler);
    }

    // =========================================================================
    // Buffer mapping
    // =========================================================================

    /** @return the mapped buffer, or {@code null} if mapping fails */
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        return backend.glMapBufferRange(target, offset, length, access, oldBuffer);
    }

    public static boolean glUnmapBuffer(int target) {
        return backend.glUnmapBuffer(target);
    }

    public static void glFlushMappedBufferRange(int target, long offset, long length) {
        backend.glFlushMappedBufferRange(target, offset, length);
    }
    
    // =========================================================================
    // Sync objects (ARBSync / GL 3.2)
    // =========================================================================

    public static long glFenceSync(int condition, int flags) {
        return backend.glFenceSync(condition, flags);
    }

    public static int glClientWaitSync(long sync, int flags, long timeout) {
        return backend.glClientWaitSync(sync, flags, timeout);
    }

    public static void glDeleteSync(long sync) {
        backend.glDeleteSync(sync);
    }
    
    // =========================================================================
    // Debug
    // =========================================================================
    
    public static int glGetError()  {
        return backend.glGetError();
    }
    
    /**
     * Drain all pending GL errors. Returns a list of error descriptions.
     * If no errors are pending, returns an empty list.
     */
    public static List<String> drainErrors() {
        List<String> errors = new ArrayList<>();
        int count = 0;
        int err;
        while ((err = CgGL.glGetError()) != CgGL.GL_NO_ERROR) {
            String name = errorName(err);
            errors.add("0x" + Integer.toHexString(err) + " (" + name + ")");
            count++;
            if (count > 64) {
                errors.add("... (stopped after 64 errors)");
                break;
            }
        }
        return errors;
    }
    /**
     * Maps a GL error code to a human-readable name.
     */
    private static String errorName(int error) {
        switch (error) {
            case GL_NO_ERROR:                       return "GL_NO_ERROR";
            case GL_INVALID_ENUM:                   return "GL_INVALID_ENUM";
            case GL_INVALID_VALUE:                  return "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION:              return "GL_INVALID_OPERATION";
            case GL_STACK_OVERFLOW:                 return "GL_STACK_OVERFLOW";
            case GL_STACK_UNDERFLOW:                return "GL_STACK_UNDERFLOW";
            case GL_OUT_OF_MEMORY:                  return "GL_OUT_OF_MEMORY";
            case GL_INVALID_FRAMEBUFFER_OPERATION:  return "GL_INVALID_FRAMEBUFFER_OPERATION";
            default:                                return "UNKNOWN";
        }
    }
    
    /**
     * Assert that no GL errors are pending. Throws {@link AssertionError} if any error is found.
     *
     * @param context human-readable description for the error message
     * @throws AssertionError if any GL error was pending
     */
    public static void assertNoGlError(String context) {
        List<String> errors = drainErrors();
        if (!errors.isEmpty()) 
            throw new AssertionError("[GlErrorChecker] GL error(s) after " + context + ": " + errors);
    }


    // --- Timer queries (GPU timing) ------------------------------------------
    // See CgGLBackend for why these are optional and why results must be polled
    // on a later frame rather than read immediately.


    public static int glGenQuery() {
        return backend.glGenQuery();
    }

    public static void glBeginTimeElapsedQuery(int query) {
        backend.glBeginTimeElapsedQuery(query);
    }

    public static void glEndTimeElapsedQuery() {
        backend.glEndTimeElapsedQuery();
    }

    public static boolean glIsQueryResultAvailable(int query) {
        return backend.glIsQueryResultAvailable(query);
    }

    public static long glGetQueryResultNanos(int query) {
        return backend.glGetQueryResultNanos(query);
    }

    public static void glDeleteQuery(int query) {
        backend.glDeleteQuery(query);
    }

    public static void glBindFramebuffer(int target, int fbo) {
        backend.bindFramebuffer(target, fbo);
    }

    // =========================================================================
    // Context
    // =========================================================================

    /** @return {@code true} if an OpenGL context is current on this thread. */
    public static boolean isContextCurrent() {
        return backend.isContextCurrent();
    }

    // =========================================================================
    // Fixed-function matrix stack
    // =========================================================================

    public static void glPushMatrix() {
        backend.glPushMatrix();
    }

    public static void glPopMatrix() {
        backend.glPopMatrix();
    }

    public static void glLoadMatrix(FloatBuffer m) {
        backend.glLoadMatrix(m);
    }
}
