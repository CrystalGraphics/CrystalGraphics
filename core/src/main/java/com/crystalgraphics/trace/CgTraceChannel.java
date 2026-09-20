package com.crystalgraphics.trace;

/**
 * One bit of the trace enable mask — the unit of opting in to instrumentation.
 *
 * <p>A channel is declared once, as a constant, and named after who owns the instrumentation rather
 * than after what it measures. Everything on a channel nobody enabled costs a single bit test.</p>
 *
 * <pre>{@code
 * public final class MyRenderer {
 *     private static final CgTraceChannel TRACE = CgTrace.channel("mymod.render");
 *
 *     void draw() {
 *         try (CgTrace.Zone z = CgTrace.zone(TRACE, "draw")) {
 *             ...
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Names are hierarchical; the mask is flat</h3>
 *
 * <p>{@code crystalgraphics.text} and {@code crystalgraphics.gl} own separate bits, and
 * {@link CgTrace#enable(String) CgTrace.enable("crystalgraphics")} sets every bit whose name carries
 * that prefix. So the coarse gesture — <em>CrystalGraphics | CrystalGUI</em> — is one call, while
 * switching off glyph shaping alone is still possible.</p>
 *
 * <h3>A bit index is not stable across runs</h3>
 *
 * <p>Channels register from static initialisers, so bit assignment follows class-load order, which
 * follows whatever the host happened to touch first. <b>Never persist a mask as a number.</b> Saved
 * settings, command arguments and trace metadata all name channels by {@link #name()}; that is also
 * why the UI binds a {@code Set<String>} rather than a {@code long}.</p>
 *
 * @see CgTrace#channel(String)
 */
public final class CgTraceChannel {

    /** Dotted, lower-case, owner first: {@code crystalgraphics.gl}, {@code mymod.worldgen}. */
    private final String name;

    /** {@code 1L << index}, precomputed because it is read on the hot path and the index is not. */
    private final long bit;

    private final int index;

    CgTraceChannel(String name, int index) {
        this.name = name;
        this.index = index;
        this.bit = 1L << index;
    }

    public String name() {
        return name;
    }

    /** This channel's position in the mask. Meaningful only within one process — see the class note. */
    public int index() {
        return index;
    }

    long bit() {
        return bit;
    }

    /** Whether anything recorded on this channel is currently being kept. */
    public boolean isEnabled() {
        return CgTrace.isEnabled(this);
    }

    @Override
    public String toString() {
        return name;
    }
}
