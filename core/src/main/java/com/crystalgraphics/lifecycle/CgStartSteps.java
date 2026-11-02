package com.crystalgraphics.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The engine's start, step by step: each step's milliseconds since the last, logged as one line once the start ends.
 * The start runs before the loading screen's first frame, so nothing else shows where its time goes.
 *
 * <pre>{@code
 * [start] 3120 ms: capabilities 41, link worker 3, fallback textures 12, world renderer 260, vfx 1810, ...
 * }</pre>
 */
final class CgStartSteps {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final StringBuilder LINE = new StringBuilder();
    private static long begun, last;

    private CgStartSteps() {
    }

    /** The start begins. */
    static void begin() {
        begun = last = System.nanoTime();
        LINE.setLength(0);
    }

    /** {@code step} has just ended. Render thread. */
    static void step(String step) {
        if (begun == 0L) return;
        long now = System.nanoTime();
        if (LINE.length() > 0) LINE.append(", ");
        LINE.append(step).append(' ').append((now - last) / 1_000_000L);
        last = now;
    }

    /** The start ended: its line logged. */
    static void end() {
        if (begun == 0L) return;
        LOGGER.info("[start] {} ms: {}", (System.nanoTime() - begun) / 1_000_000L, LINE);
        begun = 0L;
    }
}
