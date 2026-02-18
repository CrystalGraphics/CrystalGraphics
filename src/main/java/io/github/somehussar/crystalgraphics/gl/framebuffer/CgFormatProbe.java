package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.CgTextureFormatSpec;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Probes whether a given {@link CgTextureFormatSpec} is usable as a
 * framebuffer color attachment on the current OpenGL context.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Check the cache — if the {@code internalFormat} has been probed
 *       before, return the cached result immediately.</li>
 *   <li>Select the best available FBO backend via
 *       {@link CgCapabilities#preferredFboBackend()}.</li>
 *   <li>Create a temporary FBO and a small (4×4) 2D texture using the
 *       requested {@code internalFormat}, {@code pixelFormat}, and
 *       {@code pixelType}.</li>
 *   <li>Attach the texture to {@code GL_COLOR_ATTACHMENT0} and call
 *       {@code glCheckFramebufferStatus}.</li>
 *   <li>If the status is {@code GL_FRAMEBUFFER_COMPLETE}, the format is
 *       supported; otherwise it is not.</li>
 *   <li>Delete the temporary FBO and texture in a {@code finally} block
 *       to guarantee no resource leaks.</li>
 *   <li>Cache and return the result.</li>
 * </ol>
 *
 * <h3>Caching</h3>
 * <p>Results are cached per {@code internalFormat} integer in a
 * {@link ConcurrentHashMap}.  Both positive and negative results are
 * cached because the GL context is single-lifetime in Minecraft — a
 * format that fails will not start working later.  The cache can be
 * cleared for testing via {@link #clearCache()}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The cache is thread-safe ({@code ConcurrentHashMap}).  However,
 * the actual GL probe must run on the render thread with a current
 * OpenGL context (Minecraft is single-context).</p>
 *
 * @see CgTextureFormatSpec
 * @see CgCapabilities
 */
public final class CgFormatProbe {

    // ── GL constants ───────────────────────────────────────────────────

    /** {@code GL_FRAMEBUFFER} target (Core / ARB). */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** {@code GL_FRAMEBUFFER_EXT} target. */
    private static final int GL_FRAMEBUFFER_EXT = 0x8D40;

    /** {@code GL_COLOR_ATTACHMENT0} attachment point (Core / ARB). */
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

    /** {@code GL_COLOR_ATTACHMENT0_EXT} attachment point. */
    private static final int GL_COLOR_ATTACHMENT0_EXT = 0x8CE0;

    /** {@code GL_TEXTURE_2D} target. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** {@code GL_TEXTURE_MIN_FILTER} parameter. */
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;

    /** {@code GL_TEXTURE_MAG_FILTER} parameter. */
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;

    /** {@code GL_NEAREST} filter value (minimal filtering for probe). */
    private static final int GL_NEAREST = 0x2600;

    /** {@code GL_FRAMEBUFFER_COMPLETE} status (Core / ARB). */
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    /** {@code GL_FRAMEBUFFER_COMPLETE_EXT} status. */
    private static final int GL_FRAMEBUFFER_COMPLETE_EXT = 0x8CD5;

    /** Probe texture width. Small to minimise GPU overhead. */
    private static final int PROBE_WIDTH = 4;

    /** Probe texture height. Small to minimise GPU overhead. */
    private static final int PROBE_HEIGHT = 4;

    // ── Cache ──────────────────────────────────────────────────────────

    /**
     * Cache of probe results keyed by {@code internalFormat} integer.
     * Both positive ({@code true}) and negative ({@code false}) results
     * are cached for the lifetime of the GL context.
     */
    private static final Map<Integer, Boolean> cache = new ConcurrentHashMap<Integer, Boolean>();

    // ── Private constructor (utility class) ────────────────────────────

    private CgFormatProbe() {
        throw new AssertionError("No instances");
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Tests whether the given texture format can be used as a framebuffer
     * color attachment on the current OpenGL context.
     *
     * <p>The result is cached per {@code internalFormat} — subsequent calls
     * with the same internal format return immediately without any GL work.</p>
     *
     * @param format the texture format specification to probe
     * @param caps   the detected capabilities (used to select FBO backend)
     * @return {@code true} if the format produces a complete framebuffer,
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if no FBO backend is available
     *         ({@link CgCapabilities.Backend#NONE})
     * @throws NullPointerException if {@code format} or {@code caps} is null
     */
    public static boolean isSupported(CgTextureFormatSpec format, CgCapabilities caps) {
        if (format == null) {
            throw new NullPointerException("format must not be null");
        }
        if (caps == null) {
            throw new NullPointerException("caps must not be null");
        }

        int internalFormat = format.getInternalFormat();

        Boolean cached = cache.get(internalFormat);
        if (cached != null) {
            return cached.booleanValue();
        }

        CgCapabilities.Backend backend = caps.preferredFboBackend();
        if (backend == CgCapabilities.Backend.NONE) {
            throw new UnsupportedOperationException(
                    "No FBO backend available for format probe");
        }

        boolean supported;
        switch (backend) {
            case CORE_GL30:
                supported = probeCore(format);
                break;
            case ARB_FBO:
                supported = probeArb(format);
                break;
            case EXT_FBO:
                supported = probeExt(format);
                break;
            default:
                throw new UnsupportedOperationException(
                        "No FBO backend available for format probe");
        }

        cache.put(internalFormat, Boolean.valueOf(supported));
        return supported;
    }

    /**
     * Clears the probe result cache.
     *
     * <p>Primarily intended for testing.  In production, the cache lives
     * for the entire GL context lifetime.</p>
     */
    public static void clearCache() {
        cache.clear();
    }

    // ── Backend-specific probes ────────────────────────────────────────

    /**
     * Probes format support using Core GL30 entry points.
     *
     * @param format the format to probe
     * @return {@code true} if framebuffer is complete with this format
     */
    private static boolean probeCore(CgTextureFormatSpec format) {
        int fbo = 0;
        int tex = 0;
        try {
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    format.getInternalFormat(),
                    PROBE_WIDTH, PROBE_HEIGHT, 0,
                    format.getPixelFormat(),
                    format.getPixelType(),
                    (ByteBuffer) null);

            GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, tex, 0);

            int status = GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER);
            return status == GL_FRAMEBUFFER_COMPLETE;
        } finally {
            GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (tex != 0) {
                GL11.glDeleteTextures(tex);
            }
            if (fbo != 0) {
                GL30.glDeleteFramebuffers(fbo);
            }
        }
    }

    /**
     * Probes format support using ARB_framebuffer_object entry points.
     *
     * @param format the format to probe
     * @return {@code true} if framebuffer is complete with this format
     */
    private static boolean probeArb(CgTextureFormatSpec format) {
        int fbo = 0;
        int tex = 0;
        try {
            fbo = ARBFramebufferObject.glGenFramebuffers();
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    format.getInternalFormat(),
                    PROBE_WIDTH, PROBE_HEIGHT, 0,
                    format.getPixelFormat(),
                    format.getPixelType(),
                    (ByteBuffer) null);

            ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, tex, 0);

            int status = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
            return status == GL_FRAMEBUFFER_COMPLETE;
        } finally {
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (tex != 0) {
                GL11.glDeleteTextures(tex);
            }
            if (fbo != 0) {
                ARBFramebufferObject.glDeleteFramebuffers(fbo);
            }
        }
    }

    /**
     * Probes format support using EXT_framebuffer_object entry points.
     *
     * <p>All methods use the {@code *EXT} suffix as required by the EXT
     * extension.</p>
     *
     * @param format the format to probe
     * @return {@code true} if framebuffer is complete with this format
     */
    private static boolean probeExt(CgTextureFormatSpec format) {
        int fbo = 0;
        int tex = 0;
        try {
            fbo = EXTFramebufferObject.glGenFramebuffersEXT();
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);

            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    format.getInternalFormat(),
                    PROBE_WIDTH, PROBE_HEIGHT, 0,
                    format.getPixelFormat(),
                    format.getPixelType(),
                    (ByteBuffer) null);

            EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                    GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, tex, 0);

            int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
            return status == GL_FRAMEBUFFER_COMPLETE_EXT;
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
            if (tex != 0) {
                GL11.glDeleteTextures(tex);
            }
            if (fbo != 0) {
                EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
            }
        }
    }
}
