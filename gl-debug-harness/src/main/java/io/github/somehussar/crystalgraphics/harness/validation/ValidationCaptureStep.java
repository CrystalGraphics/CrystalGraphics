package io.github.somehussar.crystalgraphics.harness.validation;

/**
 * Immutable value object describing a single scripted validation capture step.
 *
 * <p>Each step encapsulates the complete intent of one validation screenshot:
 * where to position the camera (position + orientation), whether the scene
 * should be paused during capture, the semantic suffix for the output filename,
 * and the base timestamp at which the step should execute.</p>
 *
 * <p>Steps are composed into a sequence and scheduled by
 * {@link ValidationChoreographer}, which handles the timing details
 * (pose setup → wait one frame → capture → optional unpause).</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * ValidationCaptureStep step = ValidationCaptureStep.builder("front-view", 0.5)
 *     .cameraPosition(0.0f, 2.0f, 8.0f)
 *     .cameraOrientation(0.0f, -10.0f)
 *     .build();
 * }</pre>
 *
 * @see ValidationChoreographer
 */
public final class ValidationCaptureStep {

    private final String suffix;
    private final double baseTimeSeconds;
    private final float camX;
    private final float camY;
    private final float camZ;
    private final float yaw;
    private final float pitch;
    private final boolean paused;
    private final String logPrefix;

    private ValidationCaptureStep(Builder builder) {
        this.suffix = builder.suffix;
        this.baseTimeSeconds = builder.baseTimeSeconds;
        this.camX = builder.camX;
        this.camY = builder.camY;
        this.camZ = builder.camZ;
        this.yaw = builder.yaw;
        this.pitch = builder.pitch;
        this.paused = builder.paused;
        this.logPrefix = builder.logPrefix;
    }

    /** The semantic suffix for the output filename (e.g. "front-view", "paused"). */
    public String getSuffix() { return suffix; }

    /** The base timestamp (seconds from scene start) at which the camera pose is set. */
    public double getBaseTimeSeconds() { return baseTimeSeconds; }

    /** Camera X position. */
    public float getCamX() { return camX; }

    /** Camera Y position. */
    public float getCamY() { return camY; }

    /** Camera Z position. */
    public float getCamZ() { return camZ; }

    /** Camera yaw in degrees. */
    public float getYaw() { return yaw; }

    /** Camera pitch in degrees. */
    public float getPitch() { return pitch; }

    /** Whether the scene should be paused during this capture. */
    public boolean isPaused() { return paused; }

    /** Optional log prefix for structured logging (defaults to empty). */
    public String getLogPrefix() { return logPrefix; }

    /**
     * Creates a new builder for a validation capture step.
     *
     * @param suffix          the semantic suffix for the output filename
     * @param baseTimeSeconds the base timestamp for the camera setup
     * @return a new builder
     */
    public static Builder builder(String suffix, double baseTimeSeconds) {
        return new Builder(suffix, baseTimeSeconds);
    }

    @Override
    public String toString() {
        return "ValidationCaptureStep{suffix='" + suffix + "', t=" + baseTimeSeconds
                + "s, pos=(" + camX + "," + camY + "," + camZ + ")"
                + ", yaw=" + yaw + ", pitch=" + pitch
                + (paused ? ", PAUSED" : "") + "}";
    }

    /**
     * Builder for {@link ValidationCaptureStep}.
     *
     * <p>Only {@code suffix} and {@code baseTimeSeconds} are required.
     * All other fields default to sensible values (origin position,
     * zero rotation, not paused).</p>
     */
    public static final class Builder {
        private final String suffix;
        private final double baseTimeSeconds;
        private float camX = 0.0f;
        private float camY = 0.0f;
        private float camZ = 0.0f;
        private float yaw = 0.0f;
        private float pitch = 0.0f;
        private boolean paused = false;
        private String logPrefix = "";

        private Builder(String suffix, double baseTimeSeconds) {
            if (suffix == null || suffix.isEmpty()) {
                throw new IllegalArgumentException("suffix must not be null or empty");
            }
            if (baseTimeSeconds < 0.0) {
                throw new IllegalArgumentException("baseTimeSeconds must be >= 0: " + baseTimeSeconds);
            }
            this.suffix = suffix;
            this.baseTimeSeconds = baseTimeSeconds;
        }

        /** Sets the camera position for this step. */
        public Builder cameraPosition(float x, float y, float z) {
            this.camX = x;
            this.camY = y;
            this.camZ = z;
            return this;
        }

        /** Sets the camera yaw and pitch (degrees) for this step. */
        public Builder cameraOrientation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            return this;
        }

        /** Marks this step as requiring the scene to be paused during capture. */
        public Builder paused() {
            this.paused = true;
            return this;
        }

        /** Sets an optional log prefix for structured logging (e.g. scene class name). */
        public Builder logPrefix(String prefix) {
            this.logPrefix = prefix != null ? prefix : "";
            return this;
        }

        /** Builds the immutable step. */
        public ValidationCaptureStep build() {
            return new ValidationCaptureStep(this);
        }
    }
}
