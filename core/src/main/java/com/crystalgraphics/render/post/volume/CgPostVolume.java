package com.crystalgraphics.render.post.volume;

import java.util.function.Consumer;

/**
 * Where and when a {@link CgPostSettings} applies, Unreal's Post Process Volume and Unity's Volume: everywhere, or
 * within a radius of a point with a blend distance beyond it, at a weight an effect moves over its life. Volumes blend
 * by priority, a higher one over a lower. Made by {@code CgPostStack.get().volume(priority, settings)}.
 *
 * <pre>{@code
 * CgPostVolume burst = CgPostStack.get().volume(10, new CgPostSettings().flash(1.5f).bloom(2f))
 *         .at(x, y, z).radius(24f).blend(16f);
 * burst.weight(1f - age / life);   // each frame, from the effect's moment
 * burst.close();                   // when the effect ends
 *
 * CgPostVolume mood = CgPostStack.get().volume(0, new CgPostSettings().vignette(0.3f));   // global: no position
 * }</pre>
 *
 * <ul>
 *   <li>Any thread may make, move, weigh or close one; the stack reads it at the next firing.</li>
 *   <li>Its weight applies whatever the distance; the distance only lowers it, outside the radius.</li>
 *   <li>Nothing an effect does writes the global settings: an effect's look is always a volume.</li>
 * </ul>
 */
public final class CgPostVolume implements AutoCloseable {

    private final int priority;
    private final CgPostSettings settings;
    private final Consumer<CgPostVolume> closer;
    private volatile boolean placed;
    private volatile double x, y, z;
    private volatile float radius = Float.POSITIVE_INFINITY, blend, weight = 1f;

    /** The stack's. */
    public CgPostVolume(int priority, CgPostSettings settings, Consumer<CgPostVolume> closer) {
        this.priority = priority;
        this.settings = settings;
        this.closer = closer;
    }

    /** Centres it at absolute world position {@code (x, y, z)}: from then on it reaches {@link #radius} round it. */
    public CgPostVolume at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        placed = true;
        return this;
    }

    /** Full weight within {@code radius} blocks of its position. Unbounded until set. */
    public CgPostVolume radius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    /** Fades from full weight at its radius to none {@code distance} blocks beyond it. 0 until set: a hard edge. */
    public CgPostVolume blend(float distance) {
        this.blend = Math.max(0f, distance);
        return this;
    }

    /** How much it applies, 0 to 1: what an effect animates. 1 until set. */
    public CgPostVolume weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    public int priority() {
        return priority;
    }

    public CgPostSettings settings() {
        return settings;
    }

    public boolean placed() {
        return placed;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** Its weight for a camera at absolute {@code (cx, cy, cz)}: its own, lowered past its radius. */
    public float weightAt(double cx, double cy, double cz) {
        float w = weight;
        if (w <= 0f || !placed || radius == Float.POSITIVE_INFINITY) return w;
        double dx = cx - x, dy = cy - y, dz = cz - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= radius) return w;
        if (blend <= 0f) return 0f;
        return w * (float) Math.max(0.0, 1.0 - (d - radius) / blend);
    }

    /** Stops it applying. */
    @Override
    public void close() {
        closer.accept(this);
    }
}
