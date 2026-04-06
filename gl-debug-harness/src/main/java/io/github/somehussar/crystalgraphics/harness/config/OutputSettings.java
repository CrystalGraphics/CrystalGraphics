package io.github.somehussar.crystalgraphics.harness.config;

import java.io.File;

/**
 * Typed, immutable container for scene output configuration.
 *
 * <p>Encapsulates the output directory path and the output name prefix
 * that scenes use when writing artifacts (screenshots, reports, etc.).
 * This replaces the loose mutable {@code outputDir} and {@code outputName}
 * fields that were previously scattered across {@link HarnessContext}.</p>
 *
 * <p>The output directory is the scene-specific subdirectory
 * (e.g. {@code harness-output/text-3d/}). The output name prefix
 * defaults to the scene's mode ID but can be overridden via
 * {@code --output-name=PREFIX}.</p>
 *
 * <p><b>Immutability</b>: once created, the output settings do not change
 * during a scene's execution. This is intentional — output paths are
 * resolved before scene init and remain stable throughout the run.</p>
 *
 * @see HarnessContext#getOutputSettings()
 */
public final class OutputSettings {

    private final String outputDir;
    private final String outputName;

    /**
     * Creates output settings with the given directory and name prefix.
     *
     * @param outputDir  the scene-specific output directory path (must not be null)
     * @param outputName the filename prefix for artifacts (must not be null)
     * @throws IllegalArgumentException if either argument is null
     */
    public OutputSettings(String outputDir, String outputName) {
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        if (outputName == null) {
            throw new IllegalArgumentException("outputName must not be null");
        }
        this.outputDir = outputDir;
        this.outputName = outputName;
    }

    /**
     * Returns the scene-specific output directory path.
     *
     * <p>This is the fully resolved path where all scene artifacts are written,
     * e.g. {@code gl-debug-harness/harness-output/text-3d/}.</p>
     *
     * @return the output directory, never null
     */
    public String getOutputDir() {
        return outputDir;
    }

    /**
     * Returns the output name prefix for filenames.
     *
     * <p>For example, if this is {@code "test1"}, screenshots will be named
     * {@code test1-normal.png}, {@code test1-paused.png}, etc.</p>
     *
     * @return the output name prefix, never null
     */
    public String getOutputName() {
        return outputName;
    }

    /**
     * Builds a full artifact filename by combining the output name prefix
     * with a descriptive suffix.
     *
     * <p>For example, with outputName="test1" and suffix="normal",
     * returns "test1-normal.png".</p>
     *
     * @param suffix the descriptive suffix (without leading dash or extension)
     * @return the full filename including .png extension
     */
    public String buildFilename(String suffix) {
        return outputName + "-" + suffix + ".png";
    }

    /**
     * Builds a single-artifact filename using only the configured output name.
     *
     * <p>This is the managed-scene counterpart to {@link #buildFilename(String)}.
     * For example, with outputName="triangle" and extension="png", returns
     * "triangle.png".</p>
     *
     * @param extension the file extension without a leading dot
     * @return the full filename including the extension
     */
    public String buildBaseFilename(String extension) {
        return outputName + "." + extension;
    }

    /**
     * Builds a full artifact path by combining the output directory,
     * output name prefix, and a descriptive suffix.
     *
     * @param suffix the descriptive suffix (without leading dash or extension)
     * @return the full file path including directory and .png extension
     */
    public String buildPath(String suffix) {
        return outputDir + File.separator + buildFilename(suffix);
    }

    @Override
    public String toString() {
        return "OutputSettings[dir=" + outputDir + ", name=" + outputName + "]";
    }
}
