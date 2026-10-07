package com.crystalgraphics.vfx.particle.gpu;

/**
 * What a definition's size and opacity curves are sampled by: progress through a particle's life, or its speed between
 * two thresholds (Niagara's Scale Sprite Size by Speed, Unity's Size by Speed). Range applies it per parameter row.
 *
 * <pre>{@code
 * public CgVfxCurveDomain curveDomain() { return CgVfxCurveDomain.LIFE; }               // the default
 * public CgVfxCurveDomain curveDomain() { return CgVfxCurveDomain.speed(0.5f, 6f); }    // sample 0 at 0.5 blocks/s or slower
 * }</pre>
 *
 * <ul>
 *   <li>Speed is the particle's own, stepped velocity, never interpolated: a curve by speed changes at steps.</li>
 *   <li>A look's {@code motion.w} and an object's {@code custom1.x} stay life progress whichever domain the curves use.</li>
 * </ul>
 */
public final class CgVfxCurveDomain {

    /** Progress through life, age over lifetime. */
    public static final CgVfxCurveDomain LIFE = new CgVfxCurveDomain(-1f, -1f);

    private final float min, max;

    private CgVfxCurveDomain(float min, float max) {
        this.min = min;
        this.max = max;
    }

    /** Speeds in blocks a second: at {@code min} or slower a curve reads its first sample, at {@code max} or faster its last. */
    public static CgVfxCurveDomain speed(float min, float max) {
        if (!(min >= 0f && max > min)) throw new IllegalArgumentException("speeds " + min + " to " + max + ": need 0 <= min < max");
        return new CgVfxCurveDomain(min, max);
    }

    public boolean bySpeed() {
        return min >= 0f;
    }

    /** The slowest speed; -1 over life. */
    public float min() {
        return min;
    }

    /** The fastest speed; -1 over life. */
    public float max() {
        return max;
    }

    /** Where a particle samples its curves, 0 to 1: what Range's {@code range_curve_at} works out. */
    public float at(float age, float life, float vx, float vy, float vz) {
        if (!bySpeed()) return Math.min(age / life, 1f);
        float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        return Math.max(0f, Math.min((speed - min) / (max - min), 1f));
    }
}
