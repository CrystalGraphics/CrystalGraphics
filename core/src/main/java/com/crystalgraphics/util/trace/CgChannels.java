package com.crystalgraphics.util.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

/**
 * CrystalGraphics' trace channels — five, so one subsystem can be recorded without the rest.
 *
 * <pre>{@code
 * try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.TEXT, "shape.run")) {
 *     CgTrace.add(CgChannels.TEXT, "glyph.atlasHit", 1);
 * }
 *
 * CgTrace.enable("crystalgraphics");        // all five
 * CgTrace.enable("crystalgraphics.gl");     // one
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
     * The 3D world passes and their phases — a handful of zones a frame, so cheap enough to leave on
     * while hunting a frame on a Minecraft host.
     */
    public static final CgTraceChannel WORLD = CgTrace.channel("crystalgraphics.world");

    /** Work on background workers. */
    public static final CgTraceChannel ASYNC = CgTrace.channel("crystalgraphics.async");

    /** Everything else, and anything a harness or a test records. */
    public static final CgTraceChannel MISC = CgTrace.channel("crystalgraphics.misc");
}
