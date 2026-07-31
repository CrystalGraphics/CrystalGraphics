package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * One preview's render target: a multisampled framebuffer to draw into, and a plain one to read from.
 *
 * <h3>Why two, and why that is not avoidable</h3>
 * <p>A multisampled attachment cannot be read by an ordinary {@code sampler2D} — the UI draws thumbnails
 * with the same quad shader as everything else, so it needs a normal texture. The standard resolution is
 * to render into the multisampled buffer and <b>blit</b> it into a single-sampled one; that blit
 * <em>is</em> the multisample resolve, performed by the driver.</p>
 *
 * <h3>Why MSAA rather than supersampling</h3>
 * <p>Both fix the same thing — a mesh silhouette rendered at thumbnail size has a hard, binary edge that
 * stair-steps. Supersampling gets there by rendering everything bigger, so it multiplies <em>shading</em>
 * cost as well as memory. MSAA multiplies coverage only: the fragment shader still runs once per pixel,
 * which for a node graph running dozens of live previews is the difference that matters.</p>
 */
public final class CgPreviewTarget {

    private final CgFrameBuffer multisampled;
    private final CgFrameBuffer resolved;
    private boolean deleted;

    /**
     * @param size    edge length in pixels, square
     * @param samples requested sample count; 1 skips the multisampled buffer entirely
     */
    public CgPreviewTarget(String name, int size, int samples) {
        // Clamped against the live context. GL would clamp silently anyway, but asking here means a
        // machine offering 2x is not quietly assumed to be giving 4x.
        int wanted = Math.max(1, Math.min(samples, CgGL.maxSamples()));

        CgFrameBufferFormat resolveFormat = CgFrameBufferFormat.builder("cg_node_preview")
                .color(0, CgTextureType.RGBA8)
                .build();
        this.resolved = CgFrameBuffer.createOwned(name, size, size, resolveFormat);

        if (wanted <= 1) {
            // No multisampling available: draw straight into the readable target. Correct, just not
            // antialiased — the same degradation the EXT framebuffer path makes.
            this.multisampled = null;
            return;
        }

        // Renderbuffers, not textures: nothing samples this one, and a renderbuffer is the cheaper
        // attachment for a write-only buffer. Depth must carry the same sample count or the framebuffer
        // is incomplete.
        CgFrameBufferFormat msFormat = CgFrameBufferFormat.builder("cg_node_preview_ms")
                .colorRenderbuffer(0, CgTextureType.RGBA8)
                .depthRenderbuffer(CgTextureType.DEPTH24_STENCIL8)
                .samples(wanted)
                .build();
        this.multisampled = CgFrameBuffer.createOwned(name + "_ms", size, size, msFormat);
    }

    /** The framebuffer to render into. */
    public CgFrameBuffer drawTarget() {
        return multisampled != null ? multisampled : resolved;
    }

    /**
     * Resolves the multisampled buffer into the readable one. No-op when not multisampled.
     *
     * <p>{@code GL_NEAREST} because source and destination are the same size — a multisample resolve
     * blit must not be asked to filter, and GL rejects {@code GL_LINEAR} for one outright.</p>
     */
    public void resolve() {
        if (multisampled == null) return;
        resolved.blitFrom(multisampled, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);
    }

    /** The readable colour texture — what the UI draws. */
    public CgTexture texture() {
        return resolved.getColorTexture(0);
    }

    public void delete() {
        if (deleted) return;
        if (multisampled != null) multisampled.delete();
        resolved.delete();
        deleted = true;
    }
}
