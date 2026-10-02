package com.crystalgraphics.vfx.particle;

/**
 * The air every particle of a {@code CgVfxSystem} moves through: a wind that swells and veers in gusts, the same for every
 * effect, so the embers of two blasts drift the same way. Read by {@link CgVfxModule.Wind}; the system settles it every
 * tick.
 *
 * <pre>{@code
 * vfx.air().wind(1.5f, 0f, 0.5f).gusts(0.6f, 0.25f);   // ~1.6 blocks a second, swelling by up to 60%, a gust every 4 s
 * vfx.air().wind(0f, 0f, 0f);                          // still air
 * }</pre>
 */
public final class CgVfxAir {

    private float baseX = 0.8f, baseY, baseZ = 0.3f;
    private float gust = 0.5f, gustRate = 0.3f;
    private float windX, windY, windZ;

    /** The steady wind, in blocks a second. */
    public CgVfxAir wind(float x, float y, float z) {
        baseX = x;
        baseY = y;
        baseZ = z;
        return this;
    }

    /** How strongly the wind gusts, as a share of it, and how many gusts a second. */
    public CgVfxAir gusts(float strength, float rate) {
        gust = strength;
        gustRate = rate;
        return this;
    }

    /** Settles the wind for the moment {@code time}, in seconds. */
    public void tick(float time) {
        double phase = time * gustRate * 6.2831853;
        float swell = 1f + gust * (float) (0.6 * Math.sin(phase) + 0.4 * Math.sin(phase * 2.43 + 1.7));
        float veer = gust * 0.4f * (float) Math.sin(phase * 0.65 + 0.6);
        windX = (baseX - baseZ * veer) * swell;
        windY = baseY * swell;
        windZ = (baseZ + baseX * veer) * swell;
    }

    public float windX() {
        return windX;
    }

    public float windY() {
        return windY;
    }

    public float windZ() {
        return windZ;
    }
}
