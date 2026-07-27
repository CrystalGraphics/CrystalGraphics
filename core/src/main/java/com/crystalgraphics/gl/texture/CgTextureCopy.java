package com.crystalgraphics.gl.texture;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GPU-side texel copying between texture images — "move this data from texture A to texture B
 * without it ever touching the CPU", using the best path the current context supports.
 *
 * <h3>Why this exists</h3>
 * <p>Reallocating a texture (growing a 2D-array's layer count, resizing a render target, …)
 * destroys its contents, so any caller that must preserve them has to get the old texels into
 * the new allocation somehow. The naive way — keep a CPU-side mirror and re-upload every
 * texel — costs a full CPU&rarr;GPU transfer of the <em>entire</em> texture on every resize,
 * for data the GPU already had.
 *
 * <p>That cost is not hypothetical. Measured on {@code CgTexture2DArray}'s glyph-atlas growth:
 * the {@code glTexImage3D} reallocation itself took <strong>0.01 ms</strong>, while replaying
 * the existing layers from their CPU mirrors took <strong>3.8 ms &rarr; 65.7 ms</strong>,
 * scaling linearly with atlas size (~0.87 ms/MB, ~1.15 GB/s) and growing without bound as the
 * atlas fills — 8 growths re-uploaded 204 MB and cost ~190 ms of stall during one warmup. The
 * reallocation is essentially free; the re-upload is the entire problem, which is precisely
 * what a GPU-side copy eliminates.
 *
 * <h3>Strategy waterfall</h3>
 * <p>Follows the same Core &gt; ARB &gt; fallback convention as {@code CgFrameBuffer} /
 * {@code CgVertexArray} / {@code CgStreamBuffer}:</p>
 * <ol>
 *   <li><b>{@code glCopyImageSubData}</b> (core GL 4.3 / {@code ARB_copy_image}) — one call
 *       copies an arbitrary sub-volume, including every layer of an array texture at once. No
 *       framebuffer, no state juggling, no per-layer loop.</li>
 *   <li><b>Framebuffer blit</b> (core GL 3.0 baseline) — attach source and destination layers
 *       to a scratch read/draw framebuffer pair and {@code glBlitFramebuffer} each layer.
 *       Requires {@code glFramebufferTextureLayer} to address an individual array layer.</li>
 *   <li><b>Caller's own fallback</b> — every entry point <em>returns {@code false}</em> rather
 *       than throwing when no GPU path is available, so the caller can fall back to whatever it
 *       did before (typically a CPU-mirror replay). Callers must handle {@code false}; a copy
 *       silently not happening would corrupt the destination.</li>
 * </ol>
 *
 * <h3>Scope</h3>
 * <p>Deliberately not specific to glyph atlases or to 2D-array textures — it takes raw GL
 * texture names and targets, so it applies equally to any future resize/realloc path. Stateless
 * apart from one lazily created, reused scratch framebuffer pair used only by strategy 2.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe; GL-thread only, like every other class in this package.</p>
 */
public final class CgTextureCopy {

    private static final Logger LOGGER = Logger.getLogger(CgTextureCopy.class.getName());

    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_COLOR_BUFFER_BIT = 0x00004000;
    private static final int GL_NEAREST = 0x2600;

    /** Scratch read/draw framebuffers for strategy 2, created on first use and reused. */
    private static int scratchReadFbo = 0;
    private static int scratchDrawFbo = 0;

    private CgTextureCopy() {}

    /**
     * Returns whether any GPU-side copy path is available at all. When {@code false}, every
     * {@code copy*} call will return {@code false} and the caller must use its own fallback.
     */
    public static boolean isSupported() {
        CgCapabilities caps = CgCapabilities.detect();
        return caps.isCopyImageSubDataSupported() || caps.isFramebufferTextureLayerSupported();
    }

    /**
     * Copies {@code layerCount} whole layers from one 2D-array texture into another, GPU-side.
     *
     * <p>Layer {@code i} of {@code srcTextureId} lands at layer {@code i} of
     * {@code dstTextureId}; both must share the same texel format and be at least
     * {@code width x height}. The destination's remaining layers are untouched.</p>
     *
     * @return {@code true} if the copy was performed on the GPU; {@code false} if no supported
     *         path exists, in which case <strong>nothing was copied</strong> and the caller must
     *         fall back (see class javadoc)
     */
    public static boolean copyArrayLayers(int srcTextureId, int dstTextureId,
                                           int width, int height, int layerCount) {
        if (layerCount <= 0) return true; // nothing to do — trivially "copied"
        if (srcTextureId <= 0 || dstTextureId <= 0) return false;

        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isCopyImageSubDataSupported() && !copyImageUnavailable) {
            try {
                // One call for the whole stack: an array texture's layers are the Z dimension,
                // so srcDepth == layerCount copies all of them at once.
                CgGL.glCopyImageSubData(
                        srcTextureId, CgGL.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
                        dstTextureId, CgGL.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
                        width, height, layerCount);
                return true;
            } catch (UnsupportedOperationException e) {
                // The CONTEXT supports glCopyImageSubData but this loader backend has not wired
                // it up (see CgGLBackend's default). That is a static property of the build, not
                // a transient failure, so latch it off rather than re-throwing on every growth.
                copyImageUnavailable = true;
                LOGGER.log(Level.INFO, "Backend does not implement glCopyImageSubData; using blit copy from here on", e);
            } catch (RuntimeException e) {
                // A driver that advertises the capability but rejects the call is a real (if
                // rare) hazard; fall through to the blit path rather than failing the copy.
                LOGGER.log(Level.WARNING, "glCopyImageSubData failed; falling back to blit copy", e);
            }
        }

        if (caps.isFramebufferTextureLayerSupported()) {
            return blitArrayLayers(srcTextureId, dstTextureId, width, height, layerCount);
        }
        return false;
    }

    /**
     * Latched once a backend reveals it has not implemented {@code glCopyImageSubData}, so the
     * fast path is not re-attempted (and re-logged) on every subsequent copy.
     */
    private static boolean copyImageUnavailable = false;

    /**
     * Strategy 2 — per-layer {@code glBlitFramebuffer} through a scratch read/draw framebuffer
     * pair. Restores the previously bound read/draw framebuffers on exit so this is transparent
     * to whatever the caller had bound.
     */
    private static boolean blitArrayLayers(int srcTextureId, int dstTextureId,
                                            int width, int height, int layerCount) {
        ensureScratchFramebuffers();
        if (scratchReadFbo == 0 || scratchDrawFbo == 0) return false;

        int prevRead = CgGL.glGetInteger(CgGL.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            for (int layer = 0; layer < layerCount; layer++) {
                CgGL.glBindFramebuffer(GL_READ_FRAMEBUFFER, scratchReadFbo);
                CgGL.glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, srcTextureId, 0, layer);

                CgGL.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, scratchDrawFbo);
                CgGL.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, dstTextureId, 0, layer);

                CgGL.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Framebuffer blit copy failed; caller must use its own fallback", e);
            return false;
        } finally {
            // Detach so the scratch FBOs never keep a deleted texture alive, then restore.
            try {
                CgGL.glBindFramebuffer(GL_READ_FRAMEBUFFER, scratchReadFbo);
                CgGL.glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
                CgGL.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, scratchDrawFbo);
                CgGL.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
            } catch (RuntimeException ignored) {
                // Detach is best-effort cleanup; never mask the real outcome above.
            }
            CgGL.glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
            CgGL.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    private static void ensureScratchFramebuffers() {
        if (scratchReadFbo == 0) scratchReadFbo = CgGL.glGenFramebuffers();
        if (scratchDrawFbo == 0) scratchDrawFbo = CgGL.glGenFramebuffers();
    }

    /**
     * Releases the scratch framebuffers. Called on GL context teardown; safe to call when they
     * were never created, and safe to use this class again afterward (they are recreated lazily).
     */
    public static void dispose() {
        if (scratchReadFbo != 0) {
            CgGL.glDeleteFramebuffers(scratchReadFbo);
            scratchReadFbo = 0;
        }
        if (scratchDrawFbo != 0) {
            CgGL.glDeleteFramebuffers(scratchDrawFbo);
            scratchDrawFbo = 0;
        }
    }
}
