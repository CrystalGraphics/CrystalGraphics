package io.github.somehussar.crystalgraphics.harness.config;

/**
 * Singleton holding global world rendering settings for all 3D scenes.
 *
 * <p>Provides sky color, floor color, and other world-level parameters
 * accessible from anywhere without passing as method parameters.
 * Scenes read directly from this singleton via {@link #get()}.</p>
 *
 * <p>All colors are stored as RGB float triplets in [0.0, 1.0] range.</p>
 */
public final class WorldConfig {

    private static final WorldConfig INSTANCE = new WorldConfig();

    // Sky color: aqua (RGB ~0.0, 0.7, 1.0)
    private float skyR =  0.66f;
    private float skyG =  0.83f;
    private float skyB =  0.92f;

    // Floor color: gray (RGB 0.5, 0.5, 0.5)
    private float floorR = 0.5f;
    private float floorG = 0.5f;
    private float floorB = 0.5f;  

    // Floor half-size extent on X and Z axes (large enough to reach horizon)
    private float floorHalfSize = 500.0f;

    private WorldConfig() {
    }

    /**
     * Returns the global WorldConfig singleton instance.
     */
    public static WorldConfig get() {
        return INSTANCE;
    }

    // ── Sky color accessors ──

    public float getSkyR() { return skyR; }
    public float getSkyG() { return skyG; }
    public float getSkyB() { return skyB; }

    public void setSkyColor(float r, float g, float b) {
        this.skyR = r;
        this.skyG = g;
        this.skyB = b;
    }

    // ── Floor color accessors ──

    public float getFloorR() { return floorR; }
    public float getFloorG() { return floorG; }
    public float getFloorB() { return floorB; }

    public void setFloorColor(float r, float g, float b) {
        this.floorR = r;
        this.floorG = g;
        this.floorB = b;
    }

    // ── Floor geometry ──

    public float getFloorHalfSize() { return floorHalfSize; }

    public void setFloorHalfSize(float halfSize) {
        this.floorHalfSize = halfSize;
    }

    @Override
    public String toString() {
        return "WorldConfig{sky=(" + skyR + ", " + skyG + ", " + skyB
                + "), floor=(" + floorR + ", " + floorG + ", " + floorB
                + "), floorHalfSize=" + floorHalfSize + "}";
    }
}
