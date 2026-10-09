package com.crystalgraphics.render.post.volume;

import java.util.Objects;

/**
 * Screen-wide looks a {@link CgPostVolume} sets, Unity's Volume profile in small: each value a setter touched
 * overrides, the rest are left to whatever volume sits under it. The stack blends every volume's into one each firing.
 *
 * <pre>{@code
 * CgPostSettings burst = new CgPostSettings().flash(1.5f).bloom(2f);        // a stop and a half brighter, twice the glow
 * CgPostSettings hit = new CgPostSettings().impact(CgImpact.LINES, 1f).chromatic(0.8f);
 * }</pre>
 *
 * <ul>
 *   <li>Written from any thread, read on the render thread: floats, read at most a frame late.</li>
 *   <li>Neutral values are a look's absence: bloom 1, flash 0, vignette 0, chromatic 0, impact 0.</li>
 *   <li>The focus (where chromatic aberration and speed lines centre) defaults to the volume's position on screen,
 *       or the screen's centre for a volume with none.</li>
 * </ul>
 */
public final class CgPostSettings {

    public static final int BLOOM = 1, FLASH = 1 << 1, VIGNETTE = 1 << 2, CHROMATIC = 1 << 3, IMPACT = 1 << 4, FOCUS = 1 << 5;

    // Volatile: written on any thread, read on the render thread.
    volatile int overrides;
    volatile float bloom = 1f, flash, vignette, chromatic, impact;
    volatile CgImpactFrame impactLook = CgImpactFrame.NEGATIVE;
    volatile int impactSeed;
    volatile float focusX = 0.5f, focusY = 0.5f;
    /** Resolved only: the weight behind the focus so far, which averages it rather than pulling it to the centre. */
    private float focusWeight;

    /** Multiplies the bloom's intensity: 2 doubles the glow. */
    public CgPostSettings bloom(float scale) {
        bloom = Math.max(0f, scale);
        overrides |= BLOOM;
        return this;
    }

    /** Brightens the picture by {@code stops} of exposure: 1 doubles it. Emitted light is not brightened with it. */
    public CgPostSettings flash(float stops) {
        flash = stops;
        overrides |= FLASH;
        return this;
    }

    /** Darkens the screen's corners, 0 to 1. */
    public CgPostSettings vignette(float amount) {
        vignette = clamp(amount);
        overrides |= VIGNETTE;
        return this;
    }

    /** Splits red and blue apart from the focus outward, 0 to 1: a shock front's aberration. */
    public CgPostSettings chromatic(float amount) {
        chromatic = clamp(amount);
        overrides |= CHROMATIC;
        return this;
    }

    /** Turns the picture {@code amount} (0 to 1) of the way into preset {@code look}: an impact frame. */
    public CgPostSettings impact(CgImpact look, float amount) {
        return impact(Objects.requireNonNull(look, "look").frame(), amount, 0);
    }

    /**
     * Turns the picture {@code amount} (0 to 1) of the way into {@code look}, drawn from {@code seed}: a new seed draws
     * its lines and strokes anew ({@link CgImpactSequence#seed}).
     */
    public CgPostSettings impact(CgImpactFrame look, float amount, int seed) {
        impactLook = Objects.requireNonNull(look, "look");
        impactSeed = seed;
        impact = clamp(amount);
        overrides |= IMPACT;
        return this;
    }

    /** Where on screen, 0 to 1 from the bottom left, aberration and speed lines centre; else the volume's position. */
    public CgPostSettings focus(float x, float y) {
        focusX = x;
        focusY = y;
        overrides |= FOCUS;
        return this;
    }

    /** Whether a setter set {@code bit}: the stack's. */
    public boolean overrides(int bit) {
        return (overrides & bit) != 0;
    }

    public float bloom() {
        return bloom;
    }

    public float flash() {
        return flash;
    }

    public float vignette() {
        return vignette;
    }

    public float chromatic() {
        return chromatic;
    }

    public float impact() {
        return impact;
    }

    public CgImpactFrame impactLook() {
        return impactLook;
    }

    public int impactSeed() {
        return impactSeed;
    }

    public float focusX() {
        return focusX;
    }

    public float focusY() {
        return focusY;
    }

    /** Back to no look at all, nothing overridden. */
    public void reset() {
        overrides = 0;
        bloom = 1f;
        flash = vignette = chromatic = impact = 0f;
        impactLook = CgImpactFrame.NEGATIVE;
        impactSeed = 0;
        focusX = focusY = 0.5f;
        focusWeight = 0f;
    }

    /**
     * Moves each value {@code from} overrides {@code weight} (0 to 1) of the way to it; a look takes the heavier's, and
     * the focus is the weighted average of every volume's that sets one.
     */
    public void blend(CgPostSettings from, float weight, float focusX, float focusY) {
        int o = from.overrides;
        if ((o & BLOOM) != 0) bloom += (from.bloom - bloom) * weight;
        if ((o & FLASH) != 0) flash += (from.flash - flash) * weight;
        if ((o & VIGNETTE) != 0) vignette += (from.vignette - vignette) * weight;
        if ((o & CHROMATIC) != 0) chromatic += (from.chromatic - chromatic) * weight;
        if ((o & IMPACT) != 0) {
            if (weight * from.impact >= impact) {
                impactLook = from.impactLook;
                impactSeed = from.impactSeed;
            }
            impact += (from.impact - impact) * weight;
        }
        if ((o & (CHROMATIC | IMPACT | FOCUS)) != 0 && weight > 0f) {
            // A position, so averaged by weight: one volume keeps its own focus however faint it is.
            float total = focusWeight + weight;
            this.focusX = (this.focusX * focusWeight + focusX * weight) / total;
            this.focusY = (this.focusY * focusWeight + focusY * weight) / total;
            focusWeight = total;
        }
        overrides |= o;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
