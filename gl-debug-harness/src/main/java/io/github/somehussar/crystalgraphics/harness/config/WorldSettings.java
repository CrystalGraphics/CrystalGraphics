package io.github.somehussar.crystalgraphics.harness.config;

/**
 * Immutable, per-run world rendering settings for all 3D interactive scenes.
 *
 * <p>Replaces the mutable {@link WorldConfig} singleton. World settings are
 * resolved once at run startup (from {@link WorldConfig} defaults and any
 * scene-specific overrides) and then exposed read-only to all consumers
 * via {@link HarnessContext#getWorldSettings()}.</p>
 *
 * <p>No runtime-critical code should call {@link WorldConfig#get()} during
 * rendering. Instead, the runner and renderers read from this resolved
 * snapshot. This eliminates mutable global state from the render pipeline
 * and makes world configuration deterministic for the entire run.</p>
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li><b>Immutable</b>: once built, settings cannot change mid-run.
 *       This prevents subtle bugs from mutation during frame rendering.</li>
 *   <li><b>No DI</b>: constructed via a simple {@link Builder} — no framework
 *       or complex override hierarchy required.</li>
 *   <li><b>Default values</b>: the builder pre-populates with the same defaults
 *       as the original {@link WorldConfig} singleton, so a default-constructed
 *       {@code WorldSettings} is drop-in identical to the original.</li>
 * </ul>
 *
 * <p>All colors are stored as RGB float triplets in [0.0, 1.0] range.</p>
 *
 * @see WorldConfig
 * @see HarnessContext#getWorldSettings()
 */
public final class WorldSettings {

    // ── Sky color (RGB) ──
    private final float skyR;
    private final float skyG;
    private final float skyB;

    // ── Floor color (RGB) ──
    private final float floorR;
    private final float floorG;
    private final float floorB;

    // ── Floor geometry ──
    private final float floorHalfSize;

    private WorldSettings(Builder builder) {
        this.skyR = builder.skyR;
        this.skyG = builder.skyG;
        this.skyB = builder.skyB;
        this.floorR = builder.floorR;
        this.floorG = builder.floorG;
        this.floorB = builder.floorB;
        this.floorHalfSize = builder.floorHalfSize;
    }

    // ── Sky color accessors ──

    /** Returns the sky red component in [0.0, 1.0]. */
    public float getSkyR() { return skyR; }

    /** Returns the sky green component in [0.0, 1.0]. */
    public float getSkyG() { return skyG; }

    /** Returns the sky blue component in [0.0, 1.0]. */
    public float getSkyB() { return skyB; }

    // ── Floor color accessors ──

    /** Returns the floor red component in [0.0, 1.0]. */
    public float getFloorR() { return floorR; }

    /** Returns the floor green component in [0.0, 1.0]. */
    public float getFloorG() { return floorG; }

    /** Returns the floor blue component in [0.0, 1.0]. */
    public float getFloorB() { return floorB; }

    // ── Floor geometry ──

    /** Returns the floor half-size extent on X and Z axes. */
    public float getFloorHalfSize() { return floorHalfSize; }

    /**
     * Resolves world settings from the current {@link WorldConfig} singleton
     * defaults. This is the standard resolution path — call once at run
     * startup and pass the result to all consumers.
     *
     * <p>This method reads the singleton's current values and freezes them
     * into an immutable snapshot. After this call, changes to the singleton
     * will NOT be reflected in the returned settings.</p>
     *
     * @return immutable world settings matching the current WorldConfig state
     */
    public static WorldSettings resolveFromDefaults() {
        WorldConfig wc = WorldConfig.get();
        return new Builder()
                .skyColor(wc.getSkyR(), wc.getSkyG(), wc.getSkyB())
                .floorColor(wc.getFloorR(), wc.getFloorG(), wc.getFloorB())
                .floorHalfSize(wc.getFloorHalfSize())
                .build();
    }

    /**
     * Creates a new builder pre-populated with the same defaults as
     * {@link WorldConfig}: aqua sky (0.66, 0.83, 0.92), gray floor
     * (0.5, 0.5, 0.5), and 500.0 floor half-size.
     *
     * @return a new builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "WorldSettings{sky=(" + skyR + ", " + skyG + ", " + skyB
                + "), floor=(" + floorR + ", " + floorG + ", " + floorB
                + "), floorHalfSize=" + floorHalfSize + "}";
    }

    /**
     * Builder for constructing {@link WorldSettings} instances.
     *
     * <p>Pre-populated with the same defaults as {@link WorldConfig}:
     * aqua sky, gray floor, 500.0 half-size. Override only the values
     * you need to change.</p>
     */
    public static final class Builder {

        // Defaults match WorldConfig exactly — preserves current visuals
        private float skyR = 0.66f;
        private float skyG = 0.83f;
        private float skyB = 0.92f;

        private float floorR = 0.5f;
        private float floorG = 0.5f;
        private float floorB = 0.5f;

        private float floorHalfSize = 500.0f;

        private Builder() {
        }

        /**
         * Sets the sky (clear) color.
         *
         * @param r red component [0.0, 1.0]
         * @param g green component [0.0, 1.0]
         * @param b blue component [0.0, 1.0]
         * @return this builder
         */
        public Builder skyColor(float r, float g, float b) {
            this.skyR = r;
            this.skyG = g;
            this.skyB = b;
            return this;
        }

        /**
         * Sets the floor color.
         *
         * @param r red component [0.0, 1.0]
         * @param g green component [0.0, 1.0]
         * @param b blue component [0.0, 1.0]
         * @return this builder
         */
        public Builder floorColor(float r, float g, float b) {
            this.floorR = r;
            this.floorG = g;
            this.floorB = b;
            return this;
        }

        /**
         * Sets the floor half-size extent on X and Z axes.
         *
         * @param halfSize half-size in world units (must be positive)
         * @return this builder
         */
        public Builder floorHalfSize(float halfSize) {
            this.floorHalfSize = halfSize;
            return this;
        }

        /**
         * Builds an immutable {@link WorldSettings} from the current builder state.
         *
         * @return the constructed world settings
         */
        public WorldSettings build() {
            return new WorldSettings(this);
        }
    }
}
