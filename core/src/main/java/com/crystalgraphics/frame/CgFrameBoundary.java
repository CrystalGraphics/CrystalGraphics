package com.crystalgraphics.frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The frame boundary: the host's frame end, which is the top of the next frame, on every screen (a world, a GUI, the
 * title, the editor). It runs {@link Phase#DRAIN} (the asset loop's results, the editor's edits) then
 * {@link Phase#PUBLISH} (definitions swapped, generations bumped), so no firing of a frame ever sees a definition
 * another firing of it does not: 1.7.10's anaglyph fires each stage twice, all four against one generation.
 *
 * <pre>{@code
 * CgFrameBoundary.add(CgFrameBoundary.Phase.DRAIN, "mymod:editor-edits", editor::applyQueued);
 * CgFrameBoundary.run();     // CgGraphicsLifecycle.tickFrame, render thread; nothing else calls it
 * }</pre>
 *
 * <ul>
 *   <li>The process's {@link CgVfxAssets} drains first in {@code DRAIN} and publishes first in {@code PUBLISH}; other
 *       functions follow by name, never by registration order.</li>
 *   <li>A function that throws is disabled and logged once; the rest run on.</li>
 *   <li>Each phase is a trace zone, {@code frame.drain} and {@code frame.publish}.</li>
 *   <li>A dedicated server has no host frame: its server tick runs the boundary ({@link #installServerTick}). A client's
 *       integrated server never does, once a host frame has run one.</li>
 * </ul>
 */
public final class CgFrameBoundary {

    private static final Logger LOGGER = LogManager.getLogger("CgWorld");

    /** The boundary's steps, in order. */
    public enum Phase {
        /** The asset loop and the editor's edits drained; prepare work within the frame's budgets. */
        DRAIN,
        /** Definitions swapped, generations bumped. */
        PUBLISH;

        final int zone = CgTrace.name("frame." + name().toLowerCase(Locale.ROOT));
    }

    /** One function of a phase; {@link #close()} removes it. */
    public static final class Function implements AutoCloseable {
        final Phase phase;
        final String name;
        final Runnable body;
        boolean disabled;

        Function(Phase phase, String name, Runnable body) {
            this.phase = phase;
            this.name = name;
            this.body = body;
        }

        public String name() {
            return name;
        }

        @Override
        public void close() {
            remove(this);
        }
    }

    private static final List<Function> ALL = new ArrayList<>();
    private static volatile Function[][] plan = new Function[Phase.values().length][0];

    private CgFrameBoundary() {
    }

    /** Adds {@code body} to {@code phase}, named {@code mod:function}, unique. Any thread; from the next boundary. */
    public static synchronized Function add(Phase phase, String name, Runnable body) {
        if (name == null || name.indexOf(':') < 1) throw new IllegalArgumentException("a boundary function is named mod:function, not '" + name + "'");
        for (Function f : ALL) if (f.name.equals(name)) throw new IllegalArgumentException("boundary function " + name + " exists");
        Function f = new Function(phase, name, body);
        ALL.add(f);
        rebuild();
        return f;
    }

    private static synchronized void remove(Function f) {
        if (ALL.remove(f)) rebuild();
    }

    private static void rebuild() {
        Function[][] next = new Function[Phase.values().length][];
        for (Phase p : Phase.values()) {
            List<Function> in = new ArrayList<>();
            for (Function f : ALL) if (f.phase == p) in.add(f);
            in.sort(Comparator.comparing(Function::name));
            next[p.ordinal()] = in.toArray(new Function[0]);
        }
        plan = next;
    }

    /** Whether a host frame ever ran the boundary: then a server tick never does (an integrated server's thread). */
    private static volatile boolean framed;
    private static boolean serverTickInstalled;

    /**
     * Runs the boundary from the server's tick while no host frame has: a dedicated server's. Idempotent; from
     * {@code CgNetwork.install}, at init on both sides.
     */
    public static synchronized void installServerTick() {
        if (serverTickInstalled) return;
        serverTickInstalled = true;
        CgNetwork.onServerTick(CgFrameBoundary::serverTick);
    }

    /** The server tick's boundary: nothing once a host frame has run one. */
    static void serverTick() {
        if (!framed) phases();
    }

    /** For tests: forget that a host frame ran. */
    static void unframed() {
        framed = false;
    }

    /** Runs the boundary, from the host's frame end: the assets' drain, the drain functions, then the publishes. */
    public static void run() {
        framed = true;
        phases();
    }

    private static void phases() {
        Function[][] p = plan;
        CgVfxAssets assets = CgVfxAssets.instance();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, Phase.DRAIN.zone)) {
            try {
                assets.drain();
            } catch (RuntimeException e) {
                LOGGER.error("The asset loop's drain threw", e);
            }
            runAll(p[Phase.DRAIN.ordinal()]);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, Phase.PUBLISH.zone)) {
            try {
                assets.publish();
            } catch (RuntimeException e) {
                LOGGER.error("Publishing threw", e);
            }
            runAll(p[Phase.PUBLISH.ordinal()]);
        }
    }

    private static void runAll(Function[] functions) {
        for (Function f : functions) {
            if (f.disabled) continue;
            try {
                f.body.run();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                f.disabled = true;
                LOGGER.error("Boundary function {} threw and is disabled", f.name, t);
            }
        }
    }
}
