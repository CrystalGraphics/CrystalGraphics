package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;

import javax.annotation.Nullable;

/**
 * One preview's render target: a multisampled texture to draw into, and a plain one to read from. Both are requested
 * graph textures, made when the frame that first draws into them executes and kept until {@link #delete()}.
 *
 * <pre>{@code
 * CgRasterPass pass = target.begin(recording, camera, state);   // cleared, depth too
 * // ... chunks ...
 * target.end(recording, pass);                                   // ended and resolved
 * ctx.drawLayer(target.texture(), x, y, w, h);
 * }</pre>
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

    private static final CgFrameBufferFormat RESOLVED = CgFrameBufferFormat.builder("cg_node_preview")
            .color(0, CgTextureType.RGBA8)
            .build();

    private final int size;
    @Nullable
    private final CgGraphTexture multisampled;
    private final CgGraphTexture resolved;
    private boolean deleted;

    /**
     * @param size    edge length in pixels, square
     * @param samples requested sample count; 1 skips the multisampled texture entirely
     */
    public CgPreviewTarget(String name, int size, int samples) {
        this.size = size;
        // Clamped against the context, so a machine offering 2x is not quietly assumed to be giving 4x.
        int wanted = Math.max(1, Math.min(samples, CgCapabilities.detect().getMaxSamples()));
        this.resolved = CgGraphTexture.requested(name, new CgTextureDesc(size, size, RESOLVED));
        if (wanted <= 1) {
            // Drawn straight into the readable target: correct, just not antialiased.
            this.multisampled = null;
            return;
        }
        // Renderbuffers: nothing samples this one. Depth carries the same sample count or the framebuffer is
        // incomplete. WHAT WAS ASKED, not the hardware's most: up to 32x on a desktop card, resolved per preview.
        CgFrameBufferFormat format = CgFrameBufferFormat.builder("cg_node_preview_ms")
                .colorRenderbuffer(0, CgTextureType.RGBA8)
                .depthRenderbuffer(CgTextureType.DEPTH24_STENCIL8)
                .samples(wanted)
                .build();
        this.multisampled = CgGraphTexture.requested(name + "_ms", new CgTextureDesc(size, size, format));
    }

    /**
     * Opens a raster pass into this target, cleared to transparent (and its depth to 1): any opaque clear would show
     * as a square behind the node's rounded preview region.
     */
    public CgRasterPass begin(CgRecording recording, CgPassConstants camera, CgRenderState state) {
        CgGraphTexture into = multisampled != null ? multisampled : resolved;
        return recording.raster(into, CgLoad.clear(0f, 0f, 0f, 0f).andDepth(1.0), camera, state, CgOrder.LOOKBACK);
    }

    /** Ends {@code pass} and records the multisample resolve into {@link #texture()}. */
    public void end(CgRecording recording, CgRasterPass pass) {
        pass.end();
        // NEAREST: same size both sides, and GL refuses a filtered multisample resolve outright.
        if (multisampled != null) recording.copy(multisampled, 0, 0, size, size, resolved, 0, 0, size, size, false);
    }

    /** The readable colour texture — what the UI draws. */
    public CgGraphTexture texture() {
        return resolved;
    }

    /** Frees both textures' storage now. Render thread, at context teardown. */
    public void delete() {
        if (deleted) return;
        deleted = true;
        deleteStorage(resolved);
        if (multisampled != null) deleteStorage(multisampled);
    }

    private static void deleteStorage(CgGraphTexture texture) {
        CgFrameBuffer storage = texture.framebuffer();
        if (storage != null) storage.delete();
    }
}
