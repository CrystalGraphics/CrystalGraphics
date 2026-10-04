package com.crystalgraphics.util.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

/**
 * CrystalGraphics' trace channels — six, so one subsystem can be recorded without the rest, and two
 * detail channels for the steps inside a per-call operation.
 *
 * <pre>{@code
 * try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "shape.run")) {
 *     CgTrace.add(CgChannels.TEXT, "glyph.atlasHit", 1);
 * }
 *
 * CgTrace.enable("crystalgraphics");            // all six, and neither detail channel
 * CgTrace.enable("crystalgraphics.gl");         // one
 * CgTrace.enable("crystalgraphics.gl.detail");  // the steps inside each bind, flush and upload
 * }</pre>
 *
 * <p>TEXT and GL are dense — thousands of zones a frame on a busy screen — so a viewer leaves them off
 * until asked.</p>
 */
public final class CgChannels {

    private CgChannels() {
    }

    /** Shaping, line breaking, fonts, glyph generation and placement, the text renderer's draw. */
    public static final CgTraceChannel TEXT = CgTrace.channel("crystalgraphics.text");

    /** Material binds, batches, stream buffers, the quad and curve renderers, culling. */
    public static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");

    /**
     * The steps inside every material bind, quad and curve flush and stream-buffer upload — about 9,000 zones
     * a frame on the CrystalGUI desktop, so only its full name switches it on.
     */
    public static final CgTraceChannel GL_DETAIL = CgTrace.detailChannel("crystalgraphics.gl.detail");

    /**
     * A timed raster pass's GPU time by material, as counters {@code gpu:<zone>/<material>}: a timestamp wherever the
     * material changes, so only its full name switches it on. Needs the {@code gpu} channel too.
     */
    public static final CgTraceChannel GPU_GROUPS = CgTrace.detailChannel("crystalgraphics.gpu.groups");

    /** The steps inside every text draw: placement, flattening, sorting, the quad loop. Full name only. */
    public static final CgTraceChannel TEXT_DETAIL = CgTrace.detailChannel("crystalgraphics.text.detail");

    /**
     * The 3D world passes and their phases — a handful of zones a frame, so cheap enough to leave on
     * while hunting a frame on a Minecraft host.
     */
    public static final CgTraceChannel WORLD = CgTrace.channel("crystalgraphics.world");

    /**
     * The VFX engine: a zone per phase (update, tick, submit, each effect's submit), its per-particle loops as
     * per-frame time and count counters ({@code com.crystalgraphics.vfx.CgVfxTrace}), and a marker per blast.
     */
    public static final CgTraceChannel VFX = CgTrace.channel("crystalgraphics.vfx");

    /** Work on background workers. */
    public static final CgTraceChannel ASYNC = CgTrace.channel("crystalgraphics.async");

    /**
     * The shader graph's previews and emitters: node thumbnails, the main preview, and the GLSL a graph
     * emits. A handful of zones a frame per open graph.
     */
    public static final CgTraceChannel SHADERGRAPH = CgTrace.channel("crystalgraphics.shadergraph");

    /** Everything else, and anything a harness or a test records. */
    public static final CgTraceChannel MISC = CgTrace.channel("crystalgraphics.misc");
}
