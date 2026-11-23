package com.crystalgraphics.mc.render;

/**
 * A fixed-step pace for what animates slower than the frame rate: true on the frames a step falls due. Minecraft's tick
 * timer's shape, so uneven frames average out to the rate asked. Render thread.
 *
 * <pre>{@code
 * private final CgPace glint = new CgPace();
 *
 * if (glint.step(System.nanoTime(), 20f)) redrawAnimated();   // about 20 frames a second, whatever the frame rate
 * }</pre>
 *
 * <ul>
 *   <li>A rate of 0 or less is due every frame.</li>
 *   <li>A stall of more than four steps starts again from now rather than falling due every frame to catch up.</li>
 * </ul>
 */
public final class CgPace {
    private long at = Long.MIN_VALUE / 2;

    /** Whether a step falls due at {@code now} (nanoseconds) at {@code hz} a second; steps it when it does. */
    public boolean step(long now, float hz) {
        if (hz <= 0f) return true;
        long every = (long) (1e9f / hz);
        if (now - at < every) return false;
        at = now - at > 4 * every ? now : at + every;
        return true;
    }
}
