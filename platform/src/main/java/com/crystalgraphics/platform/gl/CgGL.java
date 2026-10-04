package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.gl.state.CgGlState;

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

    /**
     * Installs {@code dispatch}, or uninstalls with null. A host never calls this: the first {@link #fromHost()}
     * or {@link CgCapabilities#detect()} takes the platform's. A harness or test that uses {@code CgGL} before
     * either installs its own.
     */
    public static void init(CgGLBackend dispatch){ backend = dispatch; }

    /** Whether a backend is installed: never on a dedicated server, and on a client from its first host section. */
    public static boolean isInstalled() {
        return backend != null;
    }

    /** Takes the registered platform's backend if none is installed. */
    static void installIfAbsent() {
        if (backend == null) backend = CgPlatform.gl();
    }

    /**
     * Whether this context is a core profile. Set by {@link CgCapabilities#detect()}.
     *
     * <p>A field rather than {@code CgCapabilities.detect().isCoreProfile()} because every
     * fixed-function entry point below tests it, so it sits on a hot path and should cost a load
     * rather than a cached lookup, a null check and a getter across two classes.</p>
     *
     * <p><b>False until the first {@code detect()}</b>, which is the whole of the contract. That is
     * safe because capability probing happens during context init, long before anything paints -- and
     * it is why this is not assigned in {@link #init}: installing a backend is not probing it.</p>
     */
    public static boolean CORE;

    // ── State tracking ────────────────────────────────────────────────────────
    //
    // Each state setter below asks the manager whether the value actually changed, and skips the driver
    // call when it did not. This is the single chokepoint the whole design rests on: every caller — the
    // typed state records, raw CgGL users, other mods — is tracked and deduplicated identically, and none
    // of them has to know it is happening.
    //
    // The design this replaces tracked state in core at the level of composite value objects, which left
    // two paths and needed an observer, a bridge and a build-time guard to hold them together. Callers had
    // to announce raw writes; forty-six hand-placed notifications later, a base class was still missed.

    private static CgGlStateManager state() {
        if (CgGLAudit.ON) CgGLAudit.check(backend);
        return CgGlState.manager();
    }

    private static CgGLBackend gl() {
        if (CgGLAudit.ON) CgGLAudit.check(backend);
        return backend;
    }

    /**
     * Marks this thread as recording, until {@link #exitGlFree()}: nothing in between may reach GL. Under
     * {@code -Dcrystalgraphics.gl.threadCheck=true} every call that does is logged with its caller; otherwise it does
     * nothing.
     *
     * <pre>{@code
     * CgGL.enterGlFree("ui recording");
     * try { record(); } finally { CgGL.exitGlFree(); }
     * }</pre>
     */
    public static void enterGlFree(String section) {
        CgGLAudit.enter(section);
    }

    /** @see #enterGlFree */
    public static void exitGlFree() {
        CgGLAudit.exit();
    }

    /**
     * Whether this thread may issue GL now: it owns the context and is not recording. What core's {@code CgDeferral}
     * asks before doing a GPU object's work at once rather than handing it to the render thread; nothing else should
     * need to.
     */
    public static boolean mayIssueGl() {
        return backend != null && backend.ownedByCurrentThread() && !CgGLAudit.inSection();
    }

    
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
    public static final int GL_DRAW_INDIRECT_BUFFER     = 0x8F3F;
    public static final int GL_DISPATCH_INDIRECT_BUFFER = 0x90EE;
    /** Where an indirect draw's count is read: GL 4.6, and {@code GL_PARAMETER_BUFFER_ARB} has the same value. */
    public static final int GL_PARAMETER_BUFFER         = 0x80EE;
    public static final int GL_TRANSFORM_FEEDBACK_BUFFER = 0x8C8E;

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
		GL_MAP_UNSYNCHRONIZED_BIT = 0x20,
		GL_MAP_PERSISTENT_BIT = 0x40,
		GL_MAP_COHERENT_BIT = 0x80;

    // --- Buffer binding query params -----------------------------------------
    public static final int GL_UNIFORM_BUFFER_BINDING        = 0x8A28;
    public static final int GL_MAX_UNIFORM_BUFFER_BINDINGS   = 0x8A2F;
    public static final int GL_SHADER_STORAGE_BUFFER_BINDING = 0x90D3;
    public static final int GL_SHADER_STORAGE_BUFFER_START   = 0x90D4;
    public static final int GL_SHADER_STORAGE_BUFFER_SIZE    = 0x90D5;
    public static final int GL_DRAW_INDIRECT_BUFFER_BINDING     = 0x8F43;
    public static final int GL_DISPATCH_INDIRECT_BUFFER_BINDING = 0x90EF;
    public static final int GL_PARAMETER_BUFFER_BINDING         = 0x80EF;
    public static final int GL_TRANSFORM_FEEDBACK_BUFFER_BINDING = 0x8C8F;
    public static final int GL_TRANSFORM_FEEDBACK_BUFFER_START   = 0x8C84;
    public static final int GL_TRANSFORM_FEEDBACK_BUFFER_SIZE    = 0x8C85;

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
    /** Texture target for a multisampled texture attachment. */
    public static final int GL_TEXTURE_2D_MULTISAMPLE   = 0x9100;
    /** Query for the maximum sample count the driver supports — a request above this is clamped. */
    public static final int GL_MAX_SAMPLES              = 0x8D57;
    public static final int GL_MAX_COLOR_TEXTURE_SAMPLES = 0x910E;
    public static final int GL_MAX_DEPTH_TEXTURE_SAMPLES = 0x910F;
    public static final int GL_FRAMEBUFFER_BINDING      = 0x8CA6;

    // --- Clear / blit bits ---------------------------------------------------
    public static final int GL_COLOR_BUFFER_BIT   = 0x00004000;
    public static final int GL_DEPTH_BUFFER_BIT   = 0x00000100;
    public static final int GL_STENCIL_BUFFER_BIT = 0x00000400;

    // --- Framebuffer attachment query ----------------------------------------
    public static final int GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE    = 0x8CD0;
    public static final int GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE = 0x8211;
    public static final int GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE     = 0x8216;

    // --- Shader types --------------------------------------------------------
    public static final int GL_VERTEX_SHADER   = 0x8B31;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_GEOMETRY_SHADER = 0x8DD9;
    public static final int GL_COMPUTE_SHADER  = 0x91B9;

    // --- Shader / program query params ---------------------------------------
    public static final int GL_COMPILE_STATUS            = 0x8B81;
    public static final int GL_LINK_STATUS               = 0x8B82;
    /** Only where {@code CgCapabilities.isParallelShaderCompile()}; the ARB extension's value is the same. */
    public static final int GL_COMPLETION_STATUS_KHR     = 0x91B1;
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
    public static final int GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT = 0x90DF;

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
    public static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    public static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
    public static final int GL_SYNC_FLUSH_COMMANDS_BIT    = 0x00000001;
    public static final int GL_ALREADY_SIGNALED           = 0x911A;
    public static final int GL_TIMEOUT_EXPIRED            = 0x911B;
    public static final int GL_CONDITION_SATISFIED        = 0x911C;
    public static final int GL_WAIT_FAILED                = 0x911D;

    // --- Queries / gets ------------------------------------------------------
    public static final int GL_VENDOR                           = 0x1F00;
    public static final int GL_RENDERER                         = 0x1F01;
    public static final int GL_VERSION                          = 0x1F02;
    public static final int GL_EXTENSIONS                       = 0x1F03;
    public static final int GL_SHADING_LANGUAGE_VERSION         = 0x8B8C;
    public static final int GL_MAJOR_VERSION                    = 0x821B;
    public static final int GL_MINOR_VERSION                    = 0x821C;
    public static final int GL_NUM_EXTENSIONS                   = 0x821D;
    public static final int GL_CONTEXT_PROFILE_MASK             = 0x9126;
    public static final int GL_MAX_TEXTURE_SIZE                 = 0x0D33;
    public static final int GL_MAX_TEXTURE_BUFFER_SIZE          = 0x8C2B;
    public static final int GL_MAX_IMAGE_UNITS                  = 0x8F38;
    public static final int GL_MAX_SHADER_STORAGE_BLOCK_SIZE    = 0x90DE;
    public static final int GL_MAX_GEOMETRY_OUTPUT_VERTICES     = 0x8DE0;
    public static final int GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS = 0x8DE1;

    // --- Compute -------------------------------------------------------------
    public static final int GL_MAX_COMPUTE_WORK_GROUP_COUNT       = 0x91BE;
    public static final int GL_MAX_COMPUTE_WORK_GROUP_SIZE        = 0x91BF;
    public static final int GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS = 0x90EB;
    public static final int GL_MAX_COMPUTE_SHARED_MEMORY_SIZE     = 0x8262;
    /** {@code KHR_shader_subgroup}: the invocations in a subgroup, the stages with subgroup operations, and which. */
    public static final int GL_SUBGROUP_SIZE_KHR                  = 0x9532;
    public static final int GL_SUBGROUP_SUPPORTED_STAGES_KHR      = 0x9533;
    public static final int GL_SUBGROUP_SUPPORTED_FEATURES_KHR    = 0x9534;

    // --- Memory barriers: what glMemoryBarrier makes see a kernel's writes -------
    public static final int GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT  = 0x0001;
    public static final int GL_ELEMENT_ARRAY_BARRIER_BIT        = 0x0002;
    public static final int GL_UNIFORM_BARRIER_BIT              = 0x0004;
    public static final int GL_TEXTURE_FETCH_BARRIER_BIT        = 0x0008;
    public static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT  = 0x0020;
    public static final int GL_COMMAND_BARRIER_BIT              = 0x0040;
    public static final int GL_PIXEL_BUFFER_BARRIER_BIT         = 0x0080;
    public static final int GL_TEXTURE_UPDATE_BARRIER_BIT       = 0x0100;
    public static final int GL_BUFFER_UPDATE_BARRIER_BIT        = 0x0200;
    public static final int GL_FRAMEBUFFER_BARRIER_BIT          = 0x0400;
    public static final int GL_TRANSFORM_FEEDBACK_BARRIER_BIT   = 0x0800;
    public static final int GL_ATOMIC_COUNTER_BARRIER_BIT       = 0x1000;
    public static final int GL_SHADER_STORAGE_BARRIER_BIT       = 0x2000;
    public static final int GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT = 0x4000;
    public static final int GL_ALL_BARRIER_BITS                 = 0xFFFFFFFF;

    // --- Image units ---------------------------------------------------------
    public static final int GL_READ_ONLY  = 0x88B8;
    public static final int GL_WRITE_ONLY = 0x88B9;
    public static final int GL_READ_WRITE = 0x88BA;
    /** Indexed by unit, through {@link #glGetIntegeri}. */
    public static final int GL_IMAGE_BINDING_NAME    = 0x8F3A;
    public static final int GL_IMAGE_BINDING_LEVEL   = 0x8F3B;
    public static final int GL_IMAGE_BINDING_LAYERED = 0x8F3C;
    public static final int GL_IMAGE_BINDING_LAYER   = 0x8F3D;
    public static final int GL_IMAGE_BINDING_ACCESS  = 0x8F3E;
    public static final int GL_IMAGE_BINDING_FORMAT  = 0x906E;

    // --- Transform feedback --------------------------------------------------
    public static final int GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS   = 0x8C80;
    public static final int GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS = 0x8C8A;
    public static final int GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS     = 0x8C8B;
    public static final int GL_MAX_TRANSFORM_FEEDBACK_BUFFERS              = 0x8E70;
    public static final int GL_INTERLEAVED_ATTRIBS                         = 0x8C8C;
    public static final int GL_SEPARATE_ATTRIBS                            = 0x8C8D;
    public static final int GL_RASTERIZER_DISCARD                          = 0x8C89;
    public static final int GL_MAX_3D_TEXTURE_SIZE              = 0x8073;
    public static final int GL_MAX_ARRAY_TEXTURE_LAYERS         = 0x88FF;
    public static final int GL_MAX_TEXTURE_IMAGE_UNITS          = 0x8872;
    public static final int GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D;
    public static final int GL_VIEWPORT                         = 0x0BA2;
    public static final int GL_SCISSOR_BOX                      = 0x0C10;
    public static final int GL_CURRENT_PROGRAM                  = 0x8B8D;

    // --- Pixel pack/unpack ---------------------------------------------------
    public static final int GL_UNPACK_ROW_LENGTH   = 0x0CF2;
    public static final int GL_UNPACK_SKIP_ROWS    = 0x0CF3;
    public static final int GL_UNPACK_SKIP_PIXELS  = 0x0CF4;
    public static final int GL_UNPACK_ALIGNMENT    = 0x0CF5;
    public static final int GL_UNPACK_SKIP_IMAGES  = 0x806D;
    public static final int GL_UNPACK_IMAGE_HEIGHT = 0x806E;
    public static final int GL_PACK_ALIGNMENT      = 0x0D05;

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

    // --- Multitexture / active texture queries --------------------------------
    public static final int GL_ACTIVE_TEXTURE       = 0x84E0;

    // --- Texture binding query -----------------------------------------------
    public static final int GL_TEXTURE_BINDING_2D   = 0x8069;

    // --- VAO / buffer binding queries ----------------------------------------
    public static final int GL_VERTEX_ARRAY_BINDING          = 0x85B5;
    public static final int GL_ARRAY_BUFFER_BINDING          = 0x8894;
    public static final int GL_ELEMENT_ARRAY_BUFFER_BINDING  = 0x8895;

    // --- FBO binding queries ------------------------------------------------
    public static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    public static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;

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
        gl().copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ,
                dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
    }

    /** @see CgGLBackend#framebufferTextureLayer */
    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        gl().framebufferTextureLayer(target, attachment, texture, level, layer);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                          int dstX0, int dstY0, int dstX1, int dstY1,
                                          int mask, int filter) {
        gl().blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public static int glGenFramebuffers() {
        return gl().genFramebuffers();
    }

    public static int glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        return gl().getFramebufferAttachmentParameteriv(target, attachment, pname);
    }

    public static void glDeleteFramebuffers(int fbo) {
        gl().deleteFramebuffers(fbo);
        state().framebufferDeleted(fbo);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        gl().framebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    public static int glCheckFramebufferStatus(int target) {
        return gl().checkFramebufferStatus(target);
    }

    public static void glDrawBuffers(IntBuffer bufs) {
        gl().drawBuffers(bufs);
    }

    // --- Renderbuffer methods (same no-prefix pattern in CgGLBackend) -------

    public static int glGenRenderbuffers() {
        return gl().glGenRenderbuffers();
    }

    public static void glDeleteRenderbuffers(int rbo) {
        gl().glDeleteRenderbuffers(rbo);
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        gl().glBindRenderbuffer(target, renderbuffer);
    }

    /**
     * Multisampled renderbuffer storage. {@code samples} is clamped by the driver to what it supports.
     *
     * @see CgGLBackend#glRenderbufferStorageMultisample
     */
    public static void glRenderbufferStorageMultisample(int target, int samples, int internalFormat,
                                                        int width, int height) {
        gl().glRenderbufferStorageMultisample(target, samples, internalFormat, width, height);
    }

    /**
     * Multisampled texture storage for {@code GL_TEXTURE_2D_MULTISAMPLE}.
     *
     * @see CgGLBackend#glTexImage2DMultisample
     */
    public static void glTexImage2DMultisample(int target, int samples, int internalFormat,
                                               int width, int height, boolean fixedSampleLocations) {
        gl().glTexImage2DMultisample(target, samples, internalFormat, width, height,
                fixedSampleLocations);
    }

    public static void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        gl().glRenderbufferStorage(target, internalFormat, width, height);
    }

    public static void glFramebufferRenderbuffer(int target, int attachment,
                                                  int renderbufferTarget, int renderbuffer) {
        gl().glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
    }

    // =========================================================================
    // Shaders
    // =========================================================================

    public static int glCreateShader(int type) {
        return gl().glCreateShader(type);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        gl().glShaderSource(shader, source);
    }

    public static void glCompileShader(int shader) {
        gl().glCompileShader(shader);
    }

    public static int glGetShaderi(int shader, int pname) {
        return gl().glGetShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return gl().glGetShaderInfoLog(shader, maxLength);
    }

    public static void glDeleteShader(int shader) {
        gl().glDeleteShader(shader);
    }

    public static int glCreateProgram() {
        return gl().glCreateProgram();
    }

    public static void glAttachShader(int program, int shader) {
        gl().glAttachShader(program, shader);
    }

    public static void glLinkProgram(int program) {
        gl().glLinkProgram(program);
    }

    public static int glGetProgrami(int program, int pname) {
        return gl().glGetProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return gl().glGetProgramInfoLog(program, maxLength);
    }

    public static void glUseProgram(int program) {
        if (state().programChanged(program)) gl().glUseProgram(program);
    }

    public static void glDeleteProgram(int program) {
        gl().glDeleteProgram(program);
        state().programDeleted(program);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return gl().glGetUniformLocation(program, name);
    }

    public static void glUniform1i(int location, int v0) {
        gl().glUniform1i(location, v0);
    }

    public static void glUniform1f(int location, float v0) {
        gl().glUniform1f(location, v0);
    }

    public static void glUniform2f(int location, float v0, float v1) {
        gl().glUniform2f(location, v0, v1);
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        gl().glUniform3f(location, v0, v1, v2);
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        gl().glUniform4f(location, v0, v1, v2, v3);
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        gl().glUniformMatrix4fv(location, transpose, value);
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        gl().glBindAttribLocation(program, index, name);
    }

    public static int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return gl().glGetProgramResourceIndex(program, programInterface, name);
    }

    public static void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        gl().glShaderStorageBlockBinding(program, storageBlockIndex, storageBlockBinding);
    }

    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return gl().glGetUniformBlockIndex(program, uniformBlockName);
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        gl().glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    public static void glDetachShader(int program, int shader) {
        gl().glDetachShader(program, shader);
    }

    public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        gl().glGetAttachedShaders(program, count, shaders);
    }

    /** Returns the uniform name; fills {@code sizeTypeBuf[0]=size, [1]=type}. */
    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        return gl().glGetActiveUniform(program, index, maxLength, sizeTypeBuf);
    }

    /** Sets a float-array uniform ({@code glUniform1fv} semantics). */
    public static void glUniform1(int location, FloatBuffer values) {
        gl().glUniform1(location, values);
    }

    /** Sets an int-array uniform ({@code glUniform1iv} semantics). */
    public static void glUniform1(int location, IntBuffer values) {
        gl().glUniform1(location, values);
    }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        gl().glUniformMatrix3(location, transpose, value);
    }

    /** Equivalent to {@link #glUniformMatrix4fv}; present for LWJGL2 naming parity. */
    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        gl().glUniformMatrix4(location, transpose, value);
    }

    // =========================================================================
    // Buffers
    // =========================================================================

    public static int glGenBuffers() {
        return gl().glGenBuffers();
    }

    public static void glBindBuffer(int target, int buffer) {
        if (state().bufferChanged(target, buffer)) gl().glBindBuffer(target, buffer);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        gl().glBufferData(target, data, usage);
    }
    
    public static void glBufferData(int target, ShortBuffer data, int usage) {
        gl().glBufferData(target, data, usage);
    }

    public static void glBufferData(int target, long size, int usage) {
        gl().glBufferData(target, size, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        gl().glBufferSubData(target, offset, data);
    }

    public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        gl().glCopyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    public static void glDeleteBuffers(int buffer) {
        gl().glDeleteBuffers(buffer);
        state().bufferDeleted(buffer);
    }

    public static void glBindBufferBase(int target, int index, int buffer) {
        if (target == GL_SHADER_STORAGE_BUFFER && !state().storageBindingChanged(index, buffer, 0, 0)) return;
        if (target == GL_TRANSFORM_FEEDBACK_BUFFER && !state().feedbackBindingChanged(index, buffer, 0, 0)) return;
        gl().glBindBufferBase(target, index, buffer);
    }

    public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (target == GL_SHADER_STORAGE_BUFFER && !state().storageBindingChanged(index, buffer, offset, size)) return;
        if (target == GL_TRANSFORM_FEEDBACK_BUFFER && !state().feedbackBindingChanged(index, buffer, offset, size)) return;
        gl().glBindBufferRange(target, index, buffer, offset, size);
    }

    public static void glTexBuffer(int target, int internalFormat, int buffer) {
        gl().glTexBuffer(target, internalFormat, buffer);
    }

    // =========================================================================
    // Vertex Array Objects
    // =========================================================================

    public static int glGenVertexArrays() {
        return gl().glGenVertexArrays();
    }

    public static void glBindVertexArray(int array) {
        if (state().vertexArrayChanged(array)) gl().glBindVertexArray(array);
    }

    public static void glDeleteVertexArrays(int array) {
        gl().glDeleteVertexArrays(array);
        state().vertexArrayDeleted(array);
    }

    public static void glEnableVertexAttribArray(int index) {
        gl().glEnableVertexAttribArray(index);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        gl().glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        gl().glVertexAttribIPointer(index, size, type, stride, pointer);
    }

    public static void glVertexAttribDivisor(int index, int divisor) {
        gl().glVertexAttribDivisor(index, divisor);
    }

    // =========================================================================
    // Textures
    // =========================================================================

    public static int glGenTextures() {
        return gl().glGenTextures();
    }

    public static void glBindTexture(int target, int texture) {
        if (state().textureChanged(target, texture)) gl().glBindTexture(target, texture);
    }

    public static void glDeleteTextures(int texture) {
        gl().glDeleteTextures(texture);
        // The shadow must forget it or the next bind of a recycled id is elided. @see CgGlStateManager
        state().textureDeleted(texture);
    }

    public static void glTexImage2D(int target, int level, int internalFormat,
                                     int width, int height, int border,
                                     int format, int type, ByteBuffer pixels) {
        gl().glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalFormat,
                                     int width, int height, int border,
                                     int format, int type, FloatBuffer pixels) {
        gl().glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                     int width, int height, int depth, int border,
                                     int format, int type, ByteBuffer pixels) {
        gl().glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    public static void glTexImage3D(int target, int level, int internalFormat,
                                     int width, int height, int depth, int border,
                                     int format, int type, FloatBuffer pixels) {
        gl().glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level,
                                        int xOffset, int yOffset, int width, int height,
                                        int format, int type, ByteBuffer pixels) {
        gl().glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level,
                                        int xOffset, int yOffset, int width, int height,
                                        int format, int type, FloatBuffer pixels) {
        gl().glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glGenerateMipmap(int target) {
        gl().glGenerateMipmap(target);
    }

    public static void glActiveTexture(int texture) {
        if (state().activeTextureChanged(texture)) gl().glActiveTexture(texture);
    }

    public static void glTexParameteri(int target, int pname, int param) {
        gl().glTexParameteri(target, pname, param);
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        gl().glGetTexImage(target, level, format, type, pixels);
    }

    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, ByteBuffer pixels) {
        gl().glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, FloatBuffer pixels) {
        gl().glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    /** {@code short}-data variant — the natural fit for {@code GL_HALF_FLOAT} uploads. */
    public static void glTexSubImage3D(int target, int level,
                                        int xOffset, int yOffset, int zOffset,
                                        int width, int height, int depth,
                                        int format, int type, ShortBuffer pixels) {
        gl().glTexSubImage3D(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    // =========================================================================
    // Draw calls
    // =========================================================================

    public static void glDrawArrays(int mode, int first, int count) {
        gl().glDrawArrays(mode, first, count);
    }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        gl().glDrawElements(mode, count, type, indices);
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        gl().glDrawArraysInstanced(mode, first, count, instanceCount);
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        gl().glDrawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    public static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices,
                                                         int instanceCount, int baseVertex) {
        gl().glDrawElementsInstancedBaseVertex(mode, count, type, indices, instanceCount, baseVertex);
    }

    /**
     * A draw whose arguments the GPU holds, at {@code offset} in the bound {@code GL_DRAW_INDIRECT_BUFFER}: four
     * {@code uint}s, {@code count, instanceCount, first, baseInstance}.
     *
     * <pre>{@code
     * CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, args);
     * CgGL.glDrawArraysIndirect(CgGL.GL_TRIANGLES, 0);
     * }</pre>
     */
    public static void glDrawArraysIndirect(int mode, long offset) {
        gl().glDrawArraysIndirect(mode, offset);
    }

    /** Five {@code uint}s at {@code offset}: {@code count, instanceCount, firstIndex, baseVertex, baseInstance}. */
    public static void glDrawElementsIndirect(int mode, int type, long offset) {
        gl().glDrawElementsIndirect(mode, type, offset);
    }

    /** {@code drawCount} of {@link #glDrawArraysIndirect}'s arguments, {@code stride} bytes apart (0: packed). */
    public static void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) {
        gl().glMultiDrawArraysIndirect(mode, offset, drawCount, stride);
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) {
        gl().glMultiDrawElementsIndirect(mode, type, offset, drawCount, stride);
    }

    /**
     * As many draws as the {@code uint} at {@code countOffset} in the bound {@code GL_PARAMETER_BUFFER} says, at
     * most {@code maxDrawCount}: a count a kernel wrote, with no readback.
     *
     * <pre>{@code
     * CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, args);
     * CgGL.glBindBuffer(CgGL.GL_PARAMETER_BUFFER, args);           // the count beside the arguments
     * CgGL.glMultiDrawArraysIndirectCount(CgGL.GL_TRIANGLES, 16, 0, maxDraws, 0);
     * }</pre>
     */
    public static void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount,
                                                      int stride) {
        gl().glMultiDrawArraysIndirectCount(mode, offset, countOffset, maxDrawCount, stride);
    }

    public static void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset,
                                                        int maxDrawCount, int stride) {
        gl().glMultiDrawElementsIndirectCount(mode, type, offset, countOffset, maxDrawCount, stride);
    }

    // =========================================================================
    // Compute
    // =========================================================================

    /**
     * Runs the current program's kernel over a grid of work groups.
     *
     * <pre>{@code
     * CgGL.glUseProgram(simulate);
     * CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, particles);
     * CgGL.glDispatchCompute((count + 63) / 64, 1, 1);
     * CgGL.cgBufferBarrier(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // before the draw reading them
     * }</pre>
     */
    /**
     * The vertex or geometry outputs {@code program} captures into the bound {@code GL_TRANSFORM_FEEDBACK_BUFFER}s, as
     * {@link #GL_INTERLEAVED_ATTRIBS} into point 0 or {@link #GL_SEPARATE_ATTRIBS} one point each. Before linking.
     * GL 3.0; a device has none, and refuses it.
     *
     * <pre>{@code
     * CgGL.glTransformFeedbackVaryings(program, new String[]{"_cg_c0", "_cg_c1"}, CgGL.GL_INTERLEAVED_ATTRIBS);
     * CgGL.glLinkProgram(program);
     * CgGL.glBindBufferRange(CgGL.GL_TRANSFORM_FEEDBACK_BUFFER, 0, out, 0, bytes);
     * CgGL.glEnable(CgGL.GL_RASTERIZER_DISCARD);
     * CgGL.glBeginTransformFeedback(CgGL.GL_POINTS);
     * CgGL.glDrawArrays(CgGL.GL_POINTS, 0, count);
     * CgGL.glEndTransformFeedback();
     * CgGL.glDisable(CgGL.GL_RASTERIZER_DISCARD);
     * }</pre>
     */
    public static void glTransformFeedbackVaryings(int program, String[] varyings, int bufferMode) {
        gl().glTransformFeedbackVaryings(program, varyings, bufferMode);
    }

    /** Starts capturing {@code primitiveMode} ({@code GL_POINTS}, {@code GL_LINES} or {@code GL_TRIANGLES}). */
    public static void glBeginTransformFeedback(int primitiveMode) {
        gl().glBeginTransformFeedback(primitiveMode);
    }

    public static void glEndTransformFeedback() {
        gl().glEndTransformFeedback();
    }

    public static void glDispatchCompute(int groupsX, int groupsY, int groupsZ) {
        gl().glDispatchCompute(groupsX, groupsY, groupsZ);
    }

    /** Three {@code uint} group counts at {@code offset} in the bound {@code GL_DISPATCH_INDIRECT_BUFFER}. */
    public static void glDispatchComputeIndirect(long offset) {
        gl().glDispatchComputeIndirect(offset);
    }

    /** GL's barrier, naming no resource. Engine code says which and for whom: {@link #cgBufferBarrier}. */
    public static void glMemoryBarrier(int barriers) {
        gl().glMemoryBarrier(barriers);
    }

    /**
     * One level of {@code texture} as image unit {@code unit}, in {@code format}, the texture's own internal format.
     * The kernel's image uniform names the unit through {@link #glUniform1i}.
     */
    public static void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access,
                                          int format) {
        if (!state().imageBindingChanged(unit, texture, level, layered ? -1 : layer, access, format)) return;
        gl().glBindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    /**
     * {@code buffer}'s uses at {@code from} finished before {@code to}, as {@link CgAccess} bits: the reader's
     * {@code glMemoryBarrier} bits on GL, this exact barrier on a device.
     *
     * <pre>{@code
     * CgGL.cgBufferBarrier(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
     * CgGL.cgBufferBarrier(args, CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT);
     * }</pre>
     */
    public static void cgBufferBarrier(int buffer, int from, int to) {
        gl().cgBufferBarrier(buffer, from, to);
    }

    /**
     * {@code value} into every four bytes of {@code buffer} from {@code offset} for {@code size} bytes, both multiples
     * of 4: an append buffer's count zeroed, a histogram cleared. A device fill on the tracked backend.
     *
     * <pre>{@code
     * CgGL.cgFillBuffer(counts, 0, 4L * bins, 0);
     * }</pre>
     */
    public static void cgFillBuffer(int buffer, long offset, long size, int value) {
        gl().cgFillBuffer(buffer, offset, size, value);
    }

    /** {@link #cgBufferBarrier} for a texture: {@code cgImageBarrier(density, COMPUTE_WRITE, SAMPLED_READ)}. */
    public static void cgImageBarrier(int texture, int from, int to) {
        gl().cgImageBarrier(texture, from, to);
    }

    /**
     * What follows, kernels, barriers and transfers but no draw, runs on a compute queue beside the frame's where the
     * device has one ({@code CgCapabilities.asyncCompute()}), starting after everything before it, until
     * {@link #cgEndAsync}. Elsewhere, GL included, it runs in order with the same result. The frame graph's executor
     * brackets an {@code async()} compute pass with these.
     *
     * <pre>{@code
     * CgGL.cgBeginAsync();
     * ... the pass's dispatches ...
     * long done = CgGL.cgEndAsync();
     * ... draws touching nothing it touched, overlapping it ...
     * CgGL.cgWaitAsync(done);   // before anything that does
     * }</pre>
     */
    public static void cgBeginAsync() {
        gl().cgBeginAsync();
    }

    /** Back to the frame's queue: the point {@link #cgWaitAsync} waits for, 0 where the work ran in order. */
    public static long cgEndAsync() {
        return gl().cgEndAsync();
    }

    /** What follows on the frame's queue runs after the async work up to {@code point}. */
    public static void cgWaitAsync(long point) {
        gl().cgWaitAsync(point);
    }

    // =========================================================================
    // GL state
    // =========================================================================

    /**
     * <p><b>{@code GL_ALPHA_TEST} is dropped on a core profile</b>, where the alpha test does not
     * exist. Per CAP rather than per method, unlike the fixed-function block further down: every other
     * capability these two take is perfectly valid on a core profile, so an early return would break
     * blending, depth and scissor along with it.</p>
     *
     * <p>Dropping the DISABLE is exact rather than lenient -- the state a caller asks for is already
     * what it gets, since there is no test to fail. Dropping the ENABLE is the lossy one, and it is
     * still the right answer here: a backend without a fixed-function pipeline refuses it outright, so
     * the choice is between a no-op and a crash, and a caller written for 1.7.10 cannot act on either.</p>
     */
    public static void glEnable(int cap) {
        if (cap == GL_ALPHA_TEST && CORE) return;
        if (state().capabilityChanged(cap, true)) gl().glEnable(cap);
    }

    public static void glDisable(int cap) {
        if (cap == GL_ALPHA_TEST && CORE) return;
        if (state().capabilityChanged(cap, false)) gl().glDisable(cap);
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        if (state().blendFuncChanged(sfactor, dfactor, sfactor, dfactor)) gl().glBlendFunc(sfactor, dfactor);
    }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (state().blendFuncChanged(srcRGB, dstRGB, srcAlpha, dstAlpha)) gl().glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    public static void glDepthMask(boolean flag) {
        if (state().depthMaskChanged(flag)) gl().glDepthMask(flag);
    }

    public static void glCullFace(int mode) {
        if (state().cullFaceChanged(mode)) gl().glCullFace(mode);
    }

    public static void glViewport(int x, int y, int width, int height) {
        if (state().viewportChanged(x, y, width, height)) gl().glViewport(x, y, width, height);
    }

    public static void glScissor(int x, int y, int width, int height) {
        if (state().scissorChanged(x, y, width, height)) gl().glScissor(x, y, width, height);
    }

    public static void glLineWidth(float width) {
        if (state().lineWidthChanged(width)) gl().glLineWidth(width);
    }

    public static void glPolygonMode(int face, int mode) {
        if (state().polygonModeChanged(face, mode)) gl().glPolygonMode(face, mode);
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (state().colorMaskChanged(red, green, blue, alpha)) gl().glColorMask(red, green, blue, alpha);
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        if (state().stencilFuncChanged(func, ref, mask)) gl().glStencilFunc(func, ref, mask);
    }

    public static void glStencilOp(int sfail, int dpfail, int dppass) {
        if (state().stencilOpChanged(sfail, dpfail, dppass)) gl().glStencilOp(sfail, dpfail, dppass);
    }

    /**
     * <b>A no-op on a core profile</b>, where there is no alpha test to configure.
     */
    public static void glAlphaFunc(int func, float ref) {
        if (CORE) return;
        if (state().alphaFuncChanged(func, ref)) gl().glAlphaFunc(func, ref);
    }

    public static void glDepthFunc(int func) {
        int issued = depthReversed && !replaying && !state().restoring() ? mirroredDepthFunc(func) : func;
        if (state().depthFuncChanged(issued)) gl().glDepthFunc(issued);
    }

    public static void glClear(int mask) {
        gl().glClear(mask);
    }

    public static void glClearDepth(double depth) {
        gl().glClearDepth(depthReversed && !replaying ? 1.0 - depth : depth);
    }

    // --- Reversed depth --------------------------------------------------------

    private static boolean depthReversed;
    private static boolean depthZeroToOne;

    /** Set while a {@link CgGlRecording} replays: what it recorded was already mirrored. */
    static boolean replaying;

    /**
     * Draws what follows against a REVERSED depth buffer -- nearer is greater, cleared to 0 -- with every
     * caller still writing standard compare functions and clear values. For a host whose world is reversed-Z
     * (Minecraft 26.2); off for anything drawn into our own targets.
     *
     * <pre>{@code
     * CgGL.setDepthReversed(true);
     * try {
     *     CgRenderStage.WORLD_OPAQUE.fire();
     * } finally {
     *     CgGL.setDepthReversed(false);
     * }
     * }</pre>
     *
     * <ul>
     *   <li>{@link #glDepthFunc} issues the mirror of a caller's function ({@code LEQUAL} becomes
     *       {@code GEQUAL}), {@link #glClearDepth} clears to {@code 1 - depth}, and {@link #glPolygonOffset}
     *       negates both terms. A scope's restore and a recording's replay are exempt: each re-issues values
     *       already as GL holds them.</li>
     *   <li>Not mirrored: {@code cg_DepthBuffer}, which holds reversed values. The frame block carries this
     *       flag, so a shader reading it through {@code cg_LinearEyeDepth} gets eye distances either way.</li>
     *   <li>Nor a projection: the host's own matrices are already reversed. One built for the pass, rather
     *       than taken from the host, is built reversed too:
     *       <pre>{@code
     * if (CgGL.isDepthReversed()) proj.setPerspective(fovy, aspect, far, near, CgGL.isDepthZeroToOne());
     * else proj.setPerspective(fovy, aspect, near, far);
     * }</pre></li>
     * </ul>
     */
    public static void setDepthReversed(boolean reversed) {
        setDepthReversed(reversed, false);
    }

    /**
     * {@link #setDepthReversed(boolean)}, for a host whose clip-space depth runs {@code 0..1}
     * ({@code glClipControl(..., GL_ZERO_TO_ONE)}): what a projection built for the pass passes as JOML's
     * {@code zZeroToOne}.
     */
    public static void setDepthReversed(boolean reversed, boolean zeroToOne) {
        depthReversed = reversed;
        depthZeroToOne = reversed && zeroToOne;
    }

    public static boolean isDepthReversed() {
        return depthReversed;
    }

    /** Whether the reversed pass's clip-space depth runs {@code 0..1}; false outside one. */
    public static boolean isDepthZeroToOne() {
        return depthZeroToOne;
    }

    /** The same comparison made against a reversed depth buffer. */
    static int mirroredDepthFunc(int func) {
        switch (func) {
            case GL_LESS:    return GL_GREATER;
            case GL_LEQUAL:  return GL_GEQUAL;
            case GL_GREATER: return GL_LESS;
            case GL_GEQUAL:  return GL_LEQUAL;
            default:         return func;
        }
    }

    public static void glClearColor(float r, float g, float b, float a) {
        gl().glClearColor(r, g, b, a);
    }

    public static void glClearStencil(int s) {
        gl().glClearStencil(s);
    }

    public static void glStencilMask(int mask) {
        if (state().stencilMaskChanged(mask)) gl().glStencilMask(mask);
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        if (state().blendEquationChanged(modeRGB, modeAlpha)) gl().glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    /** GL 3.0 per-draw-buffer color mask. */
    public static void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) {
        if (state().colorMaskiChanged(buf, r, g, b, a)) gl().glColorMaski(buf, r, g, b, a);
    }

    public static void glFrontFace(int mode) {
        if (state().frontFaceChanged(mode)) gl().glFrontFace(mode);
    }

    public static void glPolygonOffset(float factor, float units) {
        // Mirrored like glDepthFunc: an offset that pushes away from the camera is a positive one only
        // while nearer is smaller.
        boolean mirror = depthReversed && !replaying && !state().restoring();
        float f = mirror ? -factor : factor, u = mirror ? -units : units;
        if (state().polygonOffsetChanged(f, u)) gl().glPolygonOffset(f, u);
    }

    public static void glPointSize(float size) {
        if (state().pointSizeChanged(size)) gl().glPointSize(size);
    }

    public static void glDrawBuffer(int mode) {
        gl().glDrawBuffer(mode);
    }

    public static void glReadBuffer(int mode) {
        gl().glReadBuffer(mode);
    }

    public static void glPixelStorei(int pname, int param) {
        gl().glPixelStorei(pname, param);
    }

    // =========================================================================
    // GL state — queries
    // =========================================================================

    public static int glGetInteger(int pname) {
        return gl().glGetInteger(pname);
    }

    /**
     * Reads a rectangle of the bound read framebuffer into {@code pixels}.
     *
     * <pre>{@code
     * ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
     * CgGL.glReadPixels(x, y, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, px);
     * }</pre>
     *
     * <p>Synchronous, so it stalls the pipeline: diagnostics only. The buffer must be direct and large
     * enough for {@code width * height} pixels in the given format.</p>
     */
    public static void glReadPixels(int x, int y, int width, int height,
                                    int format, int type, ByteBuffer pixels) {
        gl().glReadPixels(x, y, width, height, format, type, pixels);
    }

    /**
     * Reads into the bound {@code GL_PIXEL_PACK_BUFFER} at {@code packOffset}, and returns without
     * waiting — the asynchronous form. Fence it and map the buffer frames later.
     *
     * <pre>{@code
     * CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, pbo);
     * CgGL.glReadPixels(0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, 0L);
     * CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);   // or every later glReadPixels lands in it
     * }</pre>
     *
     * <p>{@code CgPixelReadback}, in core, does all of that and hands the pixels over when they land.</p>
     */
    public static void glReadPixels(int x, int y, int width, int height,
                                    int format, int type, long packOffset) {
        gl().glReadPixels(x, y, width, height, format, type, packOffset);
    }

    public static void glGetInteger(int pname, IntBuffer params) {
        gl().glGetInteger(pname, params);
    }

    public static boolean glGetBoolean(int pname) {
        return gl().glGetBoolean(pname);
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        gl().glGetBoolean(pname, params);
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        gl().glGetFloat(pname, params);
    }

    public static float glGetFloat(int pname) {
        return gl().glGetFloat(pname);
    }

    /** The context's {@code GL_VERSION}, {@code GL_VENDOR}, {@code GL_RENDERER} or {@code GL_SHADING_LANGUAGE_VERSION}. */
    public static String glGetString(int name) {
        return gl().glGetString(name);
    }

    /**
     * One indexed string: every extension a core context lists, one at a time.
     *
     * <pre>{@code
     * for (int i = 0, n = CgGL.glGetInteger(CgGL.GL_NUM_EXTENSIONS); i < n; i++) {
     *     String name = CgGL.glGetStringi(CgGL.GL_EXTENSIONS, i);
     * }
     * }</pre>
     */
    public static String glGetStringi(int name, int index) {
        return gl().glGetStringi(name, index);
    }

    /** One element of an indexed value: {@code glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1)} is the y count. */
    public static int glGetIntegeri(int target, int index) {
        return gl().glGetIntegeri(target, index);
    }

    // =========================================================================
    // Samplers
    // =========================================================================

    /** Binds a sampler object to a texture unit (ARB_sampler_objects / GL 3.3). */
    public static void glBindSampler(int unit, int sampler) {
        gl().glBindSampler(unit, sampler);
    }

    // =========================================================================
    // Buffer mapping
    // =========================================================================

    /**
     * Maps {@code length} bytes of the bound buffer from {@code offset}: answered at position 0 with its limit at
     * {@code length}, or {@code null} if mapping fails. {@code oldBuffer}, an earlier answer offered so its wrapper can
     * be reused, is cleared first.
     *
     * <pre>{@code
     * ByteBuffer mapped = CgGL.glMapBufferRange(GL_COPY_READ_BUFFER, 0, bytes, GL_MAP_READ_BIT, last);
     * last = mapped;
     * }</pre>
     */
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        // LWJGL 3 reuses the wrapper when its address plus its position is the new mapping's: one written through to its end
        // matched the range after it, and the next write went into the old range.
        if (oldBuffer != null) oldBuffer.clear();
        ByteBuffer mapped = gl().glMapBufferRange(target, offset, length, access, oldBuffer);
        if (mapped != null) mapped.clear();   // LWJGL 2 hands the old wrapper back as its caller left it
        return mapped;
    }

    public static boolean glUnmapBuffer(int target) {
        return gl().glUnmapBuffer(target);
    }

    public static void glFlushMappedBufferRange(int target, long offset, long length) {
        gl().glFlushMappedBufferRange(target, offset, length);
    }

    /**
     * Immutable storage for the bound buffer (GL 4.4 / {@code ARB_buffer_storage}) — what a persistently
     * mapped stream is built on. Gate on {@code CgCapabilities.isBufferStorageSupported()}.
     *
     * <pre>{@code
     * CgGL.glBufferStorage(GL_ARRAY_BUFFER, bytes, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
     * ByteBuffer mapped = CgGL.glMapBufferRange(GL_ARRAY_BUFFER, 0, bytes,
     *         GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT, null);   // kept for the buffer's life
     * }</pre>
     *
     * <p>Immutable means {@code glBufferData} on it afterwards is an error: growing takes a new buffer.</p>
     */
    public static void glBufferStorage(int target, long size, int flags) {
        gl().glBufferStorage(target, size, flags);
    }

    // =========================================================================
    // Sync objects (ARBSync / GL 3.2)
    // =========================================================================

    public static long glFenceSync(int condition, int flags) {
        return gl().glFenceSync(condition, flags);
    }

    public static int glClientWaitSync(long sync, int flags, long timeout) {
        return gl().glClientWaitSync(sync, flags, timeout);
    }

    public static void glDeleteSync(long sync) {
        gl().glDeleteSync(sync);
    }
    
    // =========================================================================
    // Debug
    // =========================================================================
    
    public static int glGetError()  {
        return gl().glGetError();
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
        return gl().glGenQuery();
    }

    public static void glBeginTimeElapsedQuery(int query) {
        gl().glBeginTimeElapsedQuery(query);
    }

    public static void glEndTimeElapsedQuery() {
        gl().glEndTimeElapsedQuery();
    }

    /** The GPU's clock once the commands before it finish, into {@code query}: {@link CgGLBackend#glQueryTimestamp}. */
    public static void glQueryTimestamp(int query) {
        gl().glQueryTimestamp(query);
    }

    public static boolean glIsQueryResultAvailable(int query) {
        return gl().glIsQueryResultAvailable(query);
    }

    public static long glGetQueryResultNanos(int query) {
        return gl().glGetQueryResultNanos(query);
    }

    public static void glDeleteQuery(int query) {
        gl().glDeleteQuery(query);
    }

    public static void glBindFramebuffer(int target, int fbo) {
        if (state().fboChanged(target, fbo)) gl().bindFramebuffer(target, fbo);
    }

    // =========================================================================
    // Context
    // =========================================================================

    /** @return {@code true} if an OpenGL context is current on this thread. */
    public static boolean isContextCurrent() {
        return backend.isContextCurrent();
    }

    // ── Host coexistence ──────────────────────────────────────────────────────

    /** @see CgGLBackend#importHostTexture */
    public static int importHostTexture(Object hostHandle) {
        return gl().importHostTexture(hostHandle);
    }

    // Brackets open around our work. 0 means the host has control, which is where every frame starts.
    private static int fromHostDepth;

    /**
     * The host hands CrystalGraphics its frame. Everything drawn until the matching {@link #toHost()} is ours,
     * and every place a host calls into us is one such bracket:
     *
     * <pre>{@code
     * CgGL.fromHost();       // a render event, a screen or the HUD hands us the frame
     * try {
     *     paint();
     * } finally {
     *     CgGL.toHost();     // and gets it back
     * }
     * }</pre>
     *
     * <p><b>Why the pair exists.</b> On OpenGL there is nothing to hand over: Minecraft and CrystalGraphics
     * share one context, and what changes hands is GL state, which scopes, invalidations and each host's own
     * repair already handle. Both calls do nothing there. A host that owns a Vulkan device is different, and
     * Minecraft 26.2 is the first. It records its frame as a series of render passes of its own, and keeps
     * every image in the layout its own tracking says it is in. Our draws go into command buffers of our own,
     * taken from the host's pool and run in its submit after everything it recorded before the section, and
     * its images must be where it left them when it resumes. These two calls mark those moments:</p>
     * <ul>
     *   <li>{@code fromHost}: a device-backed backend starts recording into its own command buffers, against
     *       the host's current target.</li>
     *   <li>{@link #toHost()}: it ends the render pass it opened, leaves the host's images as the host expects,
     *       and hands its command buffers to the host's submit. On the tracked backend today, this is where our
     *       open pass ends.</li>
     * </ul>
     *
     * <p>What is easy to get wrong:</p>
     * <ul>
     *   <li>Brackets nest, and only the outermost pair reaches the backend. {@code CgGraphicsLifecycle}'s
     *       entries bracket themselves, and a host's bracket may wrap one.</li>
     *   <li>A host repairing its own state, such as Blaze3D's {@code GlStateManager} cache, does it after
     *       {@link #toHost()}. That work is the host's, not ours.</li>
     *   <li>Never inside a {@link CgGlRecording}: its backend refuses both. Record, and replay, inside a
     *       bracket.</li>
     *   <li>Inside a section only our code touches GL, so the state shadow is trusted across scopes there;
     *       the outermost {@code fromHost} forgets it, since the host had the context. Host code run inside
     *       one goes through {@link CgGlState#hostForeign}, which forgets it again.</li>
     *   <li>Render thread only.</li>
     *   <li>The first one on a client installs the platform's backend, so it is where a failure to build one
     *       surfaces. @see CgPlatform#register</li>
     * </ul>
     *
     * @see CgGLBackend#fromHost
     */
    public static void fromHost() {
        if (fromHostDepth == 0) {
            installIfAbsent();
            gl().fromHost();
            CgGlState.invalidateAllIfPresent();
        }
        fromHostDepth++;
    }

    /** Whether a host section is open: between an outermost {@link #fromHost()} and its {@link #toHost()}. */
    public static boolean inHostSection() {
        return fromHostDepth > 0;
    }

    /**
     * CrystalGraphics hands the frame back to the host, closing the bracket {@link #fromHost()} opened. On a
     * device-backed backend the render pass we opened ends here and the host's images are left as it expects;
     * on OpenGL nothing happens.
     *
     * @throws IllegalStateException when no bracket is open
     * @see CgGLBackend#toHost
     */
    public static void toHost() {
        if (fromHostDepth == 0) throw new IllegalStateException("toHost with no fromHost open");
        if (--fromHostDepth == 0) gl().toHost();
    }

    /** @see CgGLBackend#ownedByCurrentThread */
    public static boolean ownedByCurrentThread() {
        return backend.ownedByCurrentThread();
    }

    /** Whether a {@link CgGlRecording} is capturing on this backend: calls are taped for replay, not drawn. */
    public static boolean isRecording() {
        return backend instanceof CgGlRecordingBackend;
    }

    /** The installed backend; {@link CgGlRecording} swaps it for the length of a recording. */
    static CgGLBackend backend() {
        return backend;
    }
}
